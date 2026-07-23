package com.kape.coffeepos.printer

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val DEFAULT_QR_SIZE = 240

internal fun qrRasterCommand(payload: String, size: Int = DEFAULT_QR_SIZE): ByteArray {
    require(payload.isNotBlank()) { "QR payload is empty." }
    require(size in 64..512) { "QR size is outside the supported range." }

    val matrix = QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2
        )
    )
    val widthBytes = (matrix.width + 7) / 8
    val raster = ByteArray(widthBytes * matrix.height)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            if (matrix[x, y]) {
                val index = y * widthBytes + (x / 8)
                raster[index] = (raster[index].toInt() or (0x80 shr (x % 8))).toByte()
            }
        }
    }

    return byteArrayOf(
        0x1D, 0x76, 0x30, 0x00,
        (widthBytes and 0xFF).toByte(),
        ((widthBytes shr 8) and 0xFF).toByte(),
        (matrix.height and 0xFF).toByte(),
        ((matrix.height shr 8) and 0xFF).toByte()
    ) + raster
}

internal fun buildPromotionTestQrUrl(template: String, claimCode: String = "TEST-ONLY"): String? {
    val cleanTemplate = template.trim()
    if (cleanTemplate.isBlank() || claimCode.isBlank()) return null

    return runCatching {
        val uri = URI(cleanTemplate)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            return null
        }

        val encodedCode = URLEncoder.encode(claimCode, StandardCharsets.UTF_8.name())
        if (cleanTemplate.contains("{CLAIM_CODE}")) {
            return cleanTemplate.replace("{CLAIM_CODE}", encodedCode)
        }

        val entryPattern = Regex("""([?&]entry\.\d+=)[^&#]*""")
        val entries = entryPattern.findAll(cleanTemplate).toList()
        if (entries.size != 1) return cleanTemplate
        entryPattern.replaceFirst(cleanTemplate, "${entries.single().groupValues[1]}$encodedCode")
    }.getOrNull()
}
