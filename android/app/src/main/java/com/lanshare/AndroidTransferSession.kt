package com.lanshare

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import org.webrtc.DataChannel

interface TransferDataChannel {
    val identity: Any
    val isOpen: Boolean

    /** Bytes queued in the native SCTP send buffer, mirroring the web `bufferedAmount`. */
    val bufferedAmount: Long
    fun sendText(text: String): Boolean
    fun sendBinary(bytes: ByteArray): Boolean
}

class WebRtcTransferDataChannel(private val channel: DataChannel) : TransferDataChannel {
    override val identity: Any = channel
    override val isOpen: Boolean
        get() = channel.state() == DataChannel.State.OPEN
    override val bufferedAmount: Long
        get() = channel.bufferedAmount()

    override fun sendText(text: String): Boolean =
        channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))

    override fun sendBinary(bytes: ByteArray): Boolean =
        channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), true))
}

private interface IncomingFileSink {
    fun write(bytes: ByteArray)
    fun finish(): ReceivedTransferPayload
    fun abort()
}

private class MemoryIncomingFileSink(private val offer: FileOffer) : IncomingFileSink {
    private val chunks = mutableListOf<ByteArray>()

    override fun write(bytes: ByteArray) {
        chunks += bytes.copyOf()
    }

    override fun finish(): ReceivedTransferPayload = ReceivedTransferPayload(offer = offer, chunks = chunks.toList())

    override fun abort() = chunks.clear()
}

private class FileIncomingFileSink(
    private val offer: FileOffer,
    directory: File,
) : IncomingFileSink {
    private val file = File.createTempFile("incoming-", ".part", directory.apply { mkdirs() })
    private val output: OutputStream = FileOutputStream(file)
    private var closed = false

    override fun write(bytes: ByteArray) {
        check(!closed) { "Incoming sink is closed." }
        output.write(bytes)
    }

    override fun finish(): ReceivedTransferPayload {
        if (!closed) {
            output.flush()
            output.close()
            closed = true
        }
        return ReceivedTransferPayload(offer = offer, chunks = emptyList(), sourceFile = file)
    }

    override fun abort() {
        if (!closed) {
            runCatching { output.close() }
            closed = true
        }
        file.delete()
    }
}

