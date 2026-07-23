package com.kape.coffeepos.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomDiscountCalculationTest {
    private fun item(id: String, price: Int) = MenuItem(
        id = id,
        categoryId = "coffee",
        name = id,
        description = "",
        basePriceCents = price,
        active = true
    )

    @Test
    fun wholeOrderDiscountIncludesQuantityAndModifiers() {
        val modifier = ModifierOption(
            id = "shot",
            groupId = "addons",
            name = "Shot",
            priceDeltaCents = 2500
        )
        val lines = listOf(
            CartLine(item = item("latte", 10000), quantity = 2, modifiers = listOf(modifier)),
            CartLine(item = item("tea", 5000))
        )

        assertEquals(3000, calculateWholeOrderDiscountCents(lines, 10.0))
    }

    @Test
    fun wholeOrderDiscountRoundsToNearestCentAndIsCapped() {
        val lines = listOf(CartLine(item = item("drink", 999)))

        assertEquals(333, calculateWholeOrderDiscountCents(lines, 33.33))
        assertEquals(999, calculateWholeOrderDiscountCents(lines, 100.0))
        assertEquals(0, calculateWholeOrderDiscountCents(lines, 0.0))
    }
}
