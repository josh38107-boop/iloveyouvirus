package com.kape.coffeepos.viewmodel

import com.kape.coffeepos.RECEIPT_DISCLAIMER_LINE
import com.kape.coffeepos.RECEIPT_THANK_YOU_LINE
import com.kape.coffeepos.data.PromotionResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotionReceiptDecisionTest {
    @Test
    fun firstConfirmedWinningPrintIncludesPromotionQr() {
        val result = PromotionResult(
            isWinner = true,
            printed = false,
            qrUrl = "https://example.test/claim/KAPE-1234"
        )

        assertTrue(shouldIncludePromotionOnReceipt(result))
    }

    @Test
    fun acknowledgedWinnerReprintsWithoutPromotionQr() {
        val result = PromotionResult(
            isWinner = true,
            printed = true,
            qrUrl = "https://example.test/claim/KAPE-1234"
        )

        assertFalse(shouldIncludePromotionOnReceipt(result))
    }

    @Test
    fun nonWinnerAndMissingQrNeverUseCombinedPrint() {
        assertFalse(shouldIncludePromotionOnReceipt(PromotionResult(isWinner = false)))
        assertFalse(shouldIncludePromotionOnReceipt(PromotionResult(isWinner = true, printed = false)))
    }

    @Test
    fun receiptPreparationCopyDoesNotRevealOutcome() {
        val forbidden = listOf("winner", "promotion", "reward", "claim", "qr")
        val visibleCopy = listOf(RECEIPT_PREPARING_LABEL, RECEIPT_PREPARATION_ERROR)

        visibleCopy.forEach { text ->
            forbidden.forEach { word ->
                assertFalse("'$word' must not appear in '$text'", text.contains(word, ignoreCase = true))
            }
        }
    }

    @Test
    fun secondReceiptUsesFiveSecondCountdown() {
        assertEquals(listOf(5, 4, 3, 2, 1), secondReceiptCountdownValues())
    }

    @Test
    fun onlyFirstReceiptCopyKicksCashDrawer() {
        assertTrue(shouldKickDrawerForReceiptCopy(1))
        assertFalse(shouldKickDrawerForReceiptCopy(2))
    }

    @Test
    fun winningReceiptOmitsRewardIntervalButKeepsClaimDetails() {
        val text = promotionReceiptText(
            PromotionResult(
                isWinner = true,
                ordersPerReward = 300,
                sequenceNumber = 600,
                claimCode = "KAPE-TEST",
                qrUrl = "https://example.test/promotion"
            )
        )

        assertFalse(text.contains("Reward interval", ignoreCase = true))
        assertTrue(text.contains("Winning order: 600"))
        assertTrue(text.contains("Claim code: KAPE-TEST"))
        assertTrue(text.contains("Scan the QR code and follow the link."))
    }

    @Test
    fun receiptFooterUsesTheRequestedUppercaseLines() {
        assertEquals("THANK YOU FOR YOUR ORDER", RECEIPT_THANK_YOU_LINE)
        assertEquals("THIS IS NOT AN OFFICIAL RECEIPT", RECEIPT_DISCLAIMER_LINE)
    }
}
