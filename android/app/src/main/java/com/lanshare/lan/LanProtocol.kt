package com.lanshare.lan

import com.lanshare.ClientCapabilities
import com.lanshare.SignalingEnvelope
import com.lanshare.TransferLimits
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val LAN_PROTOCOL_VERSION = 1
internal const val LAN_MAX_FRAME_BYTES = 256 * 1024

/**
 * One frame on the LAN signaling connection. Every field except `t` is
 * optional so a single serializable shape covers hello/challenge/auth/
 * welcome/signal/ping/close without a polymorphic serializer.
 */
@Serializable
internal data class LanMessage(
    val t: String,
    val v: Int? = null,
    val deviceId: String? = null,
    val deviceLabel: String? = null,
    val deviceType: String? = null,
    val capabilities: ClientCapabilities? = null,
    val nonce: String? = null,
    val proof: String? = null,
    val roomId: String? = null,
    val role: String? = null,
    val sessionToken: String? = null,
    val pairingExpiresAt: String? = null,
    val sessionExpiresAt: String? = null,
    val limits: TransferLimits? = null,
    val code: String? = null,
    val message: String? = null,
    val n: Int? = null,
    val e: SignalingEnvelope? = null,
)

internal object LanCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(message: LanMessage): ByteArray =
        json.encodeToString(message).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): LanMessage? = runCatching {
        json.decodeFromString<LanMessage>(bytes.toString(Charsets.UTF_8))
    }.getOrNull()
}

/** Reads 4-byte length-prefixed JSON frames from a socket. */
internal class LanFrameReader(private val input: DataInputStream) {
    fun read(): LanMessage? {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length !in 1..LAN_MAX_FRAME_BYTES) {
            throw IllegalStateException("invalid LAN frame length $length")
        }
        val payload = ByteArray(length)
        input.readFully(payload)
        return LanCodec.decode(payload) ?: throw IllegalStateException("invalid LAN frame payload")
    }
}

/** Writes 4-byte length-prefixed JSON frames to a socket. */
internal class LanFrameWriter(private val output: DataOutputStream) {
    @Synchronized
    fun write(message: LanMessage) {
        val payload = LanCodec.encode(message)
        require(payload.size in 1..LAN_MAX_FRAME_BYTES) { "LAN frame too large" }
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }
}
