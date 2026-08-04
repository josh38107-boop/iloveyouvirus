package com.kape.coffeepos.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ItemDiscountCalculationTest {
    private fun menuItem(id: String, priceCents: Int) = MenuItem(
        id = id,
        categoryId = "coffee",
        name = id,
        description = "",
        basePriceCents = priceCents
    )

    @Test
    fun discountsOnlyTheSelectedCartLine() {
        val lines = listOf(
            CartLine(id = "first", item = menuItem("Latte", 15000)),
            CartLine(id = "second", item = menuItem("Mocha", 18000)),
            CartLine(id = "third", item = menuItem("Tea", 12000))
        )

        assertEquals(3600, calculateSingleItemDiscountCents(lines, "second", 20.0))
    }

    @Test
    fun quantityThreeStillDiscountsOneUnit() {
        val lines = listOf(
            CartLine(id = "latte", item = menuItem("Latte", 15000), quantity = 3)
        )

        assertEquals(3000, calculateSingleItemDiscountCents(lines, "latte", 20.0))
    }

    @Test
    fun modifiersAreIncludedInTheDiscountBasis() {
        val oatMilk = ModifierOption(
            id = "oat",
            groupId = "milk",
            name = "Oat Milk",
            priceDeltaCents = 2500
        )
        val lines = listOf(
            CartLine(
                id = "latte",
                item = menuItem("Latte", 15000),
                modifiers = listOf(oatMilk)
            )
        )

        assertEquals(3500, calculateSingleItemDiscountCents(lines, "latte", 20.0))
    }

    @Test
    fun missingSelectionProducesNoDiscount() {
        val lines = listOf(CartLine(id = "latte", item = menuItem("Latte", 15000)))

        assertEquals(0, calculateSingleItemDiscountCents(lines, null, 20.0))
        assertEquals(0, calculateSingleItemDiscountCents(lines, "missing", 20.0))
    }

    @Test
    fun multiItemDiscountIncludesAllSelectedLineTotals() {
        val paidModifier = ModifierOption("oat", "milk", "Oat Milk", 2500)
        val lines = listOf(
            CartLine(id = "latte", item = menuItem("Latte", 15000), quantity = 2, modifiers = listOf(paidModifier)),
            CartLine(id = "cookie", item = menuItem("Cookie", 5000), quantity = 3),
            CartLine(id = "tea", item = menuItem("Tea", 12000))
        )

        assertEquals(10000, calculateMultiItemDiscountCents(lines, setOf("latte", "cookie"), 20.0))
    }

    @Test
    fun multiItemDiscountRequiresSelectionAndValidPercent() {
        val lines = listOf(CartLine(id = "latte", item = menuItem("Latte", 15000), quantity = 2))

        assertEquals(0, calculateMultiItemDiscountCents(lines, emptySet(), 20.0))
        assertEquals(0, calculateMultiItemDiscountCents(lines, setOf("missing"), 20.0))
        assertEquals(0, calculateMultiItemDiscountCents(lines, setOf("latte"), 0.0))
    }

    @Test
    fun promotionDiscountsOneBaseDrinkButNotModifiers() {
        val paidModifier = ModifierOption("oat", "milk", "Oat Milk", 2500)
        val lines = listOf(
            CartLine(
                id = "latte",
                item = menuItem("Latte", 15000),
                quantity = 3,
                modifiers = listOf(paidModifier)
            )
        )

        assertEquals(15000, calculatePromotionBaseDiscountCents(lines, "latte"))
    }

    @Test
    fun promotionMissingSelectionProducesNoDiscount() {
        val lines = listOf(CartLine(id = "latte", item = menuItem("Latte", 15000)))

        assertEquals(0, calculatePromotionBaseDiscountCents(lines, null))
        assertEquals(0, calculatePromotionBaseDiscountCents(lines, "missing"))
    }
}
