import Foundation

/// Minimal JSON value used for the opaque signaling `payload` field.
public enum JSONValue: Equatable, Sendable {
    case null
    case bool(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])
}

extension JSONValue: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Int.self) {
            self = .int(value)
        } else if let value = try? container.decode(Double.self) {
            self = .double(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else if let value = try? container.decode([String: JSONValue].self) {
            self = .object(value)
        } else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Unsupported JSON value.",
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let value): try container.encode(value)
        case .int(let value): try container.encode(value)
        case .double(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        }
    }
}

public struct SignalingEnvelope: Codable, Equatable, Sendable {
    public let type: String
    public let roomId: String?
    public let from: String?
    public let to: String?
    public let deviceId: String?
    public let sessionToken: String?
    public let payload: JSONValue?
    public let error: String?

    public init(
        type: String,
        roomId: String? = nil,
        from: String? = nil,
        to: String? = nil,
        deviceId: String? = nil,
        sessionToken: String? = nil,
        payload: JSONValue? = nil,
        error: String? = nil,
    ) {
        self.type = type
        self.roomId = roomId
        self.from = from
        self.to = to
        self.deviceId = deviceId
        self.sessionToken = sessionToken
        self.payload = payload
        self.error = error
    }

    public static func auth(roomId: String, deviceId: String, sessionToken: String) -> SignalingEnvelope {
        SignalingEnvelope(
            type: "auth",
            roomId: roomId,
            deviceId: deviceId,
            sessionToken: sessionToken,
        )
    }
}

public enum SignalingJson {
    private static let encoder = JSONEncoder()
    private static let decoder = JSONDecoder()

    public static func encode(_ envelope: SignalingEnvelope) throws -> String {
        let data = try encoder.encode(envelope)
        return String(decoding: data, as: UTF8.self)
    }

    public static func decode(_ text: String) throws -> SignalingEnvelope {
        guard let data = text.data(using: .utf8) else {
            throw SignalingError.invalidMessage
        }
        return try decoder.decode(SignalingEnvelope.self, from: data)
    }
}

public enum SignalingError: Error {
    case invalidMessage
}

/// Wraps any `Encodable` payload into a `JSONValue` (object) for signaling envelopes.
public func signalingPayload(_ value: some Encodable) -> JSONValue? {
    guard let data = try? JSONEncoder().encode(value) else { return nil }
    return try? JSONDecoder().decode(JSONValue.self, from: data)
}

/// Decodes a `JSONValue` payload back into a concrete `Decodable` type.
public func decodedPayload<T: Decodable>(_ payload: JSONValue?, as type: T.Type) throws -> T {
    guard let payload else { throw SignalingError.invalidMessage }
    let data = try JSONEncoder().encode(payload)
    return try JSONDecoder().decode(T.self, from: data)
}
