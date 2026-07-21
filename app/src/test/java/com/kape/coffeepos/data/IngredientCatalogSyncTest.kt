package com.kape.coffeepos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IngredientCatalogSyncTest {
    private fun ingredient(
        quantity: Double = 100.0,
        name: String = "Cup",
        unit: String = "pc",
        threshold: Double = 10.0,
        takeoutOnly: Boolean = false
    ) = Ingredient(
        id = "cup",
        name = name,
        unit = unit,
        quantityOnHand = quantity,
        lowStockThreshold = threshold,
        takeoutOnly = takeoutOnly
    )

    @Test
    fun quantityOnlyChangeDoesNotChangeCatalogMetadata() {
        assertEquals(
            ingredientCatalogMetadata(ingredient(quantity = 100.0)),
            ingredientCatalogMetadata(ingredient(quantity = 99.0))
        )
    }

    @Test
    fun catalogMetadataChangesAreDetected() {
        val original = ingredientCatalogMetadata(ingredient())

        assertNotEquals(original, ingredientCatalogMetadata(ingredient(name = "Takeout Cup")))
        assertNotEquals(original, ingredientCatalogMetadata(ingredient(unit = "each")))
        assertNotEquals(original, ingredientCatalogMetadata(ingredient(threshold = 20.0)))
        assertNotEquals(original, ingredientCatalogMetadata(ingredient(takeoutOnly = true)))
    }

    @Test
    fun remoteCatalogMetadataPreservesExistingLocalQuantity() {
        val local = ingredient(quantity = 99.0)
        val remote = ingredient(
            quantity = 100.0,
            name = "Takeout Cup",
            unit = "each",
            threshold = 20.0,
            takeoutOnly = true
        )

        assertEquals(remote.copy(quantityOnHand = 99.0), mergeRemoteIngredientMetadata(local, remote))
    }

    @Test
    fun newRemoteIngredientKeepsInitialQuantityUntilBalanceIsPulled() {
        val remote = ingredient(quantity = 25.0)

        assertEquals(remote, mergeRemoteIngredientMetadata(null, remote))
    }

    @Test
    fun managerPendingMetadataWinsOverStaleRemoteMetadata() {
        val local = ingredient(quantity = 99.0, takeoutOnly = true)
        val staleRemote = ingredient(quantity = 100.0, takeoutOnly = false)

        assertEquals(local, resolveRemoteIngredient(local, staleRemote, preserveLocalMetadata = true))
    }

    @Test
    fun counterAcceptsRemoteMetadataButPreservesBalanceQuantity() {
        val local = ingredient(quantity = 99.0, takeoutOnly = true)
        val remote = ingredient(quantity = 100.0, takeoutOnly = false)

        assertEquals(
            remote.copy(quantityOnHand = 99.0),
            resolveRemoteIngredient(local, remote, preserveLocalMetadata = false)
        )
    }
}
