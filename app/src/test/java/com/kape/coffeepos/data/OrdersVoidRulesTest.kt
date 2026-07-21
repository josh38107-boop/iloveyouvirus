package com.kape.coffeepos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrdersVoidRulesTest {
    @Test
    fun dineInExcludesTakeoutOnlyIngredientFromRestoration() {
        assertTrue(isExcludedFromVoidRestoration("cup", "Dine-In", setOf("cup"), emptySet()))
    }

    @Test
    fun takeOutRestoresTakeoutOnlyIngredient() {
        assertFalse(isExcludedFromVoidRestoration("cup", "Take-Out", setOf("cup"), emptySet()))
    }

    @Test
    fun complimentaryExclusionStillWins() {
        assertTrue(isExcludedFromVoidRestoration("syrup", "Take-Out", emptySet(), setOf("syrup")))
    }

    @Test
    fun legacyAddOnRequiresExactReasonAndUniquePrefix() {
        val orderId = "abcdef12-1111-2222-3333-444444444444"
        assertEquals(
            "ABCDEF12",
            legacyOrderAddOnPrefix("Post-checkout add-on (Order ABCDEF12): Cup")
        )
        assertNull(legacyOrderAddOnPrefix("Manual adjustment for ABCDEF12"))
        assertTrue(isLegacyOrderPrefixUnique(orderId, listOf(orderId, "99999999-other")))
        assertFalse(isLegacyOrderPrefixUnique(orderId, listOf(orderId, "abcdef12-collision")))
    }

    @Test
    fun restoredStructuredAddOnIsNotSelectedAgain() {
        val pending = OrderInventoryAddOn("a", "order", "cup", 1.0, 1L, null, 1L)
        val restored = pending.copy(id = "b", restoredAt = 2L, updatedAt = 2L)

        assertEquals(listOf(pending), unrestoredOrderAddOns(listOf(pending, restored)))
    }

    @Test
    fun addOnSyncPayloadNeverSendsNullRestoration() {
        val pending = OrderInventoryAddOn("a", "order", "cup", 1.0, 1L, null, 1L)
        val restored = pending.copy(restoredAt = 2L, updatedAt = 2L)

        assertFalse(orderInventoryAddOnPayload(pending).containsKey("restored_at"))
        assertEquals(2L, orderInventoryAddOnPayload(restored)["restored_at"])
        assertEquals("order", orderInventoryAddOnPayload(restored)["order_id"])
    }
}
