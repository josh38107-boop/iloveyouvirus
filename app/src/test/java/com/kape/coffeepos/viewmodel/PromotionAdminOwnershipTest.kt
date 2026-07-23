package com.kape.coffeepos.viewmodel

import com.kape.coffeepos.data.SupabaseSyncManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotionAdminOwnershipTest {
    @Test
    fun posKeepsOperationalPromotionMethodsWithoutConfigurationWrites() {
        val viewModelMethods = PosViewModel::class.java.declaredMethods.map { it.name }.toSet()
        val syncMethods = SupabaseSyncManager::class.java.declaredMethods.map { it.name }.toSet()

        assertTrue("refreshPromotionConfig" in viewModelMethods)
        assertTrue("lookupPromotionClaim" in viewModelMethods)
        assertTrue("applyPromotionToLine" in viewModelMethods)
        assertFalse("savePromotionConfig" in viewModelMethods)
        assertFalse("togglePromotionEnabled" in viewModelMethods)

        assertTrue(syncMethods.any { it.startsWith("getPromotionConfig") })
        assertTrue(syncMethods.any { it.startsWith("getPromotionResult") })
        assertTrue(syncMethods.any { it.startsWith("lookupPromotionClaim") })
        assertFalse(syncMethods.any { it.startsWith("updatePromotionConfig") })
    }
}
