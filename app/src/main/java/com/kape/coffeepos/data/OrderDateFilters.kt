package com.kape.coffeepos.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class OrderDateWindow(
    val startMs: Long,
    val endMs: Long
)

fun orderDateFilterWindow(
    dateRange: ReportDateRange,
    customStart: Long?,
    customEnd: Long?,
    cutoffMinutes: Int,
    nowMs: Long = System.currentTimeMillis()
): OrderDateWindow {
    return when (dateRange) {
        ReportDateRange.TODAY -> {
            val window = businessDayWindow(nowMs, cutoffMinutes)
            OrderDateWindow(window.startMs, window.endMs)
        }
        ReportDateRange.MONTH -> OrderDateWindow(nowMs - 30L * 24L * 60L * 60L * 1000L, Long.MAX_VALUE)
        ReportDateRange.ALL -> OrderDateWindow(0L, Long.MAX_VALUE)
        ReportDateRange.CUSTOM -> {
            val start = customStart?.let { businessDateWindow(it, cutoffMinutes).first } ?: 0L
            val end = customEnd?.let { businessDateWindow(it, cutoffMinutes).second } ?: Long.MAX_VALUE
            OrderDateWindow(start, end)
        }
    }
}

fun filterOrdersForBusinessDateRange(
    orders: List<PosOrder>,
    dateRange: ReportDateRange,
    customStart: Long?,
    customEnd: Long?,
    cutoffMinutes: Int,
    nowMs: Long = System.currentTimeMillis()
): List<PosOrder> {
    val window = orderDateFilterWindow(dateRange, customStart, customEnd, cutoffMinutes, nowMs)
    return orders.filter { order -> order.createdAt >= window.startMs && order.createdAt < window.endMs }
}

fun orderBusinessDateLabel(timestampMs: Long, cutoffMinutes: Int): String {
    val businessDateStart = businessDayWindow(timestampMs, cutoffMinutes).startMs
    return SimpleDateFormat("M/d/yyyy", Locale.US).apply {
        timeZone = TimeZone.getTimeZone(BUSINESS_DAY_TIME_ZONE_ID)
    }.format(Date(businessDateStart))
}

fun groupOrdersByBusinessDate(
    orders: List<PosOrder>,
    cutoffMinutes: Int
): Map<String, List<PosOrder>> =
    orders.groupBy { order -> orderBusinessDateLabel(order.createdAt, cutoffMinutes) }
