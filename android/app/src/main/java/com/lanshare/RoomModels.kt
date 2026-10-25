package com.lanshare

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClientCapabilities(
    val webrtc: Boolean,
    val fileSystemAccess: Boolean,
    val maxChunkSize: Long,
    val maxReadableFileSize: Long,
)

@Serializable
data class RoomJoinRequest(
    val roomKey: String = "",
    val deviceId: String,
    val deviceLabel: String,
    val deviceType: String,
    val capabilities: ClientCapabilities,
)

@Serializable
data class RoomCloseRequest(
    val deviceId: String,
    val sessionToken: String,
)

@Serializable
data class TransferLimits(
    @SerialName("maxFileSize") val maxFileSize: Long,
    @SerialName("maxActiveTransfers") val maxActiveTransfers: Int,
    @SerialName("chunkSize") val chunkSize: Int,
)

@Serializable
data class JoinedRoom(
    val roomId: String,
    val deviceId: String,
    val role: String,
    val peerCount: Int,
    val sessionToken: String,
    val pairingExpiresAt: String,
    val sessionExpiresAt: String? = null,
    val limits: TransferLimits,
)

@Serializable
data class CreatedRoom(
    val code: String,
    val roomId: String,
    val deviceId: String,
    val role: String,
    val peerCount: Int,
    val sessionToken: String,
    val pairingExpiresAt: String,
    val sessionExpiresAt: String? = null,
    val limits: TransferLimits,
) {
    fun joined(): JoinedRoom = JoinedRoom(
        roomId = roomId,
        deviceId = deviceId,
        role = role,
        peerCount = peerCount,
        sessionToken = sessionToken,
        pairingExpiresAt = pairingExpiresAt,
        sessionExpiresAt = sessionExpiresAt,
        limits = limits,
    )
}

@Serializable
data class RoomHeartbeatResponse(
    val ok: Boolean,
    val lastSeenAt: String,
    val sessionExpiresAt: String? = null,
)

fun isCurrentRoomSessionRequest(roomId: String, sessionToken: String, activeRoom: JoinedRoom?): Boolean =
    activeRoom?.roomId == roomId && activeRoom.sessionToken == sessionToken

fun defaultAndroidCapabilities(): ClientCapabilities = ClientCapabilities(
    webrtc = true,
    fileSystemAccess = true,
    maxChunkSize = 1_048_576,
    maxReadableFileSize = 2_147_483_648,
)
