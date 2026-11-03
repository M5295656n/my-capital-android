package ru.mycapital.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerTradeImportRuleTest {
    @Test
    fun importsOnlyFullyExecutedStatus() {
        assertTrue(isFullyExecutedBrokerTrade("И"))
        assertTrue(isFullyExecutedBrokerTrade(" И "))
        assertTrue(isFullyExecutedBrokerTrade("ЗИ"))
        assertTrue(isFullyExecutedBrokerTrade(" ЗИ "))
        assertTrue(isFullyExecutedBrokerTrade("зи"))
    }

    @Test
    fun skipsUnsettledAndUnknownStatuses() {
        assertFalse(isFullyExecutedBrokerTrade("ЗО"))
        assertFalse(isFullyExecutedBrokerTrade("З"))
        assertFalse(isFullyExecutedBrokerTrade("О"))
        assertFalse(isFullyExecutedBrokerTrade(""))
        assertFalse(isFullyExecutedBrokerTrade("UNKNOWN"))
    }
}
