package com.kape.coffeepos.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreSettingsSyncTest {
    private fun settings(
        senior: Double,
        pwd: Double,
        voidPin: String = "1234",
        paymentVoidUpdatedAt: Long = 0
    ) = StoreSettings(
        storeName = "Kape",
        taxRatePercent = 0.0,
        tipPresets = "",
        receiptFooter = "Thanks",
        seniorDiscountPercent = senior,
        pwdDiscountPercent = pwd,
        voidRefundPin = voidPin,
        paymentVoidSettingsUpdatedAt = paymentVoidUpdatedAt
    )

    @Test
    fun websiteDiscountsWinWhileManagerPendingStoreFieldsArePreserved() {
        val local = settings(senior = 15.0, pwd = 10.0)
        val staleRemote = settings(senior = 20.0, pwd = 20.0)

        val resolved = resolveRemoteStoreSettings(local, staleRemote, preserveLocalSettings = true)
        assertEquals(local.storeName, resolved.storeName)
        assertEquals(20.0, resolved.seniorDiscountPercent, 0.0)
        assertEquals(20.0, resolved.pwdDiscountPercent, 0.0)
    }

    @Test
    fun websiteVoidPinWinsWhileManagerPendingStoreFieldsArePreserved() {
        val local = settings(senior = 20.0, pwd = 20.0, voidPin = "1111", paymentVoidUpdatedAt = 4)
        val remote = settings(senior = 20.0, pwd = 20.0, voidPin = "9876", paymentVoidUpdatedAt = 8)

        val resolved = resolveRemoteStoreSettings(local, remote, preserveLocalSettings = true)
        assertEquals(local.storeName, resolved.storeName)
        assertEquals("9876", resolved.voidRefundPin)
        assertEquals(8, resolved.paymentVoidSettingsUpdatedAt)
    }

    @Test
    fun counterAcceptsManagerSettings() {
        val local = settings(senior = 20.0, pwd = 20.0)
        val remote = settings(senior = 15.0, pwd = 10.0)

        assertEquals(remote, resolveRemoteStoreSettings(local, remote, preserveLocalSettings = false))
    }
}
