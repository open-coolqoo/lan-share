import Foundation

public enum TransferCardStatus {
    public static let waitingForAcceptance = "Waiting for Web acceptance."
    public static let transferring = "Transferring."
    public static let sentToWeb = "Sent to Web."
    public static let receivingFromWeb = "Receiving from Web."
    public static let savedOnIOS = "Saved on iOS."
    public static let readyToAccept = "Ready to accept."
    public static let canceled = "Canceled."
    public static let rejectedPrefix = "Rejected: "
    public static let errorPrefix = "Error: "
}

public enum TransferCardAction {
    public static let accept = "Accept"
    public static let retry = "Retry"
    public static let sendAgain = "Send again"
}

public struct TransferCardState: Equatable, Sendable {
    public let fileId: String
    public var title: String
    public var detail: String
    public var status: String
    public var progressPercent: Int?
    public var actionLabel: String?
    public var canCancel: Bool
    public var canRetry: Bool
    public let sizeBytes: Int
    public let totalChunks: Int
    /// When the entry entered the conversation, so a status update never moves
    /// it. 0 for cards built outside the chat path (protocol tests).
    public var atMs: Int64
    /// The chat entry was retracted; it stays in place as a recall note.
    public var recalled: Bool

    public init(
        fileId: String,
        title: String,
        detail: String,
        status: String,
        progressPercent: Int?,
        actionLabel: String?,
        canCancel: Bool,
        canRetry: Bool,
        sizeBytes: Int,
        totalChunks: Int,
        atMs: Int64 = 0,
        recalled: Bool = false,
    ) {
        self.fileId = fileId
        self.title = title
        self.detail = detail
        self.status = status
        self.progressPercent = progressPercent
        self.actionLabel = actionLabel
        self.canCancel = canCancel
        self.canRetry = canRetry
        self.sizeBytes = sizeBytes
        self.totalChunks = totalChunks
        self.atMs = atMs
        self.recalled = recalled
    }

    /// A copy with the conversation fields replaced (the upsert keeps the
    /// existing card's values, mirroring Android's `upsertChatCard`).
    public func withConversation(atMs: Int64, recalled: Bool) -> TransferCardState {
        var copy = self
        copy.atMs = atMs
        copy.recalled = recalled
        return copy
    }

    /// A copy marked as recalled in place.
    public func recalledCopy() -> TransferCardState {
        withConversation(atMs: atMs, recalled: true)
    }
}

public struct ReceivedFileInfo: Equatable, Sendable {
    public let fileId: String
    public let name: String
    public let size: Int
    public let url: URL
}

/// Card-list rules mirroring Android's `applyTransferCardUpdate`: updates
/// upsert by file id into the sending or receiving list, and a canceled
/// transfer is dropped from both lists rather than lingering on screen as a
/// canceled card. The conversation stream uses `applyKeepingCanceled` instead
/// (design O3).
public enum TransferCards {
    public static func apply(
        _ card: TransferCardState,
        isLocal: Bool,
        local: [TransferCardState],
        peer: [TransferCardState],
    ) -> (local: [TransferCardState], peer: [TransferCardState]) {
        guard card.status != TransferCardStatus.canceled else {
            return (
                local.filter { $0.fileId != card.fileId },
                peer.filter { $0.fileId != card.fileId },
            )
        }
        var local = local
        var peer = peer
        if isLocal {
            upsert(card, into: &local)
        } else {
            upsert(card, into: &peer)
        }
        return (local, peer)
    }

    /// A status update must not move an entry in the conversation: the
    /// existing card keeps its `atMs` and `recalled`, mirroring the web, where
    /// status changes only mutate the item already in the list.
    private static func upsert(_ card: TransferCardState, into cards: inout [TransferCardState]) {
        if let index = cards.firstIndex(where: { $0.fileId == card.fileId }) {
            let existing = cards[index]
            cards[index] = card.withConversation(atMs: existing.atMs, recalled: existing.recalled)
        } else {
            cards.append(card)
        }
    }

