package com.lanshare

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.util.Base64

private const val ROOM_KEY_SALT = "lanshare-room-v1:2026-06-25"
private const val ROOM_KEY_INFO = "lanshare-room-v1"

fun deriveRoomKey(code: String): String {
    val inputKeyMaterial = code.toByteArray(StandardCharsets.UTF_8)
    val salt = ROOM_KEY_SALT.toByteArray(StandardCharsets.UTF_8)
    val info = ROOM_KEY_INFO.toByteArray(StandardCharsets.UTF_8)
    val pseudorandomKey = hmacSha256(salt, inputKeyMaterial)
    val okm = hkdfExpand(pseudorandomKey, info, 32)
    val digest = MessageDigest.getInstance("SHA-256").digest(okm)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun hkdfExpand(pseudorandomKey: ByteArray, info: ByteArray, length: Int): ByteArray {
    val output = ByteArray(length)
    var previous = ByteArray(0)
    var offset = 0
    var counter = 1
    while (offset < length) {
        val input = previous + info + byteArrayOf(counter.toByte())
        previous = hmacSha256(pseudorandomKey, input)
        val copyLength = minOf(previous.size, length - offset)
        previous.copyInto(output, destinationOffset = offset, endIndex = copyLength)
        offset += copyLength
        counter += 1
    }
    return output
}

private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}
