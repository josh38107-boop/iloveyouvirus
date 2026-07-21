package com.kape.coffeepos.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscountSettingsValidationTest {
    @Test
    fun validDiscountRangeIncludesZeroAndOneHundred() {
        assertNull(discountSettingsValidationError("0", "100"))
        assertNull(discountSettingsValidationError("15.5", "20"))
    }

    @Test
    fun blankAndNonnumericDiscountsAreRejected() {
        assertEquals("Senior discount is required.", discountSettingsValidationError("", "20"))
        assertEquals("PWD discount must be a number.", discountSettingsValidationError("20", "abc"))
    }

    @Test
    fun outOfRangeDiscountsAreRejected() {
        assertEquals("Senior discount must be between 0 and 100.", discountSettingsValidationError("-1", "20"))
        assertEquals("PWD discount must be between 0 and 100.", discountSettingsValidationError("20", "101"))
    }
}
