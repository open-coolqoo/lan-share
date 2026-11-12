package com.lanshare

import com.lanshare.lan.LanJoinTarget

/**
 * The room lifecycle seam. The LAN implementation owns the local listener,
 * the browser bridge and the TCP signaling socket.
 */
internal interface RoomService {
    suspend fun create(deviceId: String, deviceLabel: String): CreatedRoom
    suspend fun join(target: LanJoinTarget, deviceId: String, deviceLabel: String): JoinedRoom
    suspend fun heartbeat(room: JoinedRoom): RoomHeartbeatResponse
    suspend fun close(room: JoinedRoom)
    fun signalingLink(log: (String) -> Unit): SignalingLink
}

/** Mirrors the event surface of a signaling transport. */
internal interface SignalingLink {
    fun connect(room: JoinedRoom, onEvent: (SignalingEnvelope) -> Unit, onDisconnect: (String) -> Unit)
    fun send(message: SignalingEnvelope)
    fun close()
}
