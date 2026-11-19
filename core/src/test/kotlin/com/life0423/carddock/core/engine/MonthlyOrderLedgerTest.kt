package com.life0423.carddock.core.engine

import java.time.ZonedDateTime
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class MonthlyOrderLedgerTest {
    private val end = ZonedDateTime.parse("2026-03-31T16:00:00+08:00[Asia/Shanghai]")
    private val latest = end.toLocalDateTime()
    private val start = LocalDateTime.parse("2026-02-28T16:00:00")
    private fun order(id: String, at: LocalDateTime = start, cents: Long? = 3150) =
        OrderObservation(id, at, completed = true, takeout = true, originalPaidCents = cents)

    @Test fun monthEndAndTwoOverlappingPages() {
        val ledger = MonthlyOrderLedger(end)
        assertEquals(start, ledger.since.toLocalDateTime())
        val a = order("a")
        val b = order("b")
        assertNull(ledger.accept(listOf(order("future", latest, null), a, b), true, false))
        assertNull(ledger.result())
        assertNull(ledger.accept(listOf(b, order("old", start.minusSeconds(1), null),
            order("cancelled", cents = null).copy(completed = false),
            order("other", cents = null).copy(takeout = false)), false, true))
        assertEquals(2, ledger.result()!!.count)
        assertEquals("63.00", ledger.result()!!.total)
    }

    @Test fun incompleteOrConflictingPageCannotFinish() {
        val ledger = MonthlyOrderLedger(end)
        val a = order("a")
        assertNotNull(ledger.accept(listOf(a), false, false))
        assertNotNull(ledger.accept(listOf(a, a), true, false))
        assertNull(ledger.accept(listOf(a), true, false))
        assertNotNull(ledger.accept(listOf(order("b")), false, true))
        assertNotNull(ledger.accept(listOf(a), false, false))
        assertNotNull(ledger.accept(listOf(a.copy(originalPaidCents = 10)), false, true))
        assertNotNull(ledger.accept(listOf(a, order("b", cents = null)), false, true))
        assertNull(ledger.result())
    }

    @Test fun identicalTotalsForDifferentOrdersHaveDifferentEvidence() {
        fun digest(id: String): String {
            val ledger = MonthlyOrderLedger(end)
            assertNull(ledger.accept(listOf(order(id, cents = 0)), true, true))
            return ledger.result()!!.digest
        }
        assertNotEquals(digest("A"), digest("B"))
        val empty = MonthlyOrderLedger(end)
        assertNotNull(empty.accept(emptyList(), true, false))
        assertNull(empty.accept(emptyList(), true, true))
        assertEquals("0.00", empty.result()!!.total)
    }
}
