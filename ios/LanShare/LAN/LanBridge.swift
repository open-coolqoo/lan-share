import Foundation

/// The browser guest's signaling transport, attached to a host room.
protocol LanBridgeTransport: AnyObject, Sendable {
    func send(_ envelope: SignalingEnvelope)
    func close()
}

struct LanBridgeRoomInfo: Sendable {
    let code: String
    let hostDeviceId: String
    let hostDeviceLabel: String
    let roomId: String
    let pairingExpiresAt: String
    let sessionExpiresAt: String
    let limits: TransferLimits
}

struct LanBridgeWelcome: Sendable {
    let token: String
    let roomId: String
    let hostDeviceId: String
    let hostDeviceLabel: String
    let pairingExpiresAt: String
    let sessionExpiresAt: String
    let limits: TransferLimits
}
