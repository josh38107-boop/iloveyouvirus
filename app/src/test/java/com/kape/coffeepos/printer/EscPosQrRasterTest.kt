package com.kape.coffeepos.printer

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EscPosQrRasterTest {
    @Test
    fun rasterCommandUsesEscPosGsV0AndDecodesToOriginalPayload() {
        val payload = "https://www.facebook.com/profile.php?id=61589808854073"
        val command = qrRasterCommand(payload, size = 240)

        assertEquals(0x1D, command[0].toInt() and 0xFF)
        assertEquals(0x76, command[1].toInt() and 0xFF)
        assertEquals(0x30, command[2].toInt() and 0xFF)
        assertEquals(0x00, command[3].toInt() and 0xFF)

        val widthBytes = (command[4].toInt() and 0xFF) or
            ((command[5].toInt() and 0xFF) shl 8)
        val height = (command[6].toInt() and 0xFF) or
            ((command[7].toInt() and 0xFF) shl 8)
        assertEquals(30, widthBytes)
        assertEquals(240, height)
        assertEquals(8 + widthBytes * height, command.size)

        val pixels = IntArray(widthBytes * 8 * height) { 0xFFFFFFFF.toInt() }
        for (y in 0 until height) {
            for (x in 0 until widthBytes * 8) {
                val packed = command[8 + y * widthBytes + x / 8].toInt() and 0xFF
                if (packed and (0x80 shr (x % 8)) != 0) {
                    pixels[y * widthBytes * 8 + x] = 0xFF000000.toInt()
                }
            }
        }
        val decoded = QRCodeReader().decode(
            BinaryBitmap(
                HybridBinarizer(
                    RGBLuminanceSource(widthBytes * 8, height, pixels)
                )
            )
        )
        assertEquals(payload, decoded.text)
    }

    @Test
    fun promotionTestUrlReplacesTheSinglePrefilledClaimEntry() {
        val template =
            "https://docs.google.com/forms/d/e/example/viewform?usp=pp_url&entry.123456=old-code"

        val result = buildPromotionTestQrUrl(template)

        assertTrue(result!!.contains("entry.123456=TEST-ONLY"))
        assertTrue(result.startsWith("https://docs.google.com/forms/"))
    }

    @Test
    fun promotionTestUrlKeepsGenericHttpsDestinationUnchanged() {
        val businessLink =
            "https://www.google.com/search?q=Kanlungan+Coffee+Garage&kgmid=/g/11z8kznjv_"

        assertEquals(businessLink, buildPromotionTestQrUrl(businessLink))
        assertEquals(
            "https://docs.google.com/forms/d/e/example/viewform?usp=pp_url",
            buildPromotionTestQrUrl(
                "https://docs.google.com/forms/d/e/example/viewform?usp=pp_url"
            )
        )
        assertEquals(
            "https://docs.google.com/forms/d/e/example/viewform?entry.1=a&entry.2=b",
            buildPromotionTestQrUrl(
                "https://docs.google.com/forms/d/e/example/viewform?entry.1=a&entry.2=b"
            )
        )
        assertNull(buildPromotionTestQrUrl("http://example.com/form?entry.1=value"))
    }
}
