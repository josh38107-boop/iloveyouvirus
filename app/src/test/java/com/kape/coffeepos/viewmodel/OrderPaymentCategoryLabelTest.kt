package com.kape.coffeepos.viewmodel

import com.kape.coffeepos.data.Payment
import com.kape.coffeepos.data.PaymentCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrderPaymentCategoryLabelTest {
    private fun payment(method: String, category: String? = null) = Payment(
        orderId = "order-1",
        method = method,
        amountCents = 100,
        amountTenderedCents = 100,
        changeCents = 0,
        createdAt = 1,
        paymentCategory = category
    )

    @Test
    fun cashAndOnlineUseCapitalCategoryLabels() {
        assertEquals("CASH", orderPaymentCategoryLabel(listOf(payment("Cash", PaymentCategories.CASH))))
        assertEquals("ONLINE", orderPaymentCategoryLabel(listOf(payment("BPI", PaymentCategories.ONLINE))))
    }

    @Test
    fun splitPaymentUsesStableCashThenOnlineLabel() {
        val payments = listOf(
            payment("BPI", PaymentCategories.ONLINE),
            payment("Cash", PaymentCategories.CASH),
            payment("Cash", PaymentCategories.CASH)
        )

        assertEquals("CASH + ONLINE", orderPaymentCategoryLabel(payments))
    }

    @Test
    fun historicalMethodsUseLegacyClassification() {
        assertEquals("CASH", orderPaymentCategoryLabel(listOf(payment("Cash"))))
        assertEquals("ONLINE", orderPaymentCategoryLabel(listOf(payment("GCash"))))
    }

    @Test
    fun unclassifiedPaymentDoesNotShowIncorrectCategory() {
        assertNull(orderPaymentCategoryLabel(listOf(payment("Complimentary"))))
    }
}