    /// The card-list rule for the conversation stream (design O3): a canceled
    /// file bubble stays in place and shows 已取消 instead of vanishing. The
    /// web drops the row; the chat keeps it, so the user can see what was
    /// cancelled. A card the list does not hold yet is not added: cancelling
    /// must never conjure an entry.
    public static func applyKeepingCanceled(
        _ card: TransferCardState,
        isLocal: Bool,
        local: [TransferCardState],
        peer: [TransferCardState],
    ) -> (local: [TransferCardState], peer: [TransferCardState]) {
        guard card.status == TransferCardStatus.canceled else {
            return apply(card, isLocal: isLocal, local: local, peer: peer)
        }
        var local = local
        var peer = peer
        guard let index = (isLocal ? local : peer).firstIndex(where: { $0.fileId == card.fileId }) else {
            return (local, peer)
        }
        let existing = isLocal ? local[index] : peer[index]
        let kept = card.withConversation(atMs: existing.atMs, recalled: existing.recalled)
        if isLocal {
            local[index] = kept
        } else {
            peer[index] = kept
        }
        return (local, peer)
    }
}

/// Streams received chunks into a sandbox temp file and finalizes into the
/// app-controlled "Received" folder only after byte counts match.
final class IncomingFileSink {
    private let offer: FileOffer
    private let receivedDirectory: URL
    private let partURL: URL
    private let fileHandle: FileHandle?
    private var closed = false

    init(offer: FileOffer, receivedDirectory: URL) throws {
        self.offer = offer
        self.receivedDirectory = receivedDirectory
        try FileManager.default.createDirectory(at: receivedDirectory, withIntermediateDirectories: true)
        let temp = FileManager.default.temporaryDirectory
        partURL = temp.appendingPathComponent("incoming-\(UUID().uuidString).part")
        FileManager.default.createFile(atPath: partURL.path, contents: nil)
        fileHandle = try FileHandle(forWritingTo: partURL)
    }

    func write(_ bytes: Data) throws {
        guard !closed else { throw PublicContentError(message: "Incoming sink is closed.") }
        try fileHandle?.write(contentsOf: bytes)
    }

    /// Moves the completed part file to its sanitized destination name.
    func finish() throws -> URL {
        try fileHandle?.close()
        closed = true
        let destination = Self.uniqueDestination(for: sanitizeFileName(offer.name), in: receivedDirectory)
        if FileManager.default.fileExists(atPath: destination.path) {
            try FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.moveItem(at: partURL, to: destination)
        return destination
    }

    func abort() {
        guard !closed else { return }
        try? fileHandle?.close()
        closed = true
        try? FileManager.default.removeItem(at: partURL)
    }

    private static func uniqueDestination(for fileName: String, in directory: URL) -> URL {
        let dotIndex = fileName.lastIndex(of: ".")
        let baseName = dotIndex.map { String(fileName[..<$0]) } ?? fileName
        let ext = dotIndex.map { String(fileName[$0...]) } ?? ""
        var candidate = directory.appendingPathComponent(fileName)
        var index = 1
        while FileManager.default.fileExists(atPath: candidate.path) {
            candidate = directory.appendingPathComponent("\(baseName) (\(index))\(ext)")
            index += 1
        }
        return candidate
    }
}

/// Transfer protocol v1 state machine. Mirrors the Android reference:
/// one active byte transfer per session, queued remote accepts, strict chunk
/// validation, terminal cancel/reject/error handling. Confined to the main actor.
@MainActor
public final class TransferSession {
    private struct IncomingTransfer {
        let offer: FileOffer
        let sink: IncomingFileSink
        var nextIndex = 0
        var receivedBytes = 0
        var expectedChunk: ChunkMetaMessage?
    }

    private struct OutgoingFile {
        let offer: FileOffer
        let handle: TransferContentHandle
        let atMs: Int64
    }

    public let channel: TransferChannel
    public var onLog: (String) -> Void = { _ in }
    public var onReceivedFile: ((ReceivedFileInfo) -> Void)?
    public var onLocalTransfer: (TransferCardState?) -> Void = { _ in }
    public var onPeerTransfer: (TransferCardState?) -> Void = { _ in }
    /// A chat line sent or received, reported as an ordered entry (web
    /// `onTextMessage`, Android `onTextMessage`).
    public var onTextMessage: ((ChatMessageState) -> Void) = { _ in }
    /// A retraction of a chat text or a shared file (web `onRecalled`).
    public var onRecalled: ((TransferRecall) -> Void) = { _ in }
    public var nowMs: () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }

