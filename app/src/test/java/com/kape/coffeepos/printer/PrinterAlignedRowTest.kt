package com.kape.coffeepos.printer

import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterAlignedRowTest {
    @Test
    fun currencyExpansionNeverPushesPricePastPaperWidth() {
        val currencyPrices = listOf("P130.00", "Php130.00", "₱130.00", "±130.00")

        for (price in currencyPrices) {
            val lines = formatAlignedPrinterRow(
                left = "1 x ICE TRES LECHES COFFEE",
                right = price,
                width = 32
            )

            assertTrue(lines.first().endsWith(price))
            assertTrue(lines.all { it.length <= 32 })
            assertTrue(lines.drop(1).all { it.startsWith("    ") })
        }
    }

    @Test
    fun largePriceAndMultiDigitQuantityRemainWithinWidth() {
        val lines = formatAlignedPrinterRow(
            left = "12 x EXTRA LONG SIGNATURE BEVERAGE",
            right = "Php12,345.67",
            width = 32
        )

        assertTrue(lines.first().endsWith("Php12,345.67"))
        assertTrue(lines.all { it.length <= 32 })
        assertTrue(lines.drop(1).all { it.startsWith("     ") })
    }
}
