import Foundation

public struct RoomApiError: Error, Equatable {
    public let code: String
    public let message: String
    public let status: Int?
}

/// Room lifecycle client against the private LanShare backend interface.
/// Session credentials travel in JSON bodies only, never in URLs.
public final class RoomApiClient: Sendable {
    public static let defaultOrigin = "https://lanshare.example"

    /// Debug builds honor a launch-environment override (scheme → Environment Variables
    /// `LANSHARE_ORIGIN=http://192.168.1.10:5180`) so a simulator run can target the local
    /// stack. Release builds always use `defaultOrigin`; an absent or empty variable changes nothing.
    public static var resolvedDefaultOrigin: String {
        #if DEBUG
        if let override = ProcessInfo.processInfo.environment["LANSHARE_ORIGIN"] {
            let trimmed = override.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { return trimmed }
        }
        #endif
        return defaultOrigin
    }

    private let base: String
    private let session: URLSession
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    public init(origin: String, timeout: TimeInterval = 10) {
        base = origin.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = timeout
        configuration.timeoutIntervalForResource = timeout
        session = URLSession(configuration: configuration)
    }

    /// Converts an HTTP(S) origin to the signaling WebSocket URL.
    public static func signalingURL(for origin: String) -> String {
        let clean = origin.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if clean.hasPrefix("https://") {
            return "wss://\(clean.dropFirst("https://".count))\(PrivateEndpoints.signaling)"
        }
        if clean.hasPrefix("http://") {
            return "ws://\(clean.dropFirst("http://".count))\(PrivateEndpoints.signaling)"
        }
        return "ws://\(clean)\(PrivateEndpoints.signaling)"
    }

    public func create(deviceId: String, deviceLabel: String) async throws -> CreatedRoom {
        try await post(
            PrivateEndpoints.roomCreate,
            body: RoomJoinRequest(
                deviceId: deviceId,
                deviceLabel: deviceLabel,
                deviceType: "mobile",
                capabilities: .standard,
            ),
            fallbackMessage: "Connection failed.",
        )
    }

    public func join(roomKey: String, deviceId: String, deviceLabel: String) async throws -> JoinedRoom {
        try await post(
            PrivateEndpoints.roomJoin,
            body: RoomJoinRequest(
                roomKey: roomKey,
                deviceId: deviceId,
                deviceLabel: deviceLabel,
                deviceType: "mobile",
                capabilities: .standard,
            ),
            fallbackMessage: "Connection failed.",
        )
    }

    public func heartbeat(_ room: JoinedRoom) async throws -> RoomHeartbeatResponse {
        try await post(
            PrivateEndpoints.roomHeartbeat(room.roomId),
            body: RoomCloseRequest(deviceId: room.deviceId, sessionToken: room.sessionToken),
            fallbackMessage: "Connection failed.",
        )
    }

    public func status(_ room: JoinedRoom) async throws -> RoomStatusResponse {
        try await post(
            PrivateEndpoints.roomStatus(room.roomId),
            body: RoomCloseRequest(deviceId: room.deviceId, sessionToken: room.sessionToken),
            fallbackMessage: "Connection failed.",
        )
    }

    public func close(_ room: JoinedRoom) async throws {
        let _: [String: Bool] = try await post(
            PrivateEndpoints.roomClose(room.roomId),
            body: RoomCloseRequest(deviceId: room.deviceId, sessionToken: room.sessionToken),
            fallbackMessage: "Close failed.",
        )
    }

    private func post<Body: Encodable, Response: Decodable>(
        _ path: String,
        body: Body,
        fallbackMessage: String,
    ) async throws -> Response {
        guard let url = URL(string: base + path) else {
            throw RoomApiError(code: "invalid_origin", message: "Invalid server origin.", status: nil)
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(body)
        let (data, urlResponse) = try await session.data(for: request)
        let statusCode = (urlResponse as? HTTPURLResponse)?.statusCode ?? 0
        guard (200 ..< 300).contains(statusCode) else {
            throw roomApiError(data: data, status: statusCode, fallbackMessage: fallbackMessage)
        }
        do {
            return try decoder.decode(Response.self, from: data)
        } catch {
            throw RoomApiError(
                code: "invalid_response",
                message: "Unexpected server response.",
                status: statusCode,
            )
        }
    }

    private func roomApiError(data: Data, status: Int, fallbackMessage: String) -> RoomApiError {
        struct ApiErrorBody: Decodable {
            let code: String?
            let message: String?
        }
        let body = try? JSONDecoder().decode(ApiErrorBody.self, from: data)
        let code = body?.code?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let message = body?.message?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return RoomApiError(
            code: code.isEmpty ? "http_\(status)" : code,
            message: message.isEmpty ? fallbackMessage : message,
            status: status,
        )
    }
}


// The room/signaling service is not part of this public repository. These
// placeholders must be replaced by the private deployment; they deliberately do
// not match the production interface.
enum PrivateEndpoints {
    static let roomCreate = "/__private__/rooms/create"
    static let roomJoin = "/__private__/rooms/join"
    static let signaling = "/__private__/signaling"

    static func roomStatus(_ roomId: String) -> String {
        "/__private__/rooms/\(roomId)/status"
    }

    static func roomHeartbeat(_ roomId: String) -> String {
        "/__private__/rooms/\(roomId)/heartbeat"
    }

    static func roomClose(_ roomId: String) -> String {
        "/__private__/rooms/\(roomId)/close"
    }
}