    private let limits: TransferLimits
    private let receivedDirectory: URL
    private let sender = TransferSender()
    // SCTP send-buffer watermarks for the file sender, mirroring the web
    // sender's waitForBuffer thresholds.
    private static let maxBufferedAmount = 8 * 1024 * 1024
    private static let lowBufferedAmount = 2 * 1024 * 1024
    private static let bufferDrainTimeoutSeconds: TimeInterval = 30
    private var outgoingFiles: [String: OutgoingFile] = [:]
    private var incomingOffers: [String: FileOffer] = [:]
    private var queuedIncomingAccepts: [String] = []
    private var completedIncomingFileIds: Set<String> = []
    private var terminalTransferIds: Set<String> = []
    private var incoming: IncomingTransfer?
    private var activeSendingFileId: String?
    /// Files whose chat entry was recalled: no further frames, no card, and
    /// `retry` must not bring them back (Android `forgottenFileIds`).
    private var forgottenFileIds: Set<String> = []
    private var closed = false

    public init(
        channel: TransferChannel,
        limits: TransferLimits,
        receivedDirectory: URL,
    ) {
        self.channel = channel
        self.limits = limits
        self.receivedDirectory = receivedDirectory
    }

    public func cleanup() {
        closed = true
        incoming?.sink.abort()
        incoming = nil
        sender.cleanup()
        outgoingFiles.removeAll()
        incomingOffers.removeAll()
        queuedIncomingAccepts.removeAll()
        completedIncomingFileIds.removeAll()
        terminalTransferIds.removeAll()
        activeSendingFileId = nil
        forgottenFileIds.removeAll()
    }

    // MARK: Outgoing

    public func offerFile(_ handle: TransferContentHandle) {
        if closed {
            handle.cleanup()
            return
        }
        if handle.size == 0 {
            handle.cleanup()
            onLog("transfer: empty files are not supported (\(handle.name))")
            onLocalTransfer(errorCard(name: handle.name, fileId: "file_\(UUID().uuidString)", reason: "empty_file"))
            return
        }
        if handle.size > limits.maxFileSize {
            handle.cleanup()
            onLog("transfer: offer rejected \(handle.name): File exceeds the connection limit.")
            return
        }
        let offer = sender.offerFile(handle, chunkSize: limits.chunkSize)
        let atMs = nowMs()
        outgoingFiles[offer.fileId] = OutgoingFile(offer: offer, handle: handle, atMs: atMs)
        if !sendControl(.fileOffer(FileOfferMessage(
            protocolVersion: TransferJson.protocolVersion,
            messageId: nextMessageId(),
            transferId: offer.transferId,
            fileId: offer.fileId,
            type: "file_offer",
            offer: offer,
        ))) {
            outgoingFiles.removeValue(forKey: offer.fileId)
            sender.discard(offer.fileId)
            handle.cleanup()
            onLog("transfer: offer failed \(offer.name)")
            return
        }
        onLocalTransfer(localCard(
            offer,
            status: TransferCardStatus.waitingForAcceptance,
            progress: 0,
            action: TransferCardAction.retry,
            atMs: atMs,
        ))
        onLog("transfer: offered \(offer.name) (\(offer.size) bytes)")
    }

    // MARK: Incoming

    public func acceptIncoming(fileId: String? = nil) {
        let offer: FileOffer?
        if let fileId {
            offer = incomingOffers[fileId]
        } else {
            offer = incomingOffers.values.first {
                $0.fileId != incoming?.offer.fileId && !completedIncomingFileIds.contains($0.fileId)
            }
        }
        guard let offer else { return }
        acceptIncomingOffer(offer)
    }

    public func acceptAllIncoming() {
        for fileId in incomingOffers.keys {
            if !completedIncomingFileIds.contains(fileId), let offer = incomingOffers[fileId] {
                acceptIncomingOffer(offer)
            }
        }
    }

    private func acceptIncomingOffer(_ offer: FileOffer) {
        if completedIncomingFileIds.contains(offer.fileId)
            || incoming?.offer.fileId == offer.fileId
            || queuedIncomingAccepts.contains(offer.fileId) { return }
        if incoming != nil || activeSendingFileId != nil {
            if limits.maxActiveTransfers > 0 { queuedIncomingAccepts.append(offer.fileId) }
            return
        }
        let sink: IncomingFileSink
        do {
            sink = try IncomingFileSink(offer: offer, receivedDirectory: receivedDirectory)
        } catch {
            onLog("transfer: unable to create receive sink \(error.localizedDescription)")
            return
        }
        incoming = IncomingTransfer(offer: offer, sink: sink)
        if !sendControl(.fileAccept(FileAcceptMessage(
            protocolVersion: TransferJson.protocolVersion,
            messageId: nextMessageId(),
            transferId: offer.transferId,
            fileId: offer.fileId,
            type: "file_accept",
        ))) {
            abortIncoming(offer.fileId)
            return
        }
        onPeerTransfer(peerCard(offer, status: TransferCardStatus.receivingFromWeb, progress: 0, atMs: nowMs()))
        onLog("transfer: accepted \(offer.name)")
    }

