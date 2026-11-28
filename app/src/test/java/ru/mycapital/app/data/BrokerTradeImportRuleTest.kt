package ru.mycapital.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import java.time.LocalDate
import org.junit.Test

class BrokerTradeImportRuleTest {
    @Test
    fun brokerTradeTimestampPreservesExecutionTimeForChronologicalHistory() {
        val date = LocalDate.parse("2026-09-15")
        val depositAtStartOfDay = date.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        val purchaseAt = brokerTradeTimestamp(date, "10:54:54")
        assertTrue(purchaseAt > depositAtStartOfDay)
        assertEquals(10 * 3600 + 54 * 60 + 54, (purchaseAt - depositAtStartOfDay) / 1000)
    }

    @Test
    fun brokerTradeTimestampFallsBackSafelyWhenTimeIsMissingOrMalformed() {
        val date = LocalDate.parse("2026-09-15")
        val start = date.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        assertEquals(start, brokerTradeTimestamp(date, ""))
        assertEquals(start, brokerTradeTimestamp(date, "not-a-time"))
    }

    @Test
    fun importsSettledAndConcludedUnsettledTrades() {
        assertTrue(isImportableBrokerTrade("И"))
        assertTrue(isImportableBrokerTrade(" И "))
        assertTrue(isImportableBrokerTrade("ЗИ"))
        assertTrue(isImportableBrokerTrade(" ЗИ "))
        assertTrue(isImportableBrokerTrade("зи"))
        // ЗО: the deal was concluded during the report period, but settlement
        // obligations had not yet been fulfilled at period end.
        assertTrue(isImportableBrokerTrade("ЗО"))
        assertTrue(isImportableBrokerTrade(" зо "))
    }

    @Test
    fun skipsUnknownAndNonTradeStatuses() {
        assertFalse(isImportableBrokerTrade("З"))
        assertFalse(isImportableBrokerTrade("О"))
        assertFalse(isImportableBrokerTrade("П"))
        assertFalse(isImportableBrokerTrade(""))
        assertFalse(isImportableBrokerTrade("UNKNOWN"))
    }
}
