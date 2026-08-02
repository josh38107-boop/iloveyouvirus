package com.kape.coffeepos.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoveCashAuthorizationTest {
    @Test
    fun cashierWithCorrectPinCanRemoveCash() {
        assertNull(
            removeCashAuthorizationError(
                isManager = false,
                enteredPin = "1234",
                correctPin = "1234"
            )
        )
    }

    @Test
    fun cashierWithWrongPinCannotRemoveCash() {
        assertEquals(
            "Incorrect PIN. Please try again.",
            removeCashAuthorizationError(
                isManager = false,
                enteredPin = "9999",
                correctPin = "1234"
            )
        )
    }

    @Test
    fun cashierWithEmptyPinCannotRemoveCash() {
        assertEquals(
            "Incorrect PIN. Please try again.",
            removeCashAuthorizationError(
                isManager = false,
                enteredPin = "",
                correctPin = "1234"
            )
        )
    }

    @Test
    fun managerCanRemoveCashWithoutPin() {
        assertNull(
            removeCashAuthorizationError(
                isManager = true,
                enteredPin = "",
                correctPin = "1234"
            )
        )
    }

    @Test
    fun invalidAmountCannotRemoveCash() {
        assertNull(parsePositiveCashAmountCents(""))
        assertNull(parsePositiveCashAmountCents("0"))
        assertNull(parsePositiveCashAmountCents("-10"))
    }

    @Test
    fun validAmountConvertsToCents() {
        assertEquals(12550, parsePositiveCashAmountCents("125.50"))
    }
}