class AndroidTransferSession(
    private val channel: TransferDataChannel,
    private val onLog: (String) -> Unit,
    private val onReceivedFile: (ReceivedTransferPayload) -> Unit,
    private val onLocalTransfer: (TransferCardState?) -> Unit,
    private val onPeerTransfer: (TransferCardState?) -> Unit,
    /** Reports a chat line in either direction; text never touches the transfer cards. */
    private val onTextMessage: (ChatMessageState) -> Unit = {},
    /** Reports a retraction the chat layer must reflect in place (web `onRecalled`). */
    private val onRecalled: (Recall) -> Unit = {},
    private val limits: TransferLimits = defaultTransferLimits(),
    incomingDirectory: File? = null,
    /** Streams the accepted file off the caller thread; the drain wait must be able to suspend. */
    private val sendScope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** A recalled chat entry: the sender removed its text message or file bubble. */
    data class Recall(val target: RecallTarget, val targetId: String)
    private data class IncomingTransfer(
        val offer: FileOffer,
        val sink: IncomingFileSink,
        var nextIndex: Int = 0,
        var receivedBytes: Long = 0,
        var expectedChunk: TransferControlMessage.ChunkMetaMessage? = null,
    )

    private val sender = TransferSender(captureFramesForTests = false)
    private val outgoingFiles = mutableMapOf<String, OutgoingFile>()
    private val incomingOffers = mutableMapOf<String, FileOffer>()
    private val queuedIncomingAccepts = mutableListOf<String>()
    private val completedIncomingFileIds = mutableSetOf<String>()
    private val terminalTransferIds = mutableSetOf<String>()
    /** File ids the sender retracted; their pending handle is gone, so a retry cannot revive them. */
    private val forgottenFileIds = mutableSetOf<String>()
    private var incoming: IncomingTransfer? = null
    private var activeSendingFileId: String? = null
    private var closed = false

    private data class OutgoingFile(
        val offer: FileOffer,
        val handle: TransferContentHandle,
    )

    private val sinkFactory: (FileOffer) -> IncomingFileSink = { offer ->
        if (incomingDirectory == null) MemoryIncomingFileSink(offer) else FileIncomingFileSink(offer, incomingDirectory)
    }

    fun isFor(channel: TransferDataChannel): Boolean = this.channel.identity === channel.identity

    /** Whether the underlying DataChannel can carry an offer right now. */
    val isChannelOpen: Boolean
        get() = channel.isOpen

    fun cleanup() {
        closed = true
        incoming?.sink?.abort()
        incoming = null
        outgoingFiles.values.forEach { it.handle.cleanup() }
        outgoingFiles.clear()
        sender.cleanup()
        incomingOffers.clear()
        queuedIncomingAccepts.clear()
        completedIncomingFileIds.clear()
        terminalTransferIds.clear()
        forgottenFileIds.clear()
        activeSendingFileId = null
    }

    fun offerFile(name: String, bytes: ByteArray, mime: String = "application/octet-stream") =
        offerFile(ByteArrayTransferContentHandle(name = name, bytes = bytes, mime = mime))

    fun offerFile(handle: TransferContentHandle) {
        if (closed) {
            handle.cleanup()
            return
        }
        val validation = if (handle.size > limits.maxFileSize) "File exceeds the connection limit." else null
        if (validation != null) {
            handle.cleanup()
            onLog("transfer: offer rejected ${handle.name}: $validation")
            return
        }
        val offer = runCatching { sender.offerFile(handle, limits.chunkSize) }.getOrElse {
            handle.cleanup()
            onLog("transfer: offer rejected ${handle.name}: ${it.message}")
            return
        }
        outgoingFiles[offer.fileId] = OutgoingFile(offer, handle)
        if (!sendControl(TransferControlMessage.FileOfferMessage.create(offer))) {
            outgoingFiles.remove(offer.fileId)
            sender.discard(offer.fileId)
            handle.cleanup()
            onLog("transfer: offer failed ${handle.name}")
            return
        }
        onLocalTransfer(offer.localCard(TransferCardStatus.WaitingForWebAcceptance, 0, TransferCardAction.Retry))
        onLog("transfer: offered ${offer.name} (${offer.size} bytes)")
    }

    fun acceptIncoming(fileId: String? = null) {
        val offer = fileId?.let { incomingOffers[it] }
            ?: incomingOffers.values.firstOrNull { it.fileId != incoming?.offer?.fileId && it.fileId !in completedIncomingFileIds }
            ?: return
        acceptIncomingOffer(offer)
    }

    fun acceptAllIncoming() {
        incomingOffers.keys.toList().forEach { fileId ->
            if (fileId !in completedIncomingFileIds) incomingOffers[fileId]?.let(::acceptIncomingOffer)
        }
    }

    private fun acceptIncomingOffer(offer: FileOffer) {
        if (offer.fileId in completedIncomingFileIds || offer.fileId == incoming?.offer?.fileId || queuedIncomingAccepts.contains(offer.fileId)) return
        if (incoming != null || activeSendingFileId != null) {
            if (limits.maxActiveTransfers > 0) queuedIncomingAccepts += offer.fileId
            return
        }
        val sink = runCatching { sinkFactory(offer) }.getOrElse {
            onLog("transfer: unable to create receive sink ${it.message}")
            return
        }
        incoming = IncomingTransfer(offer = offer, sink = sink)
        if (!sendControl(TransferControlMessage.FileAcceptMessage.create(offer))) {
            abortIncoming(offer.fileId)
            return
        }
        onPeerTransfer(offer.peerCard(TransferCardStatus.ReceivingFromWeb, 0, null))
        onLog("transfer: accepted ${offer.name}")
    }

    fun cancelTransfer(fileId: String) {
        val outgoing = outgoingFiles[fileId]?.offer
        val offer = outgoing ?: incomingOffers[fileId] ?: return
        sendControl(TransferControlMessage.TransferCancelMessage(
            messageId = "msg_${java.util.UUID.randomUUID()}",
            transferId = offer.transferId,
            fileId = offer.fileId,
            reason = "user_cancel",
        ))
        onLocalTransfer(outgoing?.localCard(TransferCardStatus.Canceled, null, null))
        onPeerTransfer(incomingOffers[fileId]?.peerCard(TransferCardStatus.Canceled, null, null))
        outgoingFiles.remove(fileId)?.handle?.cleanup()
        incomingOffers.remove(fileId)
        queuedIncomingAccepts.remove(fileId)
        completedIncomingFileIds.remove(fileId)
        terminalTransferIds += offer.transferId
        sender.discard(fileId)
        if (incoming?.offer?.fileId == fileId) abortIncoming(fileId)
        if (activeSendingFileId == fileId) activeSendingFileId = null
        onLog("transfer: canceled ${offer.name}")
        acceptNextQueuedIncoming()
    }

    /**
     * Sends one chat line and reports it as an outgoing entry. Refused (false,
     * before any byte is sent) when the channel is not open or the text exceeds
     * the wire limit; the caller owns the oversize-as-file fallback.
     */
    fun sendText(text: String): Boolean {
        if (closed || !channel.isOpen) {
            onLog("transfer: channel not open text_message")
            return false
        }
        if (isTextTooLarge(text)) {
            onLog("chat: text too large")
            return false
        }
        val message = createTextMessage(text)
        if (!sendControl(message)) return false
        onTextMessage(ChatMessageState(id = message.messageId, outgoing = true, text = text, atMs = nowMs()))
        return true
    }

    /**
     * Retracts a chat entry on both sides (web `recall`). A recalled file drops
     * the pending offer/stream locally like [cancelTransfer], but emits no
     * canceled card and no `transfer_cancel`: the peer learns from the recall.
     */
    fun recall(target: RecallTarget, targetId: String) {
        if (closed) return
        if (target == RecallTarget.FILE) {
            // Only `forgetFile` may touch the bookkeeping: releasing the handle first removes the
            // outgoing entry, so `forgetFile` would hit its early return and leave the stream running.
            forgetFile(targetId)
        }
        sendControl(createRecallMessage(target, targetId))
        onRecalled(Recall(target, targetId))
    }

    fun retry(fileId: String) {
        if (fileId in forgottenFileIds) return
        val outgoing = outgoingFiles.remove(fileId) ?: return
        sender.discard(fileId)
        onLocalTransfer(
            outgoing.offer.localCard(TransferCardStatus.Canceled, null, null).copy(
                canCancel = false,
                canRetry = false,
            ),
        )
        offerFile(outgoing.handle)
    }

    fun handleText(text: String) {
        val message = try {
            TransferJson.decode(text)
        } catch (error: IllegalArgumentException) {
            // An oversize or empty chat line is a bad message, not a broken transfer:
            // drop it without failing the active file, as the chat layer owns its limits.
            if (error.message == INVALID_TEXT_MESSAGE_REASON) {
                onLog("chat: ignored invalid text")
                return
            }
            rejectMalformedChunkDigest(text)
            onLog("transfer: invalid control ${error.message}")
            return
        }
        // Unknown message types decode to null: ignore them instead of failing the transfer.
        if (message == null) {
            onLog("transfer: ignored unknown control")
            return
        }
        when (message) {
            is TransferControlMessage.TextMessage -> handleTextMessage(message)
            is TransferControlMessage.MessageRecallMessage -> handleMessageRecall(message)
            is TransferControlMessage.FileOfferMessage -> handleFileOffer(message)
            is TransferControlMessage.FileAcceptMessage -> handleFileAccept(message)
            is TransferControlMessage.ChunkMetaMessage -> handleChunkMeta(message)
            is TransferControlMessage.FileCompleteMessage -> completeIncoming(message)
            is TransferControlMessage.FileRejectMessage -> {
                if (sender.pendingOfferFor(message.fileId)?.transferId == message.transferId) {
                    onLocalTransfer(sender.offerFor(message.fileId)?.localCard("${TransferCardStatus.RejectedPrefix}${message.reason}", null, TransferCardAction.Retry))
                    sender.discard(message.fileId)
                }
            }
            is TransferControlMessage.TransferErrorMessage -> {
                if (sender.pendingOfferFor(message.fileId)?.transferId == message.transferId) {
                    onLocalTransfer(sender.offerFor(message.fileId)?.localCard("${TransferCardStatus.ErrorPrefix}${message.reason}", null, TransferCardAction.Retry))
                }
                abortIncomingIfIdentity(message.transferId, message.fileId)
            }
            is TransferControlMessage.TransferCancelMessage -> {
                val local = sender.pendingOfferFor(message.fileId)
                if (local?.transferId == message.transferId) {
                    onLocalTransfer(local.localCard(TransferCardStatus.Canceled, null, TransferCardAction.Retry))
                    sender.discard(message.fileId)
                }
                incomingOffers[message.fileId]?.takeIf { it.transferId == message.transferId }?.let {
                    onPeerTransfer(it.peerCard(TransferCardStatus.Canceled, null, null))
                }
                incomingOffers.remove(message.fileId)
                queuedIncomingAccepts.remove(message.fileId)
                abortIncomingIfIdentity(message.transferId, message.fileId)
                onLog("transfer: canceled ${message.reason ?: ""}")
                acceptNextQueuedIncoming()
            }
        }
    }

    private fun handleTextMessage(message: TransferControlMessage.TextMessage) {
        // S1 already rejects empty and oversize lines when decoding.
        onTextMessage(ChatMessageState(id = message.messageId, outgoing = false, text = message.text, atMs = nowMs()))
    }

    private fun handleMessageRecall(message: TransferControlMessage.MessageRecallMessage) {
        val target = RecallTarget.fromWire(message.target) ?: run {
            onLog("chat: ignored unknown recall target")
            return
        }
        if (target == RecallTarget.FILE) forgetFile(message.targetId)
        onRecalled(Recall(target, message.targetId))
    }

    private fun handleFileOffer(message: TransferControlMessage.FileOfferMessage) {
        val offer = message.offer
        val reason = when {
            message.fileId != offer.fileId || message.transferId != offer.transferId -> "Offer identity mismatch."
            else -> validateFileOffer(offer, limits)
        }
        if (reason != null) {
            sendControl(
                TransferControlMessage.FileRejectMessage(
                    messageId = "msg_${java.util.UUID.randomUUID()}",
                    transferId = offer.transferId,
                    fileId = offer.fileId,
                    reason = reason,
                ),
            )
            onLog("transfer: rejected offer ${offer.name}: $reason")
            return
        }
        incomingOffers[offer.fileId] = offer
        onPeerTransfer(offer.peerCard(TransferCardStatus.ReadyToAccept, null, TransferCardAction.Accept))
        onLog("transfer: incoming offer ${offer.name} (${offer.size} bytes)")
    }

    private fun handleFileAccept(message: TransferControlMessage.FileAcceptMessage) {
        val offer = sender.pendingOfferFor(message.fileId)
        if (offer == null || offer.transferId != message.transferId || activeSendingFileId != null || incoming != null) {
            onLog("transfer: ignored unexpected file accept")
            return
        }
        activeSendingFileId = message.fileId
        // The streaming loop leaves the caller thread (the main dispatcher):
        // it would otherwise block the UI for the whole transfer and could not
        // suspend while the SCTP queue drains between chunks. The dispatcher
        // comes from the injected scope.
        sendScope.launch {
            var bytesSent = 0L
            var lastProgress: Int? = null
            val result = sender.sendAcceptedFile(
                message,
                awaitBufferCapacity = { awaitBufferCapacity() },
                // Per-iteration abort, as the web sender checks (`session.ts:370`): a recall,
                // cancel or retry clears activeSendingFileId or the pending offer, and the loop
                // must stop instead of streaming chunks the peer no longer expects.
                shouldContinue = {
                    !closed &&
                        activeSendingFileId == message.fileId &&
                        sender.pendingOfferFor(message.fileId)?.transferId == message.transferId
                },
            ) { frame ->
                val sent = sendFrame(frame)
                if (sent && frame is TransferOutboundFrame.Binary) {
                    bytesSent += frame.bytes.size
                    val progress = ((bytesSent * 100) / offer.size.coerceAtLeast(1)).toInt().coerceIn(0, 99)
                    if (progress != lastProgress) {
                        onLocalTransfer(offer.localCard(TransferCardStatus.Transferring, progress, null))
                        lastProgress = progress
                    }
                }
                sent
            }
            activeSendingFileId = null
            val current = offer
            if (result == TransferSendResult.SENT) {
                onLocalTransfer(current.localCard(TransferCardStatus.SentToWeb, 100, TransferCardAction.SendAgain))
                onLog("transfer: sent accepted file")
            } else if (result == TransferSendResult.INTERRUPTED) {
                // The chat entry already turned into a recall note; no card update and no retry.
                onLog("transfer: send stopped for recalled file")
            } else {
                onLocalTransfer(current.localCard("${TransferCardStatus.ErrorPrefix}send_failed", null, TransferCardAction.Retry))
                onLog("transfer: frame send failed")
            }
        }
    }

    /**
     * Holds binary frames back while the SCTP queue sits above the high
     * watermark, mirroring the web sender's waitForBuffer (8 MiB high, 2 MiB
     * low). Without this the native send() starts returning false once the
     * queue fills (~16 MiB), so files larger than that died mid-transfer and
     * the receiver aborted with an unexpected chunk offset. The deadline turns
     * a dead peer into a failed card instead of an eternal wait.
     */
    private suspend fun awaitBufferCapacity(): Boolean {
        if (!channel.isOpen || channel.bufferedAmount <= MAX_BUFFERED_AMOUNT) return channel.isOpen
        val deadline = System.currentTimeMillis() + BUFFER_DRAIN_TIMEOUT_MS
        while (channel.isOpen && channel.bufferedAmount > LOW_BUFFERED_AMOUNT) {
            if (System.currentTimeMillis() >= deadline) {
                onLog("transfer: send buffer stalled at ${channel.bufferedAmount} bytes")
                return false
            }
            delay(BUFFER_POLL_INTERVAL_MS)
        }
        return channel.isOpen
    }

    private fun handleChunkMeta(message: TransferControlMessage.ChunkMetaMessage) {
        val state = incoming ?: run {
            onLog("transfer: unexpected chunk metadata")
            return
        }
        val reason = validateChunkMeta(message, state)
        if (reason != null) {
            onLog("transfer: invalid chunk metadata: $reason")
            failIncoming(state, reason)
            return
        }
        state.expectedChunk = message
    }

    /**
     * The deserializer rejects a malformed digest before the typed message exists,
     * so an active transfer would otherwise just ignore that line. Enforce the same
     * hex rule here: a bad digest aborts through the chunk-failure path.
     */
    private fun rejectMalformedChunkDigest(text: String) {
        val state = incoming ?: return
        val fields = runCatching { TransferJson.fields(text) }.getOrNull() ?: return
        if (fields["type"] != "chunk_meta") return
        val digest = fields["sha256"] ?: return
        if (isSha256Hex(digest)) return
        onLog("transfer: invalid chunk metadata: chunk checksum invalid")
        failIncoming(state, "chunk checksum invalid")
    }

    fun handleBinary(bytes: ByteArray) {
        val state = incoming ?: run {
            onLog("transfer: unexpected binary payload")
            return
        }
        val expected = state.expectedChunk ?: run {
            onLog("transfer: unexpected binary payload")
            return
        }
        if (bytes.size != expected.size) {
            onLog("transfer: chunk size mismatch")
            failIncoming(state, "chunk size mismatch")
            return
        }
        // Verify before writing: a corrupted chunk must never reach the sink.
        expected.sha256?.let { digest ->
            if (sha256Hex(bytes) != digest) {
                onLog("transfer: chunk checksum mismatch")
                failIncoming(state, "chunk checksum mismatch")
                return
            }
        }
        runCatching { state.sink.write(bytes) }.onFailure {
            onLog("transfer: chunk write failed ${it.message}")
            failIncoming(state, "chunk write failed")
            return
        }
        state.receivedBytes += bytes.size
        state.nextIndex += 1
        state.expectedChunk = null
        val progress = ((state.receivedBytes * 100) / state.offer.size.coerceAtLeast(1)).toInt().coerceIn(0, 100)
        onPeerTransfer(state.offer.peerCard(TransferCardStatus.ReceivingFromWeb, progress, null))
    }

    private fun completeIncoming(message: TransferControlMessage.FileCompleteMessage) {
        val state = incoming ?: run {
            onLog("transfer: unexpected completion")
            return
        }
        if (message.fileId != state.offer.fileId || message.transferId != state.offer.transferId || state.expectedChunk != null ||
            state.receivedBytes != message.bytesSent || state.receivedBytes != state.offer.size || state.nextIndex != state.offer.totalChunks
        ) {
            onLog("transfer: completion validation failed")
            failIncoming(state, "completion validation failed")
            return
        }
        val payload = runCatching { state.sink.finish() }.getOrElse {
            onLog("transfer: receive finalize failed ${it.message}")
            failIncoming(state, "receive finalize failed")
            return
        }
        incoming = null
        completedIncomingFileIds += message.fileId
        onReceivedFile(payload)
        onPeerTransfer(state.offer.peerCard(TransferCardStatus.SavedOnAndroid, 100, null))
        onLog("transfer: received complete ${state.offer.name}")
        acceptNextQueuedIncoming()
    }

    private fun validateChunkMeta(message: TransferControlMessage.ChunkMetaMessage, state: IncomingTransfer): String? {
        val offer = state.offer
        if (message.fileId != offer.fileId || message.transferId != offer.transferId) return "chunk identity mismatch"
        if (message.index < 0 || message.index >= offer.totalChunks || message.index != state.nextIndex) return "chunk index mismatch"
        if (message.offset < 0 || message.offset != state.receivedBytes) return "chunk offset mismatch"
        if (message.size <= 0 || message.size > offer.chunkSize) return "chunk size invalid"
        if (message.sha256 != null && !isSha256Hex(message.sha256)) return "chunk checksum invalid"
        if (message.offset + message.size > offer.size) return "chunk exceeds file size"
        val last = message.index == offer.totalChunks - 1
        if (!last && message.size != offer.chunkSize) return "intermediate chunk size mismatch"
        if (last && message.offset + message.size != offer.size) return "final chunk size mismatch"
        return null
    }

    private fun abortIncoming(fileId: String) {
        incoming?.takeIf { it.offer.fileId == fileId }?.let {
            it.sink.abort()
            incoming = null
        }
    }

    /**
     * Marks a receiving card as failed once its transfer can no longer continue, so
     * the bubble shows the in-stream failure line (design frame 14) instead of resting
     * at its last percentage.
     */
    private fun markIncomingFailed(offer: FileOffer, reason: String) {
        onPeerTransfer(offer.peerCard("${TransferCardStatus.ErrorPrefix}$reason", null, null).copy(canCancel = false))
    }

    /**
     * Aborts a receive that cannot continue (bad size, bad digest, write failure), tells
     * the sender why through `transfer_error` when the channel is usable (web `fail()`),
     * and marks the card failed.
     */
    private fun failIncoming(state: IncomingTransfer, reason: String) {
        val owned = incoming === state
        abortIncoming(state.offer.fileId)
        if (owned) markIncomingFailed(state.offer, reason)
        if (channel.isOpen) {
            sendControl(
                TransferControlMessage.TransferErrorMessage(
                    messageId = "msg_${java.util.UUID.randomUUID()}",
                    transferId = state.offer.transferId,
                    fileId = state.offer.fileId,
                    reason = reason,
                ),
            )
        }
    }

    private fun abortIncomingIfIdentity(transferId: String, fileId: String) {
        if (incoming?.offer?.transferId == transferId && incoming?.offer?.fileId == fileId) abortIncoming(fileId)
    }

    private fun acceptNextQueuedIncoming() {
        if (incoming != null || activeSendingFileId != null) return
        while (queuedIncomingAccepts.isNotEmpty()) {
            val id = queuedIncomingAccepts.removeAt(0)
            val offer = incomingOffers[id] ?: continue
            acceptIncomingOffer(offer)
            return
        }
    }

    /**
     * Drop every local trace of a file whose chat entry was recalled (web
     * `forgetFile`): the active stream is aborted so in-flight bytes stop.
     */
    private fun forgetFile(fileId: String) {
        val offer = outgoingFiles[fileId]?.offer ?: incomingOffers[fileId]
        if (offer == null) {
            releaseHandle(fileId)
            return
        }
        if (activeSendingFileId == fileId) activeSendingFileId = null
        if (incoming?.offer?.fileId == fileId) abortIncoming(fileId)
        queuedIncomingAccepts.remove(fileId)
        terminalTransferIds += offer.transferId
        releaseHandle(fileId)
        incomingOffers.remove(fileId)
        completedIncomingFileIds.remove(fileId)
        if (activeSendingFileId == null) acceptNextQueuedIncoming()
    }

    /** Drops a pending outgoing file and its content handle without any card or wire message. */
    private fun releaseHandle(fileId: String) {
        outgoingFiles.remove(fileId)?.handle?.cleanup()
        sender.discard(fileId)
    }

    private fun sendControl(message: TransferControlMessage): Boolean {
        if (!channel.isOpen) {
            onLog("transfer: channel not open ${message.type}")
            return false
        }
        return channel.sendText(TransferJson.encode(message))
    }

    private fun sendFrame(frame: TransferOutboundFrame): Boolean = when (frame) {
        is TransferOutboundFrame.Control -> sendControl(frame.message)
        is TransferOutboundFrame.Binary -> if (channel.isOpen) channel.sendBinary(frame.bytes) else false
    }

    /**
     * A card as the conversation first draws it. `atMs` is stamped here, on the card that
     * joins the stream, and later status updates only restamp the same entry through
     * [applyChatTransferCardUpdate]. Leaving it at the `TransferCardState` default of 0
     * sorted every file above the whole conversation, so a file that had just arrived
     * appeared at the top instead of at the end.
     */
    private fun FileOffer.localCard(status: String, progress: Int?, action: String?): TransferCardState = TransferCardState(
        fileId = fileId,
        title = name,
        detail = "$size bytes · $totalChunks chunks",
        status = status,
        progressPercent = progress,
        actionLabel = action,
        canCancel = true,
        canRetry = status.startsWith(TransferCardStatus.RejectedPrefix) || status.startsWith(TransferCardStatus.ErrorPrefix) || status == TransferCardStatus.Canceled,
        sizeBytes = size,
        totalChunks = totalChunks,
        atMs = nowMs(),
    )

    private fun FileOffer.peerCard(status: String, progress: Int?, action: String?): TransferCardState = TransferCardState(
        fileId = fileId,
        title = name,
        detail = "$size bytes · $totalChunks chunks",
        status = status,
        progressPercent = progress,
        actionLabel = action,
        canCancel = true,
        canRetry = false,
        sizeBytes = size,
        totalChunks = totalChunks,
        atMs = nowMs(),
    )
}

// SCTP send-buffer watermarks for the file sender, mirroring the web sender's
// waitForBuffer thresholds.
private const val MAX_BUFFERED_AMOUNT = 8L * 1024 * 1024
private const val LOW_BUFFERED_AMOUNT = 2L * 1024 * 1024
private const val BUFFER_DRAIN_TIMEOUT_MS = 30_000L
private const val BUFFER_POLL_INTERVAL_MS = 20L

/** Decode reason for an empty or oversize chat line (S1 `TransferJson.decode`). */
internal const val INVALID_TEXT_MESSAGE_REASON = "Invalid text message."