    // MARK: Chat

    /// Sends one chat line and reports it as an outgoing entry. Refused
    /// (false, before any byte is sent) when the channel is not open or the
    /// text exceeds the wire limit; the caller owns the oversize-as-file
    /// fallback.
    @discardableResult
    public func sendText(_ text: String) -> Bool {
        guard !closed, channel.isOpen else {
            onLog("transfer: channel not open text_message")
            return false
        }
        guard !isTextTooLarge(text) else {
            onLog("chat: text too large")
            return false
        }
        let message = createTextMessage(text)
        guard sendControl(.textMessage(message)) else { return false }
        onTextMessage(ChatMessageState(
            id: message.messageId,
            outgoing: true,
            text: text,
            atMs: nowMs(),
        ))
        return true
    }

    /// Retracts a chat entry on both sides (web `recall`). A recalled file
    /// drops the pending offer/stream locally like `cancelTransfer`, but emits
    /// no canceled card and no `transfer_cancel`: the peer learns from the
    /// recall itself.
    public func recall(target: RecallTarget, targetId: String) {
        guard !closed else { return }
        // Only `forgetFile` may touch the bookkeeping: releasing the handle
        // first would remove the outgoing entry, and `forgetFile` would then
        // hit its early return and leave the stream running.
        if target == .file { forgetFile(targetId) }
        sendControl(.messageRecall(createRecallMessage(target: target, targetId: targetId)))
        onRecalled(TransferRecall(target: target, targetId: targetId))
    }

    /// Drop every local trace of a file whose chat entry was recalled (web
    /// `forgetFile`): the active stream is aborted so in-flight bytes stop and
    /// the sender loop exits through its `interrupted` result.
    private func forgetFile(_ fileId: String) {
        forgottenFileIds.insert(fileId)
        let offer = outgoingFiles[fileId]?.offer ?? incomingOffers[fileId]
        guard let offer else {
            releaseHandle(fileId)
            return
        }
        if activeSendingFileId == fileId { activeSendingFileId = nil }
        if incoming?.offer.fileId == fileId { abortIncoming(fileId) }
        queuedIncomingAccepts.removeAll { $0 == fileId }
        terminalTransferIds.insert(offer.transferId)
        releaseHandle(fileId)
        incomingOffers.removeValue(forKey: fileId)
        completedIncomingFileIds.remove(fileId)
        if activeSendingFileId == nil { acceptNextQueuedIncoming() }
        onLog("transfer: forgot recalled file \(offer.name)")
    }

    /// Drops a pending outgoing file and its content handle without any card
    /// or wire message.
    private func releaseHandle(_ fileId: String) {
        outgoingFiles.removeValue(forKey: fileId)?.handle.cleanup()
        sender.discard(fileId)
    }

    // MARK: Cancel / retry

    public func cancelTransfer(fileId: String) {
        let outgoing = outgoingFiles[fileId]?.offer
        guard let offer = outgoing ?? incomingOffers[fileId] else { return }
        sendControl(.transferCancel(TransferCancelMessage(
            protocolVersion: TransferJson.protocolVersion,
            messageId: nextMessageId(),
            transferId: offer.transferId,
            fileId: offer.fileId,
            type: "transfer_cancel",
            reason: "user_cancel",
        )))
        if let outgoing {
            onLocalTransfer(localCard(outgoing, status: TransferCardStatus.canceled, progress: nil, action: nil))
        }
        if let incomingOffer = incomingOffers[fileId] {
            onPeerTransfer(peerCard(incomingOffer, status: TransferCardStatus.canceled, progress: nil))
        }
        if let removed = outgoingFiles.removeValue(forKey: fileId) {
            removed.handle.cleanup()
        }
        incomingOffers.removeValue(forKey: fileId)
        queuedIncomingAccepts.removeAll { $0 == fileId }
        completedIncomingFileIds.remove(fileId)
        terminalTransferIds.insert(offer.transferId)
        sender.discard(fileId)
        if incoming?.offer.fileId == fileId { abortIncoming(fileId) }
        if activeSendingFileId == fileId { activeSendingFileId = nil }
        onLog("transfer: canceled \(offer.name)")
        acceptNextQueuedIncoming()
    }

