package com.kape.coffeepos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteNumberParsingTest {
    @Test
    fun parsesJsonNumbersAndPostgresNumericStrings() {
        assertEquals(42L, remoteLong(42.0, "id"))
        assertEquals(1_784_722_572_857L, remoteLong("1784722572857", "created_at"))
        assertEquals(125, remoteInt("125", "amount_cents"))
    }

    @Test
    fun preservesNullForOptionalNumbers() {
        assertNull(remoteLongOrNull(null, "closed_at"))
        assertNull(remoteIntOrNull(null, "ending_cash_cents"))
    }

    @Test
    fun reportsTheInvalidFieldInsteadOfThrowingClassCastException() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            remoteLong("not-a-number", "shift.id")
        }
        assertTrue(error.message.orEmpty().contains("shift.id"))
    }
}
