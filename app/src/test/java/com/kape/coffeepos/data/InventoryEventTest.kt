package com.kape.coffeepos.data

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InventoryEventTest {
    @Test
    fun newAdjustmentsReceiveUniquePendingEventIds() {
        val first = InventoryAdjustment(ingredientId = "beans", deltaQuantity = -1.0, reason = "sale", createdAt = 1)
        val second = InventoryAdjustment(ingredientId = "beans", deltaQuantity = -1.0, reason = "sale", createdAt = 1)

        assertNotEquals(first.eventId, second.eventId)
        assertFalse(first.synced)
        assertFalse(second.synced)
    }
}
