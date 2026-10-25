package com.lanshare

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SignalingEnvelope(
    val type: String,
    val roomId: String? = null,
    val from: String? = null,
    val to: String? = null,
    val deviceId: String? = null,
    val sessionToken: String? = null,
    val payload: JsonElement? = null,
    val error: String? = null,
) {
    companion object {
        fun auth(roomId: String, deviceId: String, sessionToken: String): SignalingEnvelope = SignalingEnvelope(
            type = "auth",
            roomId = roomId,
            deviceId = deviceId,
            sessionToken = sessionToken,
        )
    }
}
