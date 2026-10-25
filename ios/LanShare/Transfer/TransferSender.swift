import Foundation

/// Send/receive surface the transfer session needs from a DataChannel.
/// `ChannelAdapter` is the production implementation; tests inject fakes.
public protocol TransferChannel: AnyObject, Sendable {
    var isOpen: Bool { get }

    /// Bytes queued in the native SCTP send buffer, mirroring the web
    /// `bufferedAmount`; the sender holds binary frames back while it sits
    /// above the high watermark.
    var bufferedAmount: Int { get }
    func sendText(_ text: String) -> Bool
    func sendBinary(_ bytes: Data) -> Bool
}

/// A readable file source for an outgoing transfer. Confined to the main
/// actor (the transfer session's actor); reads are sequential.
public protocol TransferContentHandle: AnyObject {
    var name: String { get }
    var size: Int { get }
    var mime: String { get }
    func readChunk(at offset: Int, maxLength: Int) -> Data?
    func cleanup()
}

public final class MemoryContentHandle: TransferContentHandle {
    public let name: String
    public let mime: String
    private let data: Data

    public init(name: String, data: Data, mime: String = "application/octet-stream") {
        self.name = name
        self.data = data
        self.mime = mime
    }

    public var size: Int { data.count }

    public func readChunk(at offset: Int, maxLength: Int) -> Data? {
        guard offset < data.count, maxLength > 0 else { return nil }
        let end = min(offset + maxLength, data.count)
        return data.subdata(in: offset ..< end)
    }

    public func cleanup() {}
}

/// Reads a file from disk in chunks without loading it fully into memory.
public final class FileContentHandle: TransferContentHandle {
    public let name: String
    public let mime: String
    public let url: URL
    public let size: Int
    private var handle: FileHandle?

    public init(url: URL, name: String? = nil, mime: String = "application/octet-stream") throws {
        self.url = url
        self.name = name ?? url.lastPathComponent
        self.mime = mime
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        let fileSize = attributes[.size] as? NSNumber
        size = fileSize?.intValue ?? 0
    }

    public func readChunk(at offset: Int, maxLength: Int) -> Data? {
        guard maxLength > 0, offset < size else { return nil }
        if handle == nil {
            handle = try? FileHandle(forReadingFrom: url)
        }
        guard let handle else { return nil }
        do {
            try handle.seek(toOffset: UInt64(offset))
            let data = try handle.read(upToCount: maxLength)
            if let data, !data.isEmpty { return data }
            return nil
        } catch {
            return nil
        }
    }

    public func cleanup() {
        try? handle?.close()
        handle = nil
    }
}

public enum TransferOutboundFrame: Sendable {
    case control(TransferControlMessage)
    case binary(Data)
}

public enum TransferSendResult: Equatable {
    case sent
    case notFound
    case invalidIdentity
    case alreadyTerminal
    case sendFailed
    /// The transfer was recalled or cancelled mid-stream; the remaining chunks
    /// (and `file_complete`) must not be sent. Distinct from `sendFailed` so the
    /// session emits no error card for a file the user retracted.
    case interrupted
}

/// Streams one control/binary pair at a time; no future frames are buffered.
public final class TransferSender {
    private struct PendingFile {
        let offer: FileOffer
        let handle: TransferContentHandle
    }

    private var pendingFiles: [String: PendingFile] = [:]
    private var terminalFileIds: Set<String> = []

    public init() {}

    public func offerFile(_ handle: TransferContentHandle, chunkSize: Int = defaultTransferChunkSize) -> FileOffer {
        let offer = createFileOffer(name: handle.name, size: handle.size, mime: handle.mime, chunkSize: chunkSize)
        pendingFiles[offer.fileId] = PendingFile(offer: offer, handle: handle)
        terminalFileIds.remove(offer.fileId)
        return offer
    }

    public func pendingOffer(for fileId: String) -> FileOffer? {
        pendingFiles[fileId]?.offer
    }

    public func discard(_ fileId: String) {
        pendingFiles[fileId] = nil
        terminalFileIds.insert(fileId)
    }

    public func cleanup() {
        pendingFiles.values.forEach { $0.handle.cleanup() }
        pendingFiles.removeAll()
        terminalFileIds.removeAll()
    }

    /// Streams one control/binary pair at a time. The callback must return
    /// false when the channel cannot accept more data; no future frames are
    /// buffered by this class. The callback is async so the session can hold
    /// binary frames back while the SCTP queue drains (see the web sender's
    /// waitForBuffer): without that, native sends start failing once the
    /// ~16 MiB queue fills and larger files die mid-transfer.
    ///
    /// `shouldContinue` is polled before each chunk and again after hashing,
    /// before anything is sent (the web sender's per-iteration abort checks at
    /// `session.ts:370-382`): a recall, cancel or retry landing mid-stream
    /// stops the loop instead of streaming chunks the peer no longer expects.
    public func sendAcceptedFile(
        _ message: FileAcceptMessage,
        shouldContinue: () -> Bool = { true },
        send: (TransferOutboundFrame) async -> Bool,
    ) async -> TransferSendResult {
        guard let pending = pendingFiles[message.fileId] else { return .notFound }
        let offer = pending.offer
        if offer.transferId != message.transferId || offer.fileId != message.fileId {
            return .invalidIdentity
        }
        if terminalFileIds.contains(message.fileId) { return .alreadyTerminal }

        var offset = 0
        var index = 0
        while offset < offer.size {
            if !shouldContinue() { return .interrupted }
            guard let chunk = pending.handle.readChunk(at: offset, maxLength: offer.chunkSize),
                  !chunk.isEmpty
            else { return .sendFailed }
            // Digest of exactly the bytes sent for this chunk, so the receiver
            // can reject a corrupted chunk before it reaches disk.
            let meta = chunkMeta(offer, index: index, offset: offset, size: chunk.count, sha256: sha256Hex(chunk))
            // Second check after hashing: the recall may have landed meanwhile.
            if !shouldContinue() { return .interrupted }
            if !(await send(.control(.chunkMeta(meta)))) { return .sendFailed }
            if !shouldContinue() { return .interrupted }
            if !(await send(.binary(chunk))) { return .sendFailed }
            offset += chunk.count
            index += 1
        }
        if offset != offer.size || index != offer.totalChunks { return .sendFailed }
        if !shouldContinue() { return .interrupted }
        if !(await send(.control(.fileComplete(fileComplete(offer, bytesSent: offset))))) {
            return .sendFailed
        }
        terminalFileIds.insert(offer.fileId)
        pendingFiles[message.fileId] = nil
        pending.handle.cleanup()
        return .sent
    }
}
