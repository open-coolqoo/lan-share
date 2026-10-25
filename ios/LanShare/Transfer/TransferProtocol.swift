import CryptoKit
import Foundation

public struct FileOffer: Codable, Equatable, Sendable {
    public let fileId: String
    public let transferId: String
    public let name: String
    public let size: Int
    public let mime: String
    public let chunkSize: Int
    public let totalChunks: Int
}

public struct FileOfferMessage: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let messageId: String
    public let transferId: String
    public let fileId: String
    public let type: String
    public let offer: FileOffer
}

public struct FileAcceptMessage: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let messageId: String
    public let transferId: String
    public let fileId: String
    public let type: String
}

public struct FileRejectMessage: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let messageId: String
    public let transferId: String
    public let fileId: String
    public let type: String
    public let reason: String
}

public struct ChunkMetaMessage: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let messageId: String
    public let transferId: String
    public let fileId: String
    public let type: String
    public let index: Int
    public let offset: Int
    public let size: Int
    public let sha256: String?
}

public struct FileCompleteMessage: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let messageId: String
    public let transferId: String
    public let fileId: String
    public let type: String
    public let bytesSent: Int
}

public struct TransferErrorMessage: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let messageId: String
    public let transferId: String
    public let fileId: String
    public let type: String
    public let reason: String
}

public struct TransferCancelMessage: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let messageId: String
    public let transferId: String
    public let fileId: String
    public let type: String
    public let reason: String?

    private enum CodingKeys: String, CodingKey {
        case protocolVersion, messageId, transferId, fileId, type, reason
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(protocolVersion, forKey: .protocolVersion)
        try container.encode(messageId, forKey: .messageId)
        try container.encode(transferId, forKey: .transferId)
        try container.encode(fileId, forKey: .fileId)
        try container.encode(type, forKey: .type)
        try container.encodeIfPresent(reason, forKey: .reason)
    }
}

/// Chat text; exactly the four web fields (`protocol.ts:55-60`), no transfer identity.
public struct TextMessage: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let messageId: String
    public let type: String
    public let text: String
}

/// Recall of a chat text or a shared file; no transfer identity (`protocol.ts:62-68`).
public struct MessageRecallMessage: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let messageId: String
    public let type: String
    public let target: String
    public let targetId: String
}

/// Wire values match the web client.
public enum RecallTarget: String, Sendable {
    case text
    case file
}

/// In-band text cap. Larger drafts are sent as a .txt file instead.
public let maxTextBytes = 64 * 1024
public let maxRecallTargetIdLength = 128

/// UTF-8 byte length, matching the web `TextEncoder` / Android `toByteArray`.
public func utf8ByteLength(_ text: String) -> Int { text.utf8.count }

public func isTextTooLarge(_ text: String) -> Bool { utf8ByteLength(text) > maxTextBytes }

public func isSha256Hex(_ value: String) -> Bool {
    value.utf8.count == 64 && value.utf8.allSatisfy { byte in
        (byte >= UInt8(ascii: "0") && byte <= UInt8(ascii: "9"))
            || (byte >= UInt8(ascii: "a") && byte <= UInt8(ascii: "f"))
    }
}

/// Lowercase hex SHA-256, matching the web `sha256Hex` and Android `sha256Hex`.
public func sha256Hex(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}

public enum TransferControlMessage: Equatable, Sendable {
    case fileOffer(FileOfferMessage)
    case fileAccept(FileAcceptMessage)
    case fileReject(FileRejectMessage)
    case chunkMeta(ChunkMetaMessage)
    case fileComplete(FileCompleteMessage)
    case transferError(TransferErrorMessage)
    case transferCancel(TransferCancelMessage)
    case textMessage(TextMessage)
    case messageRecall(MessageRecallMessage)

    /// Chat messages carry no transfer identity: they are not tied to a file transfer.
    public var fileId: String? {
        switch self {
        case .fileOffer(let m): return m.fileId
        case .fileAccept(let m): return m.fileId
        case .fileReject(let m): return m.fileId
        case .chunkMeta(let m): return m.fileId
        case .fileComplete(let m): return m.fileId
        case .transferError(let m): return m.fileId
        case .transferCancel(let m): return m.fileId
        case .textMessage, .messageRecall: return nil
        }
    }

