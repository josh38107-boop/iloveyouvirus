package com.kape.coffeepos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MutationIdTest {
    private val row = """{"id":"store","store_name":"Kanlungan"}"""

    @Test
    fun identicalSharedRowUsesDifferentMutationIdsOnDifferentDevices() {
        val counterOne = versionedMutationId("device-one", "store_settings", row)
        val counterTwo = versionedMutationId("device-two", "store_settings", row)

        assertNotEquals(counterOne, counterTwo)
    }

    @Test
    fun retryingIdenticalSharedRowOnSameDeviceReusesMutationId() {
        val firstAttempt = versionedMutationId("device-one", "store_settings", row)
        val retry = versionedMutationId("device-one", "store_settings", row)

        assertEquals(firstAttempt, retry)
    }

    @Test
    fun entityTypeRemainsPartOfMutationIdentity() {
        val settings = versionedMutationId("device-one", "store_settings", row)
        val menuItem = versionedMutationId("device-one", "menu_item", row)

        assertNotEquals(settings, menuItem)
    }

    @Test
    fun inventoryEventKeepsItsBusinessIdempotencyKey() {
        val existingEventId = "inventory-event-123"

        assertEquals(
            existingEventId,
            inventoryEventMutationId(existingEventId, generatedId = "unused-random-id")
        )
    }
}
