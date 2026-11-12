package com.lanshare.lan

import com.lanshare.deriveRoomKey
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val LAN_SERVICE_TYPE = "_lanshare._tcp"
internal const val LAN_PAIRING_TTL_MS = 120_000L
internal const val LAN_SESSION_TTL_MS = 24L * 60L * 60L * 1_000L

private const val QR_SCHEME = "lanshare-lan"
private const val AUTH_PREFIX = "lanshare-lan-auth-v1|"
private const val HOST_PREFIX = "lanshare-lan-host-v1|"
private const val RESUME_PREFIX = "lanshare-lan-resume-v1|"
private const val MDNS_PREFIX = "lanshare-lan-mdns-v1|"

/** One reachable `host:port` for the local signaling listener. */
data class LanAddress(val host: String, val port: Int) {
    /** `192.168.1.5:43000` or `[fe80::1]:43000`. */
    val authority: String
        get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    companion object {
        fun parse(raw: String): LanAddress? {
            val value = raw.trim()
            if (value.isEmpty()) return null
            if (value.startsWith("[")) {
                val end = value.indexOf(']')
                if (end <= 1) return null
                val host = value.substring(1, end)
                val port = value.substring(end + 1).removePrefix(":").toIntOrNull() ?: return null
                return if (host.isNotBlank() && port in 1..65535) LanAddress(host, port) else null
            }
            val colon = value.lastIndexOf(':')
            if (colon <= 0) return null
            val host = value.substring(0, colon)
            val port = value.substring(colon + 1).toIntOrNull() ?: return null
            return if (host.isNotBlank() && port in 1..65535) LanAddress(host, port) else null
        }
    }
}

internal data class LanPairingPayload(val code: String, val addresses: List<LanAddress>)

internal data class LanJoinTarget(
    val code: String,
    val roomKey: String,
    val addresses: List<LanAddress> = emptyList(),
)

/**
 * Pure parts of LAN pairing: the code rules mirrored from the Go server, the
 * HMAC proofs, the mDNS identity, and the QR payload. No sockets or Android
 * APIs live here so the same rules can be unit-tested on the JVM.
 */
internal object LanPairing {
    private val weakCodes = setOf(
        "00000000", "11111111", "22222222", "33333333", "44444444",
        "55555555", "66666666", "77777777", "88888888", "99999999",
        "12345678", "87654321",
    )

    private val random = SecureRandom()

    /** Mirrors `generateConnectionCode` in the Go server. */
    fun generateCode(random: SecureRandom = this.random): String {
        repeat(128) {
            val code = String.format(Locale.US, "%08d", random.nextInt(100_000_000))
            if (isUsableCode(code)) return code
        }
        error("unable to generate a secure connection code")
    }

    /** Mirrors `isUsableCode`, including its modulo-10 cyclic runs. */
    fun isUsableCode(code: String): Boolean {
        if (code.length != 8 || code.any { it !in '0'..'9' }) return false
        if (code in weakCodes) return false
        var ascending = true
        var descending = true
        var same = true
        for (index in 1 until code.length) {
            val previous = code[index - 1] - '0'
            val current = code[index] - '0'
            if (current != (previous + 1) % 10) ascending = false
            if (current != (previous + 9) % 10) descending = false
            if (code[index] != code[0]) same = false
        }
        return !ascending && !descending && !same
    }

    fun roomKey(code: String): String = deriveRoomKey(code)

    fun authProof(roomKey: String, nonce: String, deviceId: String): String =
        encode(hmac(roomKey, AUTH_PREFIX + nonce + "|" + deviceId))

    fun hostProof(roomKey: String, nonce: String, hostDeviceId: String): String =
        encode(hmac(roomKey, HOST_PREFIX + nonce + "|" + hostDeviceId))

    /** Proof the host returns when a guest resumes with its session token. */
    fun resumeHostProof(roomKey: String, sessionToken: String, hostDeviceId: String): String =
        encode(hmac(roomKey, RESUME_PREFIX + sessionToken + "|" + hostDeviceId))

    /** First 16 base64url characters of the salted room identity. */
    fun mdnsSid(roomKey: String, salt: String): String =
        encode(hmac(roomKey, MDNS_PREFIX + salt)).take(16)

    fun matchesSid(roomKey: String, salt: String, advertised: String): Boolean =
        MessageDigest.isEqual(
            mdnsSid(roomKey, salt).toByteArray(Charsets.UTF_8),
            advertised.toByteArray(Charsets.UTF_8),
        )

    /**
     * Builds the QR a host shows. With a browser bridge the payload is a plain
     * HTTP URL: a phone camera opens the browser guest, while the native
     * scanner reads the same `pair`/`addr` parameters. Without one it falls
     * back to the custom scheme the native apps understand.
     */
    fun buildQrPayload(
        code: String,
        addresses: List<LanAddress>,
        webOrigin: String? = null,
    ): String {
        val builder = StringBuilder()
        if (!webOrigin.isNullOrBlank()) {
            builder.append("http://").append(webOrigin).append("/?pair=").append(code)
        } else {
            builder.append("$QR_SCHEME://pair?pair=").append(code)
        }
        addresses.distinctBy { it.authority }.take(6).forEach { address ->
            builder.append("&addr=").append(encodeComponent(address.authority))
        }
        return builder.toString()
    }

    fun parseQrPayload(raw: String?): LanPairingPayload? {
        val value = raw?.trim().orEmpty()
        if (!value.startsWith("$QR_SCHEME://") &&
            !value.startsWith("http://") &&
            !value.startsWith("https://")
        ) {
            return null
        }
        val query = runCatching { URI(value).rawQuery }.getOrNull() ?: return null
        var code: String? = null
        val addresses = mutableListOf<LanAddress>()
        for (parameter in query.split("&")) {
            val pair = parameter.split("=", limit = 2)
            if (pair.size != 2) continue
            val key = runCatching { decodeComponent(pair[0]) }.getOrNull() ?: continue
            val rawValue = runCatching { decodeComponent(pair[1]) }.getOrNull() ?: continue
            when (key) {
                "pair", "code" ->
                    if (rawValue.length == 8 && rawValue.all { it in '0'..'9' }) code = rawValue
                "addr", "host" -> LanAddress.parse(rawValue)?.let(addresses::add)
            }
        }
        return code?.let { LanPairingPayload(it, addresses.distinctBy { address -> address.authority }) }
    }

    fun randomToken(bytes: Int = 16, random: SecureRandom = this.random): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return encode(buffer)
    }

    private fun hmac(key: String, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun encodeComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decodeComponent(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())
}
