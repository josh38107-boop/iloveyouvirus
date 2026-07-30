package com.kape.coffeepos.viewmodel

import com.kape.coffeepos.data.DailyReport
import com.kape.coffeepos.data.ReportDateRange
import com.kape.coffeepos.data.ReportCancelledOrder
import com.kape.coffeepos.data.ReportOrderLine
import com.kape.coffeepos.data.ReportProcessedOrder
import com.kape.coffeepos.data.TopSellingItem
import org.junit.Assert.assertTrue
import org.junit.Test

class DayEndClosingReportTextTest {
    private fun report(
        cancelledOrders: List<ReportCancelledOrder> = emptyList(),
        processedOrders: List<ReportProcessedOrder> = listOf(
            ReportProcessedOrder(
                id = "RK2-00015",
                timestamp = 1_754_000_000_000,
                cashierName = "Ana",
                customerName = "Mika",
                paymentLabel = "Cash",
                totalCents = 25000,
                lines = listOf(
                    ReportOrderLine(quantity = 1, name = "Spanish Latte"),
                    ReportOrderLine(quantity = 2, name = "Americano")
                )
            )
        )
    ) = DailyReport(
        orderCount = 1,
        grossSalesCents = 28000,
        discountsCents = 3000,
        netSalesCents = 25000,
        paymentTotals = mapOf("Cash" to 25000, "GCash" to 18000),
        onlinePaymentSalesCents = 18000,
        topItems = listOf(TopSellingItem("Spanish Latte", 1, 15000)),
        cashDrawerStarting = 100000,
        cashDrawerSales = 25000,
        cashDrawerExpected = 125000,
        cashDrawerActual = 124500,
        cashDrawerDifference = -500,
        taxRatePercent = 12.0,
        taxCents = 2679,
        cancelledOrders = cancelledOrders,
        processedOrders = processedOrders
    )

    @Test
    fun dayEndClosingIncludesMainSectionsAndSelectedRange() {
        val text = buildDayEndClosingReportText(report(), "07/01/2026 - 07/30/2026 (Cashier: Ana)", 48)

        assertTrue(text.contains("Day-end Closing"))
        assertTrue(text.contains("Range:"))
        assertTrue(text.contains("07/01/2026 - 07/30/2026 (Cashier: Ana)"))
        assertTrue(text.contains("Total net sales"))
        assertTrue(text.contains("Cash balance"))
        assertTrue(text.contains("Total revenue per VAT rate"))
        assertTrue(text.contains("Total revenue per payment method"))
        assertTrue(text.contains("Processed Orders"))
    }

    @Test
    fun closeShiftAutoPrintUsesTodayRange() {
        assertTrue(closeShiftAutoPrintRange() == ReportDateRange.TODAY)
        assertTrue(closeShiftAutoPrintRangeName() == "Today")
    }

    @Test
    fun paymentTotalsAndProcessedOrderLinesArePrinted() {
        val text = buildDayEndClosingReportText(report(), "Today", 48)

        assertTrue(text.contains("Cash"))
        assertTrue(text.contains("GCash"))
        assertTrue(text.contains("#RK2-00015"))
        assertTrue(text.contains("Cashier: Ana"))
        assertTrue(text.contains("Customer: Mika"))
        assertTrue(text.contains("Payment: Cash"))
        assertTrue(text.contains("1x Spanish Latte"))
        assertTrue(text.contains("2x Americano"))
    }

    @Test
    fun cancelledReceiptsShowEmptyAndNonEmptyStates() {
        val emptyText = buildDayEndClosingReportText(report(cancelledOrders = emptyList()), "Today", 48)
        assertTrue(emptyText.contains("No cancelled receipts"))

        val cancelledText = buildDayEndClosingReportText(
            report(
                cancelledOrders = listOf(
                    ReportCancelledOrder(
                        id = "RK2-00013",
                        timestamp = 1_754_000_000_000,
                        reason = "Customer changed order",
                        netCents = 1277,
                        grossCents = 1520
                    )
                )
            ),
            "Today",
            48
        )
        assertTrue(cancelledText.contains("RK2-00013"))
        assertTrue(cancelledText.contains("Customer changed order"))
    }
}