    public func retry(fileId: String) {
        guard !forgottenFileIds.contains(fileId) else { return }
        guard let outgoing = outgoingFiles.removeValue(forKey: fileId) else { return }
        sender.discard(fileId)
        onLocalTransfer(localCard(
            outgoing.offer,
            status: TransferCardStatus.canceled,
            progress: nil,
            action: nil,
            canCancel: false,
            canRetry: false,
        ))
        offerFile(outgoing.handle)
    }

    // MARK: Inbound handling

    public func handleText(_ text: String) async {
        let message: TransferControlMessage?
        do {
            message = try TransferJson.decode(text)
        } catch TransferProtocolError.invalidMessage {
            // An oversize, empty or malformed chat line is a bad message, not a
            // broken transfer: drop it without failing the active file, as the
            // chat layer owns its limits. A malformed chunk digest is different:
            // it belongs to the transfer in flight and aborts it.
            if rejectMalformedChunkDigest(text) { return }
            onLog("transfer: ignored invalid control")
            return
        } catch {
            onLog("transfer: invalid control \(error)")
            return
        }
        // Unknown types are ignored, not fatal: a newer optional message type
        // must not kill a file transfer.
        switch message {
        case nil:
            onLog("transfer: ignored unknown control")
        case .textMessage(let m)?:
            // Decoding already rejects empty and oversize lines.
            onTextMessage(ChatMessageState(
                id: m.messageId,
                outgoing: false,
                text: m.text,
                atMs: nowMs(),
            ))
        case .messageRecall(let m)?:
            guard let target = RecallTarget(rawValue: m.target) else {
                onLog("chat: ignored unknown recall target")
                return
            }
            if target == .file { forgetFile(m.targetId) }
            onRecalled(TransferRecall(target: target, targetId: m.targetId))
        case .fileOffer(let m)?: handleFileOffer(m)
        case .fileAccept(let m)?: await handleFileAccept(m)
        case .chunkMeta(let m)?: handleChunkMeta(m)
        case .fileComplete(let m)?: completeIncoming(m)
        case .fileReject(let m)?:
            if sender.pendingOffer(for: m.fileId)?.transferId == m.transferId {
                if let offer = sender.pendingOffer(for: m.fileId) {
                    onLocalTransfer(localCard(
                        offer,
                        status: "\(TransferCardStatus.rejectedPrefix)\(m.reason)",
                        progress: nil,
                        action: TransferCardAction.retry,
                    ))
                }
                sender.discard(m.fileId)
            }
        case .transferError(let m)?:
            if sender.pendingOffer(for: m.fileId)?.transferId == m.transferId,
               let offer = sender.pendingOffer(for: m.fileId) {
                onLocalTransfer(localCard(
                    offer,
                    status: "\(TransferCardStatus.errorPrefix)\(m.reason)",
                    progress: nil,
                    action: TransferCardAction.retry,
                ))
            }
            abortIncomingIfIdentity(transferId: m.transferId, fileId: m.fileId)
        case .transferCancel(let m)?:
            if sender.pendingOffer(for: m.fileId)?.transferId == m.transferId {
                if let offer = sender.pendingOffer(for: m.fileId) {
                    onLocalTransfer(localCard(
                        offer,
                        status: TransferCardStatus.canceled,
                        progress: nil,
                        action: TransferCardAction.retry,
                    ))
                }
                sender.discard(m.fileId)
            }
            if let offer = incomingOffers[m.fileId], offer.transferId == m.transferId {
                onPeerTransfer(peerCard(offer, status: TransferCardStatus.canceled, progress: nil))
            }
            incomingOffers.removeValue(forKey: m.fileId)
            queuedIncomingAccepts.removeAll { $0 == m.fileId }
            abortIncomingIfIdentity(transferId: m.transferId, fileId: m.fileId)
            onLog("transfer: canceled \(m.reason ?? "")")
            acceptNextQueuedIncoming()
        }
    }

    public func handleBinary(_ bytes: Data) {
        guard var state = incoming else {
            onLog("transfer: unexpected binary payload")
            return
        }
        guard let expected = state.expectedChunk else {
            onLog("transfer: unexpected binary payload")
            return
        }
        if bytes.count != expected.size {
            onLog("transfer: chunk size mismatch")
            failIncoming(state, reason: "chunk size mismatch")
            return
        }
        // Verify before writing: a corrupted chunk must never reach the sink.
        if let digest = expected.sha256, sha256Hex(bytes) != digest {
            onLog("transfer: chunk checksum mismatch")
            failIncoming(state, reason: "chunk checksum mismatch")
            return
        }
        do {
            try state.sink.write(bytes)
        } catch {
            onLog("transfer: chunk write failed \(error.localizedDescription)")
            failIncoming(state, reason: "chunk write failed")
            return
        }
        state.receivedBytes += bytes.count
        state.nextIndex += 1
        state.expectedChunk = nil
        incoming = state
        let progress = progressPercent(received: state.receivedBytes, total: state.offer.size)
        onPeerTransfer(peerCard(state.offer, status: TransferCardStatus.receivingFromWeb, progress: progress))
    }

