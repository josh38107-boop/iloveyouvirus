package com.kape.coffeepos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafetyValidationTest {
    @Test
    fun customReportRequiresBothDates() {
        assertEquals(CUSTOM_REPORT_MISSING_DATES_ERROR, customReportRangeError(null, null))
        assertEquals(CUSTOM_REPORT_MISSING_DATES_ERROR, customReportRangeError(100L, null))
        assertEquals(CUSTOM_REPORT_MISSING_DATES_ERROR, customReportRangeError(null, 200L))
    }

    @Test
    fun customReportRejectsReversedRangeAndAcceptsValidRange() {
        assertEquals(CUSTOM_REPORT_REVERSED_DATES_ERROR, customReportRangeError(200L, 100L))
        assertNull(customReportRangeError(100L, 100L))
        assertNull(customReportRangeError(100L, 200L))
        assertEquals(100L to 200L, requireValidCustomReportRange(100L, 200L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun repositoryGuardRejectsIncompleteCustomRange() {
        requireValidCustomReportRange(null, 200L)
    }

    @Test
    fun equivalentIngredientNamesNormalizeToTheSameId() {
        assertEquals("brown-sugar", normalizeIngredientId("Brown Sugar"))
        assertEquals("brown-sugar", normalizeIngredientId("Brown-Sugar"))
        assertEquals("brown-sugar", normalizeIngredientId("brown sugar!"))
    }
}