    public var transferId: String? {
        switch self {
        case .fileOffer(let m): return m.transferId
        case .fileAccept(let m): return m.transferId
        case .fileReject(let m): return m.transferId
        case .chunkMeta(let m): return m.transferId
        case .fileComplete(let m): return m.transferId
        case .transferError(let m): return m.transferId
        case .transferCancel(let m): return m.transferId
        case .textMessage, .messageRecall: return nil
        }
    }

    public var messageId: String {
        switch self {
        case .fileOffer(let m): return m.messageId
        case .fileAccept(let m): return m.messageId
        case .fileReject(let m): return m.messageId
        case .chunkMeta(let m): return m.messageId
        case .fileComplete(let m): return m.messageId
        case .transferError(let m): return m.messageId
        case .transferCancel(let m): return m.messageId
        case .textMessage(let m): return m.messageId
        case .messageRecall(let m): return m.messageId
        }
    }
}

public enum TransferProtocolError: Error, Equatable {
    case unsupportedVersion
    case unsupportedType(String)
    case invalidMessage
}

public enum TransferJson {
    private static let encoder = JSONEncoder()
    private static let decoder = JSONDecoder()
    public static let protocolVersion = 1

    public static func encode(_ message: TransferControlMessage) throws -> String {
        let payload: Codable
        switch message {
        case .fileOffer(let m): payload = m
        case .fileAccept(let m): payload = m
        case .fileReject(let m): payload = m
        case .chunkMeta(let m): payload = m
        case .fileComplete(let m): payload = m
        case .transferError(let m): payload = m
        case .transferCancel(let m): payload = m
        case .textMessage(let m): payload = m
        case .messageRecall(let m): payload = m
        }
        let data = try encoder.encode(payload)
        return String(decoding: data, as: UTF8.self)
    }

    /// Parses a control frame, mirroring the web `parseControlMessage`.
    ///
    /// Returns `nil` for an unknown type: the peer may already speak a newer optional message
    /// type, and one unknown line must not kill a file transfer. Invalid input for a known type,
    /// or an unsupported protocol version, throws.
    public static func decode(_ text: String) throws -> TransferControlMessage? {
        guard let data = text.data(using: .utf8) else {
            throw TransferProtocolError.invalidMessage
        }
        guard let any = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw TransferProtocolError.invalidMessage
        }
        let version = any["protocolVersion"] as? Int
        guard version == TransferJson.protocolVersion else {
            throw TransferProtocolError.unsupportedVersion
        }
        guard let type = any["type"] as? String else {
            throw TransferProtocolError.invalidMessage
        }
        switch type {
        case "file_offer": return .fileOffer(try decoder.decode(FileOfferMessage.self, from: data))
        case "file_accept": return .fileAccept(try decoder.decode(FileAcceptMessage.self, from: data))
        case "file_reject": return .fileReject(try decoder.decode(FileRejectMessage.self, from: data))
        case "chunk_meta":
            if let raw = any["sha256"], !(raw is NSNull) {
                guard let sha256 = raw as? String, isSha256Hex(sha256) else {
                    throw TransferProtocolError.invalidMessage
                }
            }
            return .chunkMeta(try decoder.decode(ChunkMetaMessage.self, from: data))
        case "file_complete": return .fileComplete(try decoder.decode(FileCompleteMessage.self, from: data))
        case "transfer_error": return .transferError(try decoder.decode(TransferErrorMessage.self, from: data))
        case "transfer_cancel": return .transferCancel(try decoder.decode(TransferCancelMessage.self, from: data))
        case "text_message":
            guard any["messageId"] is String else {
                throw TransferProtocolError.invalidMessage
            }
            guard let body = any["text"] as? String, !body.isEmpty, !isTextTooLarge(body) else {
                throw TransferProtocolError.invalidMessage
            }
            return .textMessage(try decoder.decode(TextMessage.self, from: data))
        case "message_recall":
            guard any["messageId"] is String else {
                throw TransferProtocolError.invalidMessage
            }
            guard let target = any["target"] as? String, RecallTarget(rawValue: target) != nil else {
                throw TransferProtocolError.invalidMessage
            }
            guard let targetId = any["targetId"] as? String,
                  !targetId.isEmpty,
                  targetId.utf16.count <= maxRecallTargetIdLength else {
                throw TransferProtocolError.invalidMessage
            }
            return .messageRecall(try decoder.decode(MessageRecallMessage.self, from: data))
        default: return nil
        }
    }
}

