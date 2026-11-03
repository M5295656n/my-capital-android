package ru.mycapital.app.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class SberBrokerReportRealLayoutFixtureTest {
    @Test
    fun parsesSanitizedMainAccountLayoutWithTheObservedRowCounts() {
        val report = parseFixture("/broker-reports/main-account.html")

        assertEquals("MAIN-TEST", report.accountId)
        assertEquals(LocalDate.parse("2026-08-01"), report.periodStart)
        assertEquals(LocalDate.parse("2026-10-30"), report.periodEnd)
        assertEquals(100.0, report.openingCash, 0.000001)
        assertEquals(475.57, report.closingCash, 0.000001)
        assertEquals(436.0, report.cashCredits, 0.000001)
        assertEquals(60.43, report.cashDebits, 0.000001)
        assertEquals(12, report.assets.size)
        assertEquals(92, report.cashFlows.size)
        assertEquals(48, report.trades.size)
        assertEquals(43, report.cashFlows.count { it.kind == BrokerCashFlowKind.DEPOSIT })
        assertEquals(3, report.cashFlows.count { it.kind == BrokerCashFlowKind.DIVIDEND })
        assertEquals(3, report.cashFlows.count { it.kind == BrokerCashFlowKind.INTERNAL_TRANSFER })
        assertEquals(47, report.trades.count { it.status == "ЗИ" })
        assertEquals(1, report.trades.count { it.status == "ЗО" })
        assertEquals(setOf("M01", "M02", "M03"), report.cashFlows
            .filter { it.kind == BrokerCashFlowKind.DIVIDEND }.mapNotNull { it.ticker }.toSet())
    }

    @Test
    fun parsesSanitizedIis3LayoutAndDeduplicatesItsSevenRegisterRows() {
        val report = parseFixture("/broker-reports/iis3-account.html")

        assertEquals("IIS-TEST", report.accountId)
        assertEquals(LocalDate.parse("2026-10-02"), report.periodStart)
        assertEquals(LocalDate.parse("2026-10-30"), report.periodEnd)
        assertEquals(0.0, report.openingCash, 0.000001)
        assertEquals(149.88, report.closingCash, 0.000001)
        assertEquals(150.0, report.cashCredits, 0.000001)
        assertEquals(0.12, report.cashDebits, 0.000001)
        assertEquals(5, report.assets.size)
        assertEquals(20, report.cashFlows.size)
        assertEquals(6, report.trades.size)
        assertEquals(4, report.cashFlows.count { it.kind == BrokerCashFlowKind.DEPOSIT })
        assertEquals(1, report.cashFlows.count { it.kind == BrokerCashFlowKind.COUPON })
        assertEquals(3, report.cashFlows.count { it.kind == BrokerCashFlowKind.INTERNAL_TRANSFER })
        assertEquals(6, report.trades.count { it.status == "ЗИ" })
        assertEquals("I01", report.trades.first().ticker)
        assertEquals("I01", report.cashFlows.single { it.kind == BrokerCashFlowKind.COUPON }.ticker)
    }

    private fun parseFixture(path: String): SberBrokerReport {
        val html = requireNotNull(javaClass.getResourceAsStream(path)) { "Fixture not found: $path" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return SberBrokerReportParser.parse(html)
    }
}
