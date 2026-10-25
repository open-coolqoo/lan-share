package com.lanshare

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private val PairingCodePattern = Regex("""^\d{8}$""")

fun buildPairingUrl(origin: String, code: String, locale: String): String {
    val separator = if (origin.contains("?")) "&" else "?"
    return "${origin.trimEnd('/')}/$separator" + "pair=$code&lang=$locale"
}

fun pairingCodeFromScanResult(rawValue: String?): String? {
    val value = rawValue?.trim().orEmpty()
    if (PairingCodePattern.matches(value)) return value
    val query = scanResultQuery(value) ?: return null
    return pairingCodeFromQuery(query)
}

fun pairingCodeFromQrUrl(rawValue: String?): String? {
    val value = rawValue?.trim().orEmpty()
    if ("://" !in value) return null
    val query = runCatching { URI(value).rawQuery }.getOrNull() ?: return null
    return pairingCodeFromQuery(query)
}

private fun pairingCodeFromQuery(query: String): String? =
    query
        .split("&")
        .firstNotNullOfOrNull { parameter ->
            val keyValue = parameter.split("=", limit = 2)
            val key = keyValue.getOrNull(0)?.decodeUrlComponent()
            val code = keyValue.getOrNull(1)?.decodeUrlComponent()
            if (key == "pair" && code != null && PairingCodePattern.matches(code)) code else null
        }

private fun scanResultQuery(value: String): String? {
    val uriQuery = runCatching { URI(value).rawQuery }.getOrNull()
    if (!uriQuery.isNullOrBlank()) return uriQuery
    if ("?" in value) return value.substringAfter("?").substringBefore("#")
    if ("=" in value && "://" !in value) return value.substringBefore("#")
    return null
}

private fun String.decodeUrlComponent(): String =
    URLDecoder.decode(this, StandardCharsets.UTF_8.name())

fun pairingQrImage(origin: String, code: String, locale: String, sizePx: Int = 176): ImageBitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(buildPairingUrl(origin, code, locale), BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (matrix[x, y]) 0xFF10201A.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    return bitmap.asImageBitmap()
}
