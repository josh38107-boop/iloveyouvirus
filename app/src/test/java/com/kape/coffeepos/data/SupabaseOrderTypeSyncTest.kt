package com.kape.coffeepos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseOrderTypeSyncTest {
    private fun order(orderType: String) = PosOrder(
        id = "order-1",
        status = "paid",
        employeeId = "employee-1",
        shiftId = 42L,
        subtotalCents = 15000,
        discountCents = 0,
        taxCents = 0,
        tipCents = 0,
        totalCents = 15000,
        createdAt = 1_000L,
        paidAt = 1_000L,
        orderType = orderType
    )

    @Test
    fun uploadPayloadPreservesDineInOrderType() {
        val payload = posOrderUploadPayload(order("Dine-In"), "tablet-1" to 42L)

        assertEquals("Dine-In", payload["order_type"])
    }

    @Test
    fun uploadPayloadPreservesTakeOutOrderType() {
        val payload = posOrderUploadPayload(order("Take-Out"), "tablet-1" to 42L)

        assertEquals("Take-Out", payload["order_type"])
    }

    @Test
    fun downloadedOrderAcceptsCloudOrderTypeCorrection() {
        assertTrue(
            shouldApplyRemoteOrderTypeCorrection(
                isDownloadedRemoteOrder = true,
                localOrderType = "Dine-In",
                remoteOrderType = "Take-Out"
            )
        )
    }

    @Test
    fun locallyCreatedOrderRejectsOlderCloudDefault() {
        assertFalse(
            shouldApplyRemoteOrderTypeCorrection(
                isDownloadedRemoteOrder = false,
                localOrderType = "Take-Out",
                remoteOrderType = "Dine-In"
            )
        )
    }
}
