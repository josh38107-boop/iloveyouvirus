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
        assertNull(remoteDoubleOrNull(null, "pos_order.discount_percent"))
        assertEquals(20.0, remoteDoubleOrNull("20.0", "pos_order.discount_percent")!!, 0.0)
    }

    @Test
    fun reportsTheInvalidFieldInsteadOfThrowingClassCastException() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            remoteLong("not-a-number", "shift.id")
        }
        assertTrue(error.message.orEmpty().contains("shift.id"))

        val discountError = assertThrows(IllegalArgumentException::class.java) {
            remoteDoubleOrNull("not-a-number", "pos_order.discount_percent")
        }
        assertTrue(discountError.message.orEmpty().contains("pos_order.discount_percent"))
    }

    @Test
    fun parsesRemoteInventoryAddOnNumbers() {
        val parsed = orderInventoryAddOnFromRemote(
            mapOf(
                "id" to "addon-1",
                "order_id" to "order-1",
                "ingredient_id" to "cup",
                "quantity" to 2.5,
                "created_at" to 1_000L,
                "updated_at" to 1_100L,
                "restored_at" to null
            ),
            local = null
        )

        assertEquals(2.5, parsed.quantity, 0.0)
        assertEquals(1_000L, parsed.createdAt)
        assertEquals(1_100L, parsed.updatedAt)
        assertNull(parsed.restoredAt)
    }

    @Test
    fun parsesRemoteInventoryAddOnNumericStrings() {
        val local = OrderInventoryAddOn(
            id = "addon-1",
            orderId = "order-1",
            ingredientId = "cup",
            quantity = 1.0,
            createdAt = 900L,
            restoredAt = 1_050L,
            updatedAt = 1_200L,
            localAdjustmentId = 77L
        )

        val parsed = orderInventoryAddOnFromRemote(
            mapOf(
                "id" to "addon-1",
                "order_id" to "order-1",
                "ingredient_id" to "cup",
                "quantity" to "2.5",
                "created_at" to "1000",
                "updated_at" to "1100",
                "restored_at" to "1300"
            ),
            local = local
        )

        assertEquals(2.5, parsed.quantity, 0.0)
        assertEquals(1_000L, parsed.createdAt)
        assertEquals(1_300L, parsed.restoredAt)
        assertEquals(1_200L, parsed.updatedAt)
        assertEquals(77L, parsed.localAdjustmentId)
    }

    @Test
    fun invalidRemoteInventoryAddOnQuantityReportsFieldName() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            orderInventoryAddOnFromRemote(
                mapOf(
                    "id" to "addon-1",
                    "order_id" to "order-1",
                    "ingredient_id" to "cup",
                    "quantity" to "not-a-number",
                    "created_at" to "1000",
                    "updated_at" to "1100",
                    "restored_at" to null
                ),
                local = null
            )
        }

        assertTrue(error.message.orEmpty().contains("order_inventory_add_on.quantity"))
    }
}
