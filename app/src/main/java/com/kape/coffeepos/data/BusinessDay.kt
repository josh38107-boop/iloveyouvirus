package com.kape.coffeepos.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

const val DEFAULT_BUSINESS_DAY_CUTOFF_MINUTES = 120
const val BUSINESS_DAY_TIME_ZONE_ID = "Asia/Manila"

private val businessDayTimeZone: TimeZone = TimeZone.getTimeZone(BUSINESS_DAY_TIME_ZONE_ID)

data class BusinessDayWindow(
    val businessDate: String,
    val startMs: Long,
    val endMs: Long
)

fun normalizedBusinessDayCutoffMinutes(value: Int?): Int =
    value?.coerceIn(0, 1439) ?: DEFAULT_BUSINESS_DAY_CUTOFF_MINUTES

fun businessDayWindow(
    timestampMs: Long = System.currentTimeMillis(),
    cutoffMinutes: Int = DEFAULT_BUSINESS_DAY_CUTOFF_MINUTES
): BusinessDayWindow {
    val cutoff = normalizedBusinessDayCutoffMinutes(cutoffMinutes)
    val cal = Calendar.getInstance(businessDayTimeZone)
    cal.timeInMillis = timestampMs
    cal.set(Calendar.HOUR_OF_DAY, cutoff / 60)
    cal.set(Calendar.MINUTE, cutoff % 60)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    if (timestampMs < cal.timeInMillis) {
        cal.add(Calendar.DAY_OF_MONTH, -1)
    }
    val start = cal.timeInMillis
    val label = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = businessDayTimeZone
    }.format(Date(start))
    return BusinessDayWindow(label, start, start + 24L * 60L * 60L * 1000L)
}

fun businessDateWindow(
    businessDate: Long,
    cutoffMinutes: Int = DEFAULT_BUSINESS_DAY_CUTOFF_MINUTES
): Pair<Long, Long> {
    val cutoff = normalizedBusinessDayCutoffMinutes(cutoffMinutes)
    val cal = Calendar.getInstance(businessDayTimeZone)
    cal.timeInMillis = businessDate
    cal.set(Calendar.HOUR_OF_DAY, cutoff / 60)
    cal.set(Calendar.MINUTE, cutoff % 60)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val start = cal.timeInMillis
    return start to start + 24L * 60L * 60L * 1000L
}

fun minutesUntilBusinessDayCutoff(
    timestampMs: Long = System.currentTimeMillis(),
    cutoffMinutes: Int = DEFAULT_BUSINESS_DAY_CUTOFF_MINUTES
): Long {
    val window = businessDayWindow(timestampMs, cutoffMinutes)
    return ((window.endMs - timestampMs) / 60_000L).coerceAtLeast(0L)
}
