package com.lanshare

import kotlinx.coroutines.runBlocking
import java.io.InputStream

interface TransferContentHandle {
    val name: String
    val size: Long
    val mime: String
    fun openStream(): InputStream
    fun cleanup() = Unit
}

data class ByteArrayTransferContentHandle(
    override val name: String,
    private val bytes: ByteArray,
    override val mime: String = "application/octet-stream",
) : TransferContentHandle {
    override val size: Long = bytes.size.toLong()
    override fun openStream(): InputStream = bytes.inputStream()
}

sealed interface TransferOutboundFrame {
    data class Control(val message: TransferControlMessage) : TransferOutboundFrame
    data class Binary(val bytes: ByteArray) : TransferOutboundFrame
}

enum class TransferSendResult {
    SENT,
    NOT_FOUND,
    INVALID_IDENTITY,
    ALREADY_TERMINAL,
    SEND_FAILED,
    /** The transfer was recalled or cancelled mid-stream; remaining chunks must not be sent. */
    INTERRUPTED,
}

class TransferSender(
    private val captureFramesForTests: Boolean = true,
) {
    data class PendingFile(val offer: FileOffer, val handle: TransferContentHandle)

    // These collections are intentionally opt-in for JVM protocol tests only. Production uses the
    // callback-based sender below and retains at most one chunk at a time.
    val sentControlMessages = mutableListOf<TransferControlMessage>()
    val sentBinaryPayloads = mutableListOf<ByteArray>()
    val sentFrames = mutableListOf<TransferOutboundFrame>()
    var offer: FileOffer? = null
        private set

    private val pendingFiles = mutableMapOf<String, PendingFile>()
    private val terminalFileIds = mutableSetOf<String>()

    fun offerGeneratedFile(
        name: String,
        bytes: ByteArray,
        mime: String = "application/octet-stream",
        chunkSize: Int = DEFAULT_TRANSFER_CHUNK_SIZE,
    ): FileOffer = offerFile(ByteArrayTransferContentHandle(name = name, bytes = bytes, mime = mime), chunkSize)

    fun offerFile(handle: TransferContentHandle, chunkSize: Int = DEFAULT_TRANSFER_CHUNK_SIZE): FileOffer {
        val newOffer = createFileOffer(name = handle.name, size = handle.size, mime = handle.mime, chunkSize = chunkSize)
        offer = newOffer
        pendingFiles[newOffer.fileId] = PendingFile(newOffer, handle)
        terminalFileIds.remove(newOffer.fileId)
        appendControl(TransferControlMessage.FileOfferMessage.create(newOffer))
        return newOffer
    }

    fun pendingOfferFor(fileId: String): FileOffer? = pendingFiles[fileId]?.offer

    fun offerFor(fileId: String): FileOffer? = pendingFiles[fileId]?.offer ?: offer?.takeIf { it.fileId == fileId }

    fun discard(fileId: String) {
        pendingFiles.remove(fileId)
        terminalFileIds += fileId
        if (offer?.fileId == fileId) offer = null
    }

    fun cleanup() {
        pendingFiles.values.forEach { it.handle.cleanup() }
        pendingFiles.clear()
        terminalFileIds.clear()
        offer = null
    }

    /**
     * Streams one control/binary pair at a time. The callback must return false when the channel
     * cannot accept more data; no future frames are buffered by this class. [awaitBufferCapacity]
     * runs before every binary frame (mirroring the web sender's waitForBuffer) so the SCTP queue
     * can drain; returning false aborts with [TransferSendResult.SEND_FAILED]. Without it the
     * native send() starts returning false once the ~16 MiB queue fills, so larger files failed
     * mid-transfer and the receiver aborted with an unexpected chunk offset. [shouldContinue] is
     * re-read at every iteration, mirroring the web sender's `activeSendingFileId !== fileId`
     * check, so a recall or cancel in the middle of a file stops the stream promptly.
     */
    suspend fun sendAcceptedFile(
        message: TransferControlMessage.FileAcceptMessage,
        awaitBufferCapacity: suspend () -> Boolean = { true },
        shouldContinue: () -> Boolean = { true },
        send: (TransferOutboundFrame) -> Boolean,
    ): TransferSendResult {
        val pending = pendingFiles[message.fileId] ?: return TransferSendResult.NOT_FOUND
        val localOffer = pending.offer
        if (localOffer.transferId != message.transferId || localOffer.fileId != message.fileId) {
            return TransferSendResult.INVALID_IDENTITY
        }
        if (message.fileId in terminalFileIds) return TransferSendResult.ALREADY_TERMINAL

        return runCatching {
            var offset = 0L
            var index = 0
            pending.handle.openStream().use { input ->
                val buffer = ByteArray(localOffer.chunkSize)
                while (true) {
                    if (!shouldContinue()) return@runCatching TransferSendResult.INTERRUPTED
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    if (bytesRead == 0) continue
                    val chunk = buffer.copyOf(bytesRead)
                    // Digest exactly the bytes sent for this chunk, so the receiver can verify it.
                    val meta = chunkMeta(localOffer, index, offset, bytesRead, sha256Hex(chunk))
                    // Second check, as the web does after awaiting: the recall may have landed meanwhile.
                    if (!shouldContinue()) return@runCatching TransferSendResult.INTERRUPTED
                    if (!send(TransferOutboundFrame.Control(meta)) || !awaitBufferCapacity()) {
                        return@runCatching TransferSendResult.SEND_FAILED
                    }
                    if (!shouldContinue()) return@runCatching TransferSendResult.INTERRUPTED
                    if (!send(TransferOutboundFrame.Binary(chunk))) {
                        return@runCatching TransferSendResult.SEND_FAILED
                    }
                    offset += bytesRead
                    index += 1
                }
            }
            if (!shouldContinue()) return@runCatching TransferSendResult.INTERRUPTED
            if (offset != localOffer.size || index != localOffer.totalChunks) return@runCatching TransferSendResult.SEND_FAILED
            if (!send(TransferOutboundFrame.Control(fileComplete(localOffer, offset)))) {
                return@runCatching TransferSendResult.SEND_FAILED
            }
            terminalFileIds += localOffer.fileId
            pendingFiles.remove(localOffer.fileId)
            pending.handle.cleanup()
            TransferSendResult.SENT
        }.getOrElse { TransferSendResult.SEND_FAILED }
    }

    /** Compatibility helper retained for protocol unit tests. */
    fun handle(message: TransferControlMessage) {
        if (message !is TransferControlMessage.FileAcceptMessage) {
            when (message) {
                is TransferControlMessage.FileRejectMessage,
                is TransferControlMessage.TransferCancelMessage,
                is TransferControlMessage.TransferErrorMessage -> discard(message.fileId)
                // Chat messages carry no transfer identity, so they cannot end a transfer.
                is TransferControlMessage.TextMessage,
                is TransferControlMessage.MessageRecallMessage,
                is TransferControlMessage.FileOfferMessage,
                is TransferControlMessage.ChunkMetaMessage,
                is TransferControlMessage.FileCompleteMessage -> Unit
            }
            return
        }
        runBlocking {
            sendAcceptedFile(message) { frame ->
                if (!captureFramesForTests) return@sendAcceptedFile true
                when (frame) {
                    is TransferOutboundFrame.Control -> appendControl(frame.message)
                    is TransferOutboundFrame.Binary -> appendBinary(frame.bytes)
                }
                true
            }
        }
    }

    private fun appendControl(message: TransferControlMessage) {
        if (!captureFramesForTests) return
        sentControlMessages += message
        sentFrames += TransferOutboundFrame.Control(message)
    }

    private fun appendBinary(bytes: ByteArray) {
        if (!captureFramesForTests) return
        sentBinaryPayloads += bytes
        sentFrames += TransferOutboundFrame.Binary(bytes)
    }
}