    private func handleFileOffer(_ message: FileOfferMessage) {
        let offer = message.offer
        var reason: String?
        if message.fileId != offer.fileId || message.transferId != offer.transferId {
            reason = "Offer identity mismatch."
        } else {
            reason = validateFileOffer(offer, limits: limits)
        }
        if offer.size == 0 { reason = "Zero-byte files are not supported." }
        if let reason {
            sendControl(.fileReject(FileRejectMessage(
                protocolVersion: TransferJson.protocolVersion,
                messageId: nextMessageId(),
                transferId: offer.transferId,
                fileId: offer.fileId,
                type: "file_reject",
                reason: reason,
            )))
            onLog("transfer: rejected offer \(offer.name): \(reason)")
            return
        }
        incomingOffers[offer.fileId] = offer
        onPeerTransfer(peerCard(
            offer,
            status: TransferCardStatus.readyToAccept,
            progress: nil,
            action: TransferCardAction.accept,
            atMs: nowMs(),
        ))
        onLog("transfer: incoming offer \(offer.name) (\(offer.size) bytes)")
    }

    private func handleFileAccept(_ message: FileAcceptMessage) async {
        let offer = sender.pendingOffer(for: message.fileId)
        if offer == nil
            || offer?.transferId != message.transferId
            || activeSendingFileId != nil
            || incoming != nil {
            onLog("transfer: ignored unexpected file accept")
            return
        }
        guard let offer else { return }
        activeSendingFileId = message.fileId
        let cardAtMs = outgoingFiles[message.fileId]?.atMs
        var bytesSent = 0
        var lastProgress: Int?
        let result = await sender.sendAcceptedFile(
            message,
            // Per-iteration abort, as the web sender checks (`session.ts:370`):
            // a recall, cancel or retry clears activeSendingFileId or the
            // pending offer, and the loop must stop instead of streaming chunks
            // the peer no longer expects.
            shouldContinue: { [weak self] in
                guard let self else { return false }
                return !self.closed
                    && self.activeSendingFileId == message.fileId
                    && self.sender.pendingOffer(for: message.fileId)?.transferId == message.transferId
            },
            send: { [weak self] (frame: TransferOutboundFrame) async -> Bool in
                guard let self else { return false }
                let sent: Bool
                switch frame {
                case .control(let control): sent = self.rawSendControl(control)
                case .binary(let bytes):
                    // Hold the frame back while the SCTP queue is above the
                    // high watermark; native sends fail outright once it fills.
                    guard await self.waitForBufferCapacity() else {
                        sent = false
                        break
                    }
                    sent = self.channel.isOpen ? self.channel.sendBinary(bytes) : false
                }
                if sent {
                    switch frame {
                    case .binary(let chunk):
                        bytesSent += chunk.count
                        let progress = (bytesSent * 100 / max(offer.size, 1))
                        if progress != lastProgress {
                            self.onLocalTransfer(self.localCard(
                                offer,
                                status: TransferCardStatus.transferring,
                                progress: min(max(progress, 0), 99),
                                action: nil,
                                atMs: cardAtMs,
                            ))
                            lastProgress = progress
                        }
                    default:
                        break
                    }
                }
                return sent
            },
        )
        activeSendingFileId = nil
        if result == .sent {
            onLocalTransfer(localCard(
                offer,
                status: TransferCardStatus.sentToWeb,
                progress: 100,
                action: TransferCardAction.sendAgain,
                atMs: cardAtMs,
            ))
            onLog("transfer: sent accepted file")
        } else if result == .interrupted {
            // The chat entry already turned into a recall note; no card update
            // and no retry, unlike a send failure.
            onLog("transfer: send stopped for recalled file")
        } else {
            onLocalTransfer(localCard(
                offer,
                status: "\(TransferCardStatus.errorPrefix)send_failed",
                progress: nil,
                action: TransferCardAction.retry,
                atMs: cardAtMs,
            ))
            onLog("transfer: frame send failed")
        }
    }

