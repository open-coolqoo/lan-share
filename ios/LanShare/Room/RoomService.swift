import Foundation

/// The room lifecycle seam. The LAN implementation owns the local listener,
/// the browser bridge and the TCP signaling socket.
public protocol RoomService: Sendable {
    func create(deviceId: String, deviceLabel: String) async throws -> CreatedRoom
    func join(target: LanJoinTarget, deviceId: String, deviceLabel: String) async throws -> JoinedRoom
    func heartbeat(_ room: JoinedRoom) async throws -> RoomHeartbeatResponse
    func close(_ room: JoinedRoom) async throws
    func signalingLink(log: @escaping @Sendable (String) -> Void) -> any SignalingLink
}

/// Mirrors the event surface of a signaling transport.
public protocol SignalingLink: AnyObject, Sendable {
    func connect(
        room: JoinedRoom,
        onEvent: @escaping @Sendable (SignalingEnvelope) -> Void,
        onDisconnect: @escaping @Sendable (String) -> Void,
    )
    func send(_ envelope: SignalingEnvelope)
    func close()
}
