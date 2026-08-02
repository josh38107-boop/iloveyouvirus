package com.kape.coffeepos.viewmodel

import com.kape.coffeepos.data.DailyReport
import com.kape.coffeepos.data.IngredientUsageSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryPrintReportTest {
    @Test
    fun inventoryPrintReportIncludesHeaderAndTodayRange() {
        val text = buildInventoryUsageReportText(reportWithUsage(), "Today", 48)

        assertTrue(text.contains("Inventory Usage Report"))
        assertTrue(text.contains("Report ID:"))
        assertTrue(text.contains("Date:"))
        assertTrue(text.contains("Range:"))
        assertTrue(text.contains("Today"))
    }

    @Test
    fun inventoryPrintReportIncludesCustomRangeLabel() {
        val text = buildInventoryUsageReportText(reportWithUsage(), "08/01/2026 - 08/02/2026", 48)

        assertTrue(text.contains("08/01/2026 - 08/02/2026"))
    }

    @Test
    fun inventoryPrintReportPrintsUsedIngredientAndCurrentStock() {
        val text = buildInventoryUsageReportText(reportWithUsage(), "Today", 48)

        assertTrue(text.contains("Milk"))
        assertTrue(text.contains("2.5 L"))
        assertTrue(text.contains("Current stock:"))
        assertTrue(text.contains("10 L"))
    }

    @Test
    fun inventoryPrintReportExcludesZeroUsageIngredients() {
        val text = buildInventoryUsageReportText(reportWithUsage(), "Today", 48)

        assertFalse(text.contains("Sugar"))
    }

    @Test
    fun inventoryPrintReportPrintsEmptyUsageMessage() {
        val text = buildInventoryUsageReportText(DailyReport(), "Today", 48)

        assertTrue(text.contains("No inventory usage recorded for this period."))
    }

    private fun reportWithUsage() = DailyReport(
        ingredientUsage = listOf(
            IngredientUsageSummary(
                ingredientId = "milk",
                name = "Milk",
                unit = "L",
                usedToday = 2.5,
                restocked = 0.0,
                endingStock = 10.0,
                isLow = false
            ),
            IngredientUsageSummary(
                ingredientId = "sugar",
                name = "Sugar",
                unit = "g",
                usedToday = 0.0,
                restocked = 5.0,
                endingStock = 100.0,
                isLow = false
            )
        )
    )
}