    /// Mirrors the web sender's waitForBuffer: hold binary frames back while
    /// the SCTP queue sits above the high watermark (8 MiB), resuming once it
    /// drains to the low watermark (2 MiB). Without this, native sends start
    /// failing once the queue fills (~16 MiB), so files larger than that died
    /// mid-transfer and the receiver aborted with an unexpected chunk offset.
    /// The deadline turns a dead peer into a failed card instead of an
    /// eternal wait.
    private func waitForBufferCapacity() async -> Bool {
        if !channel.isOpen || channel.bufferedAmount <= Self.maxBufferedAmount { return channel.isOpen }
        let deadline = Date().addingTimeInterval(Self.bufferDrainTimeoutSeconds)
        while channel.isOpen, channel.bufferedAmount > Self.lowBufferedAmount {
            if Date() >= deadline {
                onLog("transfer: send buffer stalled at \(channel.bufferedAmount) bytes")
                return false
            }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
        return channel.isOpen
    }

    private func handleChunkMeta(_ message: ChunkMetaMessage) {
        guard let state = incoming else {
            onLog("transfer: unexpected chunk metadata")
            return
        }
        if let reason = validateChunkMeta(message, state: state) {
            onLog("transfer: invalid chunk metadata: \(reason)")
            failIncoming(state, reason: reason)
            return
        }
        incoming?.expectedChunk = message
    }

    /// The decoder rejects a malformed digest before the typed message exists,
    /// so an active transfer would otherwise just ignore that line. Enforce
    /// the same hex rule here: a bad digest aborts through the chunk-failure
    /// path. Returns whether the line was a chunk digest, i.e. consumed.
    @discardableResult
    private func rejectMalformedChunkDigest(_ text: String) -> Bool {
        guard let state = incoming else { return false }
        guard let data = text.data(using: .utf8),
              let fields = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              fields["type"] as? String == "chunk_meta"
        else { return false }
        if let raw = fields["sha256"], !(raw is NSNull) {
            if let digest = raw as? String, isSha256Hex(digest) { return false }
            onLog("transfer: invalid chunk metadata: chunk checksum invalid")
            failIncoming(state, reason: "chunk checksum invalid")
            return true
        }
        return false
    }

    private func completeIncoming(_ message: FileCompleteMessage) {
        guard let state = incoming else {
            onLog("transfer: unexpected completion")
            return
        }
        let identityMatches = message.fileId == state.offer.fileId && message.transferId == state.offer.transferId
        if !identityMatches
            || state.expectedChunk != nil
            || state.receivedBytes != message.bytesSent
            || state.receivedBytes != state.offer.size
            || state.nextIndex != state.offer.totalChunks {
            onLog("transfer: completion validation failed")
            failIncoming(state, reason: "completion validation failed")
            return
        }
        let destination: URL
        do {
            destination = try state.sink.finish()
        } catch {
            onLog("transfer: receive finalize failed \(error.localizedDescription)")
            failIncoming(state, reason: "receive finalize failed")
            return
        }
        let offer = state.offer
        incoming = nil
        completedIncomingFileIds.insert(message.fileId)
        onReceivedFile?(ReceivedFileInfo(fileId: offer.fileId, name: offer.name, size: offer.size, url: destination))
        onPeerTransfer(peerCard(offer, status: TransferCardStatus.savedOnIOS, progress: 100))
        onLog("transfer: received complete \(offer.name)")
        acceptNextQueuedIncoming()
    }

    // MARK: Validation helpers

    private func validateChunkMeta(_ message: ChunkMetaMessage, state: IncomingTransfer) -> String? {
        let offer = state.offer
        if message.fileId != offer.fileId || message.transferId != offer.transferId { return "chunk identity mismatch" }
        if message.index < 0 || message.index >= offer.totalChunks || message.index != state.nextIndex {
            return "chunk index mismatch"
        }
        if message.offset < 0 || message.offset != state.receivedBytes { return "chunk offset mismatch" }
        if message.size <= 0 || message.size > offer.chunkSize { return "chunk size invalid" }
        if let digest = message.sha256, !isSha256Hex(digest) { return "chunk checksum invalid" }
        if message.offset + message.size > offer.size { return "chunk exceeds file size" }
        let last = message.index == offer.totalChunks - 1
        if !last && message.size != offer.chunkSize { return "intermediate chunk size mismatch" }
        if last && message.offset + message.size != offer.size { return "final chunk size mismatch" }
        return nil
    }

    private func abortIncoming(_ fileId: String) {
        if let state = incoming, state.offer.fileId == fileId {
            state.sink.abort()
            incoming = nil
        }
    }

    /// Marks a receiving card as failed once its transfer can no longer
    /// continue, so the bubble shows the in-stream failure line instead of
    /// resting at its last percentage.
    private func markIncomingFailed(_ offer: FileOffer, reason: String) {
        onPeerTransfer(peerCard(
            offer,
            status: "\(TransferCardStatus.errorPrefix)\(reason)",
            progress: nil,
            canCancel: false,
        ))
    }

    /// Aborts a receive that cannot continue (bad size, bad digest, write
    /// failure, failed completion), tells the sender why through
    /// `transfer_error` when the channel is usable (web `fail()`), and marks
    /// the card failed.
    private func failIncoming(_ state: IncomingTransfer, reason: String) {
        let owned = incoming?.offer.fileId == state.offer.fileId
        abortIncoming(state.offer.fileId)
        if owned { markIncomingFailed(state.offer, reason: reason) }
        if channel.isOpen {
            sendControl(.transferError(TransferErrorMessage(
                protocolVersion: TransferJson.protocolVersion,
                messageId: nextMessageId(),
                transferId: state.offer.transferId,
                fileId: state.offer.fileId,
                type: "transfer_error",
                reason: reason,
            )))
        }
        onLog("transfer: receive failed \(reason)")
    }

    private func abortIncomingIfIdentity(transferId: String, fileId: String) {
        if let state = incoming, state.offer.transferId == transferId, state.offer.fileId == fileId {
            abortIncoming(fileId)
        }
    }

    private func acceptNextQueuedIncoming() {
        if incoming != nil || activeSendingFileId != nil { return }
        while !queuedIncomingAccepts.isEmpty {
            let id = queuedIncomingAccepts.removeFirst()
            guard let offer = incomingOffers[id] else { continue }
            acceptIncomingOffer(offer)
            return
        }
    }

    // MARK: Channel send

    private func sendControl(_ message: TransferControlMessage) -> Bool {
        if !channel.isOpen {
            onLog("transfer: channel not open \(message.fileId ?? message.messageId)")
            return false
        }
        return rawSendControl(message)
    }

    private func rawSendControl(_ message: TransferControlMessage) -> Bool {
        guard let text = try? TransferJson.encode(message) else { return false }
        return channel.sendText(text)
    }

    private func progressPercent(received: Int, total: Int) -> Int {
        min(max(received * 100 / max(total, 1), 0), 100)
    }

    // MARK: Cards

    private func localCard(
        _ offer: FileOffer,
        status: String,
        progress: Int?,
        action: String?,
        canCancel: Bool = true,
        canRetry: Bool = false,
        atMs: Int64? = nil,
    ) -> TransferCardState {
        TransferCardState(
            fileId: offer.fileId,
            title: offer.name,
            detail: "\(offer.size) bytes · \(offer.totalChunks) chunks",
            status: status,
            progressPercent: progress,
            actionLabel: action,
            canCancel: canCancel,
            canRetry: canRetry
                || status.hasPrefix(TransferCardStatus.rejectedPrefix)
                || status.hasPrefix(TransferCardStatus.errorPrefix)
                || status == TransferCardStatus.canceled,
            sizeBytes: offer.size,
            totalChunks: offer.totalChunks,
            atMs: atMs ?? nowMs(),
        )
    }

    private func peerCard(
        _ offer: FileOffer,
        status: String,
        progress: Int?,
        action: String? = nil,
        canCancel: Bool = true,
        atMs: Int64? = nil,
    ) -> TransferCardState {
        TransferCardState(
            fileId: offer.fileId,
            title: offer.name,
            detail: "\(offer.size) bytes · \(offer.totalChunks) chunks",
            status: status,
            progressPercent: progress,
            actionLabel: action,
            canCancel: canCancel,
            canRetry: false,
            sizeBytes: offer.size,
            totalChunks: offer.totalChunks,
            atMs: atMs ?? nowMs(),
        )
    }

    private func errorCard(name: String, fileId: String, reason: String) -> TransferCardState {
        TransferCardState(
            fileId: fileId,
            title: name,
            detail: "0 bytes",
            status: "\(TransferCardStatus.errorPrefix)\(reason)",
            progressPercent: nil,
            actionLabel: nil,
            canCancel: false,
            canRetry: false,
            sizeBytes: 0,
            totalChunks: 0,
            atMs: nowMs(),
        )
    }
}
