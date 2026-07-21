package com.kape.coffeepos.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAuditReceiptTest {
    private val paidReceipt = "AVE n' You\nDINE-IN\nTOTAL                 P100.00"

    @Test
    fun paidReceiptIsUnchanged() {
        assertEquals(paidReceipt, terminalAuditReceiptText(paidReceipt, "paid", null))
    }

    @Test
    fun voidedReceiptContainsAuditStatusAndReason() {
        val result = terminalAuditReceiptText(paidReceipt, "void", "Wrong item")

        assertTrue(result.contains("VOIDED ORDER - AUDIT COPY"))
        assertTrue(result.contains("Reason: Wrong item"))
        assertTrue(result.endsWith(paidReceipt))
        assertFalse(result.contains("FREE DRINK QR"))
    }

    @Test
    fun refundedReceiptContainsAuditStatusAndReason() {
        val result = terminalAuditReceiptText(paidReceipt, "refunded", "Duplicate charge")

        assertTrue(result.contains("REFUNDED ORDER - AUDIT COPY"))
        assertTrue(result.contains("Reason: Duplicate charge"))
    }

    @Test
    fun newVoidedReceiptOmitsReasonLine() {
        val result = terminalAuditReceiptText(paidReceipt, "void", null)

        assertTrue(result.contains("VOIDED ORDER - AUDIT COPY"))
        assertFalse(result.contains("Reason:"))
        assertFalse(result.contains("No reason recorded"))
    }

    @Test
    fun newRefundedReceiptOmitsReasonLine() {
        val result = terminalAuditReceiptText(paidReceipt, "refunded", null)

        assertTrue(result.contains("REFUNDED ORDER - AUDIT COPY"))
        assertFalse(result.contains("Reason:"))
        assertFalse(result.contains("No reason recorded"))
    }
}
