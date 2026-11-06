package ru.mycapital.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerTradeImportRuleTest {
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