public let defaultTransferChunkSize = 262_144
public let defaultTransferMaxFileSize = 2_147_483_648

public func defaultTransferLimits() -> TransferLimits {
    TransferLimits(
        maxFileSize: defaultTransferMaxFileSize,
        maxActiveTransfers: 1,
        chunkSize: defaultTransferChunkSize,
    )
}

/// Mirrors the Web/Android validation: identity, size limit, chunk-size limit,
/// and the `totalChunks` invariant `max(1, ceil(size / chunkSize))`.
public func validateFileOffer(_ offer: FileOffer, limits: TransferLimits) -> String? {
    if offer.fileId.isEmpty || offer.transferId.isEmpty { return "Missing transfer identity." }
    if offer.size < 0 { return "Negative file size." }
    if offer.size > limits.maxFileSize { return "File exceeds the connection limit." }
    if offer.chunkSize <= 0 || offer.chunkSize > limits.chunkSize { return "Invalid chunk size." }
    let expectedChunks = expectedTotalChunks(size: offer.size, chunkSize: offer.chunkSize)
    if expectedChunks > Int.max || offer.totalChunks != expectedChunks {
        return "File chunk metadata is inconsistent."
    }
    if offer.totalChunks <= 0 { return "Invalid total chunk count." }
    return nil
}

public func expectedTotalChunks(size: Int, chunkSize: Int) -> Int {
    if size == 0 { return 1 }
    return size / chunkSize + (size % chunkSize == 0 ? 0 : 1)
}

public func createFileOffer(
    name: String,
    size: Int,
    mime: String = "application/octet-stream",
    chunkSize: Int = defaultTransferChunkSize,
) -> FileOffer {
    precondition(size >= 0, "File size cannot be negative.")
    precondition(chunkSize > 0, "Chunk size must be positive.")
    let safeName = sanitizeFileName(name)
    return FileOffer(
        fileId: "file_\(UUID().uuidString)",
        transferId: "transfer_\(UUID().uuidString)",
        name: safeName,
        size: size,
        mime: mime,
        chunkSize: chunkSize,
        totalChunks: expectedTotalChunks(size: size, chunkSize: chunkSize),
    )
}

public func sanitizeFileName(_ name: String) -> String {
    let invalid = CharacterSet(charactersIn: "\\/:*?\"<>|")
    var cleaned = name
        .components(separatedBy: invalid).joined(separator: "_")
    while cleaned.contains("..") {
        cleaned = cleaned.replacingOccurrences(of: "..", with: ".")
    }
    cleaned = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
    return cleaned.isEmpty ? "unnamed-file" : cleaned
}

public func chunkMeta(
    _ offer: FileOffer,
    index: Int,
    offset: Int,
    size: Int,
    sha256: String? = nil,
) -> ChunkMetaMessage {
    ChunkMetaMessage(
        protocolVersion: TransferJson.protocolVersion,
        messageId: "msg_\(UUID().uuidString)",
        transferId: offer.transferId,
        fileId: offer.fileId,
        type: "chunk_meta",
        index: index,
        offset: offset,
        size: size,
        sha256: sha256,
    )
}

public func fileComplete(_ offer: FileOffer, bytesSent: Int) -> FileCompleteMessage {
    FileCompleteMessage(
        protocolVersion: TransferJson.protocolVersion,
        messageId: "msg_\(UUID().uuidString)",
        transferId: offer.transferId,
        fileId: offer.fileId,
        type: "file_complete",
        bytesSent: bytesSent,
    )
}

public func nextMessageId() -> String { "msg_\(UUID().uuidString)" }

public func createTextMessage(_ text: String) -> TextMessage {
    TextMessage(
        protocolVersion: TransferJson.protocolVersion,
        messageId: nextMessageId(),
        type: "text_message",
        text: text,
    )
}

public func createRecallMessage(target: RecallTarget, targetId: String) -> MessageRecallMessage {
    MessageRecallMessage(
        protocolVersion: TransferJson.protocolVersion,
        messageId: nextMessageId(),
        type: "message_recall",
        target: target.rawValue,
        targetId: targetId,
    )
}
