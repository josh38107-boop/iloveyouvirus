package com.kape.coffeepos.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId

class BusinessDayTest {
    private val manila = ZoneId.of(BUSINESS_DAY_TIME_ZONE_ID)

    private fun manilaMillis(value: String): Long =
        ZonedDateTime.parse(value).withZoneSameInstant(manila).toInstant().toEpochMilli()

    @Test
    fun oneFiftyNineBelongsToPreviousBusinessDate() {
        val timestamp = manilaMillis("2026-07-27T01:59:59+08:00")
        val window = businessDayWindow(timestamp, cutoffMinutes = 120)

        assertEquals("2026-07-26", window.businessDate)
        assertEquals(manilaMillis("2026-07-26T02:00:00+08:00"), window.startMs)
        assertEquals(manilaMillis("2026-07-27T02:00:00+08:00"), window.endMs)
    }

    @Test
    fun exactlyTwoAmStartsNewBusinessDate() {
        val timestamp = manilaMillis("2026-07-27T02:00:00+08:00")
        val window = businessDayWindow(timestamp, cutoffMinutes = 120)

        assertEquals("2026-07-27", window.businessDate)
        assertEquals(manilaMillis("2026-07-27T02:00:00+08:00"), window.startMs)
        assertEquals(manilaMillis("2026-07-28T02:00:00+08:00"), window.endMs)
    }
}
