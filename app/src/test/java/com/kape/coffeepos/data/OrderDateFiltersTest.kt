package com.kape.coffeepos.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class OrderDateFiltersTest {
    private val manila = ZoneId.of(BUSINESS_DAY_TIME_ZONE_ID)

    private fun manilaMillis(value: String): Long =
        ZonedDateTime.parse(value).withZoneSameInstant(manila).toInstant().toEpochMilli()

    @Test
    fun ordersBeforeTwoAmUsePreviousBusinessDateLabel() {
        val label = orderBusinessDateLabel(
            timestampMs = manilaMillis("2026-07-29T01:59:59+08:00"),
            cutoffMinutes = 120
        )

        assertEquals("7/28/2026", label)
    }

    @Test
    fun ordersAtTwoAmUseCurrentBusinessDateLabel() {
        val label = orderBusinessDateLabel(
            timestampMs = manilaMillis("2026-07-29T02:00:00+08:00"),
            cutoffMinutes = 120
        )

        assertEquals("7/29/2026", label)
    }

    @Test
    fun customRangeUsesBusinessDayWindow() {
        val july28 = manilaMillis("2026-07-28T00:00:00+08:00")

        val window = orderDateFilterWindow(
            dateRange = ReportDateRange.CUSTOM,
            customStart = july28,
            customEnd = july28,
            cutoffMinutes = 120,
            nowMs = manilaMillis("2026-07-29T12:00:00+08:00")
        )

        assertEquals(manilaMillis("2026-07-28T02:00:00+08:00"), window.startMs)
        assertEquals(manilaMillis("2026-07-29T02:00:00+08:00"), window.endMs)
    }
}
