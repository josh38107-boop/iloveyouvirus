package com.kape.coffeepos.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ShiftSyncConflictTest {
    private fun shift(
        id: Long = 10L,
        openedAt: Long = 1_000L,
        closedAt: Long? = null,
        startingCashCents: Int = 10_000,
        endingCashCents: Int? = null,
        cashAddedCents: Int = 0,
        cashRemovedCents: Int = 0
    ) = Shift(
        id = id,
        employeeId = "manager",
        openedAt = openedAt,
        closedAt = closedAt,
        startingCashCents = startingCashCents,
        endingCashCents = endingCashCents,
        cashAddedCents = cashAddedCents,
        cashRemovedCents = cashRemovedCents
    )

    @Test
    fun staleRemoteCashRemovalCannotEraseLocalRemoval() {
        val local = shift(cashRemovedCents = 5_000)
        val staleRemote = shift(id = 99L, openedAt = 2_000L)

        assertEquals(
            shift(id = 10L, openedAt = 2_000L, cashRemovedCents = 5_000),
            mergeRemoteShiftTotals(local, staleRemote)
        )
    }

    @Test
    fun newerRemoteCashTotalsAreAccepted() {
        val local = shift(cashAddedCents = 2_000, cashRemovedCents = 5_000)
        val newerRemote = shift(
            id = 99L,
            closedAt = 3_000L,
            cashAddedCents = 3_000,
            cashRemovedCents = 7_500
        )

        assertEquals(
            shift(
                id = 10L,
                closedAt = 3_000L,
                cashAddedCents = 3_000,
                cashRemovedCents = 7_500
            ),
            mergeRemoteShiftTotals(local, newerRemote)
        )
    }

    @Test
    fun repeatedMergeIsIdempotent() {
        val local = shift(cashAddedCents = 2_000, cashRemovedCents = 5_000)
        val remote = shift(id = 99L, cashAddedCents = 1_000, cashRemovedCents = 4_000)

        val once = mergeRemoteShiftTotals(local, remote)
        val twice = mergeRemoteShiftTotals(once, remote)

        assertEquals(once, twice)
    }
}
