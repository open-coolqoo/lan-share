import Foundation

public let lanProtocolVersion = 1
public let lanMaxFrameBytes = 256 * 1024

/// One frame on the LAN signaling connection. Every field except `t` is
/// optional so one Codable shape covers hello/challenge/auth/welcome/signal/
/// ping/close without a polymorphic decoder.
public struct LanMessage: Codable, Equatable, Sendable {
    public var t: String
    public var v: Int?
    public var deviceId: String?
    public var deviceLabel: String?
    public var deviceType: String?
    public var capabilities: ClientCapabilities?
    public var nonce: String?
    public var proof: String?
    public var roomId: String?
    public var role: String?
    public var sessionToken: String?
    public var pairingExpiresAt: String?
    public var sessionExpiresAt: String?
    public var limits: TransferLimits?
    public var code: String?
    public var message: String?
    public var n: Int?
    public var e: SignalingEnvelope?

    public init(
        t: String,
        v: Int? = nil,
        deviceId: String? = nil,
        deviceLabel: String? = nil,
        deviceType: String? = nil,
        capabilities: ClientCapabilities? = nil,
        nonce: String? = nil,
        proof: String? = nil,
        roomId: String? = nil,
        role: String? = nil,
        sessionToken: String? = nil,
        pairingExpiresAt: String? = nil,
        sessionExpiresAt: String? = nil,
        limits: TransferLimits? = nil,
        code: String? = nil,
        message: String? = nil,
        n: Int? = nil,
        e: SignalingEnvelope? = nil,
    ) {
        self.t = t
        self.v = v
        self.deviceId = deviceId
        self.deviceLabel = deviceLabel
        self.deviceType = deviceType
        self.capabilities = capabilities
        self.nonce = nonce
        self.proof = proof
        self.roomId = roomId
        self.role = role
        self.sessionToken = sessionToken
        self.pairingExpiresAt = pairingExpiresAt
        self.sessionExpiresAt = sessionExpiresAt
        self.limits = limits
        self.code = code
        self.message = message
        self.n = n
        self.e = e
    }
}

public enum LanCodec {
    public static func encode(_ message: LanMessage) throws -> Data {
        try JSONEncoder().encode(message)
    }

    public static func decode(_ data: Data) throws -> LanMessage {
        try JSONDecoder().decode(LanMessage.self, from: data)
    }
}

public enum LanFramingError: Error, Equatable {
    case invalidLength
    case invalidPayload
}

public enum LanFraming {
    public static func frame(_ payload: Data) -> Data {
        var length = UInt32(payload.count).bigEndian
        var frame = Data(bytes: &length, count: 4)
        frame.append(payload)
        return frame
    }

    /// Pulls every complete frame out of `buffer`, leaving a partial frame in
    /// place. Throws when a length prefix is outside the protocol bounds.
    public static func payloads(from buffer: inout Data) throws -> [Data] {
        var payloads: [Data] = []
        while buffer.count >= 4 {
            let bytes = [UInt8](buffer.prefix(4))
            let length = (UInt32(bytes[0]) << 24) | (UInt32(bytes[1]) << 16) |
                (UInt32(bytes[2]) << 8) | UInt32(bytes[3])
            guard length > 0, length <= UInt32(lanMaxFrameBytes) else {
                throw LanFramingError.invalidLength
            }
            guard buffer.count >= 4 + Int(length) else { break }
            let payload = Data(buffer.dropFirst(4).prefix(Int(length)))
            buffer.removeFirst(4 + Int(length))
            payloads.append(payload)
        }
        return payloads
    }
}
