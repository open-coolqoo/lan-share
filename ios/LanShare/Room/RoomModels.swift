import Foundation

public struct RoomApiError: Error, Equatable, Sendable {
    public let code: String
    public let message: String
    public let status: Int?

    public init(code: String, message: String, status: Int?) {
        self.code = code
        self.message = message
        self.status = status
    }
}

public struct ClientCapabilities: Codable, Equatable, Sendable {
    public let webrtc: Bool
    public let fileSystemAccess: Bool
    public let maxChunkSize: Int
    public let maxReadableFileSize: Int

    public init(webrtc: Bool, fileSystemAccess: Bool, maxChunkSize: Int, maxReadableFileSize: Int) {
        self.webrtc = webrtc
        self.fileSystemAccess = fileSystemAccess
        self.maxChunkSize = maxChunkSize
        self.maxReadableFileSize = maxReadableFileSize
    }

    public static let standard = ClientCapabilities(
        webrtc: true,
        fileSystemAccess: true,
        maxChunkSize: 1_048_576,
        maxReadableFileSize: 2_147_483_648,
    )
}


public struct TransferLimits: Codable, Equatable, Sendable {
    public let maxFileSize: Int
    public let maxActiveTransfers: Int
    public let chunkSize: Int
}

public struct JoinedRoom: Codable, Equatable, Sendable {
    public let roomId: String
    public let deviceId: String
    public let role: String
    public let peerCount: Int
    public let sessionToken: String
    public let pairingExpiresAt: String
    public let sessionExpiresAt: String?
    public let limits: TransferLimits
}

public struct CreatedRoom: Codable, Equatable, Sendable {
    public let code: String
    public let roomId: String
    public let deviceId: String
    public let role: String
    public let peerCount: Int
    public let sessionToken: String
    public let pairingExpiresAt: String
    public let sessionExpiresAt: String?
    public let limits: TransferLimits

    public func joined() -> JoinedRoom {
        JoinedRoom(
            roomId: roomId,
            deviceId: deviceId,
            role: role,
            peerCount: peerCount,
            sessionToken: sessionToken,
            pairingExpiresAt: pairingExpiresAt,
            sessionExpiresAt: sessionExpiresAt,
            limits: limits,
        )
    }
}

public struct RoomHeartbeatResponse: Codable, Equatable, Sendable {
    public let ok: Bool
    public let lastSeenAt: String
    public let sessionExpiresAt: String?
}

public struct RoomPeer: Codable, Equatable, Sendable {
    public let deviceId: String
    public let deviceLabel: String
    public let deviceType: String
    public let lastSeenAt: String
}

public struct RoomStatusResponse: Codable, Equatable, Sendable {
    public let roomId: String
    public let peerCount: Int
    public let paired: Bool
    public let pairingExpiresAt: String
    public let sessionExpiresAt: String?
    public let peers: [RoomPeer]
}
