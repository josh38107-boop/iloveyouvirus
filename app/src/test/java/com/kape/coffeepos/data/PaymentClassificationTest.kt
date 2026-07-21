package com.kape.coffeepos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentClassificationTest {
    @Test
    fun legacyBuiltInMethodsAreClassifiedWithoutClassifyingCustomMethods() {
        assertEquals(PaymentCategories.CASH, PaymentCategories.fromLegacyMethod("Cash"))
        assertEquals(PaymentCategories.ONLINE, PaymentCategories.fromLegacyMethod("Online"))
        assertEquals(PaymentCategories.ONLINE, PaymentCategories.fromLegacyMethod("GCash"))
        assertNull(PaymentCategories.fromLegacyMethod("BPI"))
    }

    @Test
    fun categorySnapshotDoesNotReplaceTheExactPaymentMethodName() {
        val payment = Payment(
            orderId = "order-1",
            method = "BPI",
            amountCents = 15_000,
            amountTenderedCents = 15_000,
            changeCents = 0,
            createdAt = 1L,
            paymentCategory = PaymentCategories.ONLINE
        )

        assertEquals("BPI", payment.method)
        assertEquals(PaymentCategories.ONLINE, payment.paymentCategory)
    }
}
