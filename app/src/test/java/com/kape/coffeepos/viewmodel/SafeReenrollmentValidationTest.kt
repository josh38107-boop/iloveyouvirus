package com.kape.coffeepos.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeReenrollmentValidationTest {
    @Test
    fun blankCodeExplainsWhereToGetIt() {
        assertEquals(
            "Enter the re-enrollment code from the admin website.",
            safeReenrollmentCodeError(" ")
        )
    }

    @Test
    fun completeWebsiteCodesAreAccepted() {
        assertNull(safeReenrollmentCodeError("AB12-CD_3"))
    }

    @Test
    fun incompleteOrMalformedCodesAreRejected() {
        assertEquals(
            "Enter the complete re-enrollment code exactly as shown on the website.",
            safeReenrollmentCodeError("ABC")
        )
        assertEquals(
            "Enter the complete re-enrollment code exactly as shown on the website.",
            safeReenrollmentCodeError("ABC 123")
        )
    }
}
