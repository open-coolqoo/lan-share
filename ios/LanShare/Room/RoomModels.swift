import Foundation

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

public struct RoomJoinRequest: Codable, Sendable {
    public var roomKey: String?
    public let deviceId: String
    public let deviceLabel: String
    public let deviceType: String
    public let capabilities: ClientCapabilities

    public init(
        roomKey: String? = nil,
        deviceId: String,
        deviceLabel: String,
        deviceType: String,
        capabilities: ClientCapabilities
    ) {
        self.roomKey = roomKey
        self.deviceId = deviceId
        self.deviceLabel = deviceLabel
        self.deviceType = deviceType
        self.capabilities = capabilities
    }

    private enum CodingKeys: String, CodingKey {
        case roomKey, deviceId, deviceLabel, deviceType, capabilities
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(roomKey, forKey: .roomKey)
        try container.encode(deviceId, forKey: .deviceId)
        try container.encode(deviceLabel, forKey: .deviceLabel)
        try container.encode(deviceType, forKey: .deviceType)
        try container.encode(capabilities, forKey: .capabilities)
    }
}

public struct RoomCloseRequest: Codable, Equatable, Sendable {
    public let deviceId: String
    public let sessionToken: String

    public init(deviceId: String, sessionToken: String) {
        self.deviceId = deviceId
        self.sessionToken = sessionToken
    }
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
