package com.lanshare.lan

import com.lanshare.SignalingEnvelope
import com.lanshare.TransferLimits

/** The browser guest's signaling transport, attached to a host room. */
internal interface LanBridgeTransport {
    fun send(envelope: SignalingEnvelope)
    fun close()
}

internal data class LanBridgeRoomInfo(
    val code: String,
    val hostDeviceId: String,
    val hostDeviceLabel: String,
    val roomId: String,
    val pairingExpiresAt: String,
    val sessionExpiresAt: String,
    val limits: TransferLimits,
)

internal data class LanBridgeWelcome(
    val token: String,
    val roomId: String,
    val hostDeviceId: String,
    val hostDeviceLabel: String,
    val pairingExpiresAt: String,
    val sessionExpiresAt: String,
    val limits: TransferLimits,
)
