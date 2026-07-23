package com.kape.coffeepos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptItemFormattingTest {
    @Test
    fun longDrinkNameKeepsPriceOnFirstLineAt32Characters() {
        val lines = formatReceiptItemLines(
            quantity = 1,
            itemName = "ICE TRES LECHES COFFEE (16)",
            price = "₱130.00",
            width = 32
        )

        assertEquals("1 x ICE TRES LECHES      ₱130.00", lines[0])
        assertEquals("    COFFEE (16)", lines[1])
        assertTrue(lines.all { it.length <= 32 })
    }

    @Test
    fun shortNameUsesOneRightAlignedLine() {
        val lines = formatReceiptItemLines(2, "LATTE", "P220.00", 32)

        assertEquals(1, lines.size)
        assertEquals(32, lines.single().length)
        assertTrue(lines.single().startsWith("2 x LATTE"))
        assertTrue(lines.single().endsWith("P220.00"))
    }

    @Test
    fun supportsAllConfiguredWidthsAndCurrencyStyles() {
        val widths = listOf(32, 40, 42, 48)
        val prices = listOf("P1,234.00", "Php1,234.00", "₱1,234.00", "±1,234.00")

        for (width in widths) {
            for (price in prices) {
                val lines = formatReceiptItemLines(
                    quantity = 12,
                    itemName = "EXTRA LONG SIGNATURE DRINK WITH CREAM",
                    price = price,
                    width = width
                )

                assertTrue(lines.first().endsWith(price))
                assertTrue(lines.all { it.length <= width })
            }
        }
    }

    @Test
    fun safelySplitsAnUnusuallyLongWordWithoutLosingIt() {
        val itemName = "SUPERCALIFRAGILISTICEXPIALIDOCIOUS"
        val lines = formatReceiptItemLines(1, itemName, "P99.00", 32)
        val reconstructedName = lines.mapIndexed { index, line ->
            if (index == 0) {
                line.removePrefix("1 x ").substringBefore("P99.00").trim()
            } else {
                line.trim()
            }
        }.joinToString("")

        assertEquals(itemName, reconstructedName)
        assertTrue(lines.all { it.length <= 32 })
    }
}
