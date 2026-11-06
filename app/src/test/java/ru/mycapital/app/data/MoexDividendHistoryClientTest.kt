package ru.mycapital.app.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoexDividendHistoryClientTest {
    @Test
    fun sberFiveYearGrowthTreatsVerifiedNoDividendYearAsZero() {
        val rows = listOf(
            dividend("2021-05-12", 18.70),
            // Sber paid no dividend in 2022; no event row is expected for that year.
            dividend("2023-05-11", 25.00),
            dividend("2024-07-11", 33.30),
            dividend("2025-07-18", 34.84),
            dividend("2026-07-20", 37.64)
        )

        val metrics = calculateDividendHistoryMetrics(
            rows,
            currentPrice = 292.43,
            today = LocalDate.of(2026, 11, 6),
            annualCoverageVerified = true
        )

        assertEquals(15.02, metrics.fiveYearGrowthPercent!!, 0.01)
    }

    @Test
    fun missingYearIsNotAssumedZeroWhenHistoryCoverageIsUnverified() {
        val rows = listOf(
            dividend("2021-07-12", 18.70),
            dividend("2023-07-21", 25.00),
            dividend("2024-07-11", 33.30),
            dividend("2025-07-18", 34.84)
        )

        val metrics = calculateDividendHistoryMetrics(
            rows,
            currentPrice = 292.43,
            today = LocalDate.of(2026, 11, 6),
            annualCoverageVerified = false
        )

        assertNull(metrics.fiveYearGrowthPercent)
    }

    @Test
    fun trailingTwelveMonthYieldUsesOnlyLast365Days() {
        val rows = listOf(
            dividend("2025-07-18", 34.84),
            dividend("2026-07-20", 37.64)
        )

        val metrics = calculateDividendHistoryMetrics(
            rows,
            currentPrice = 292.43,
            today = LocalDate.of(2026, 11, 6),
            annualCoverageVerified = true
        )

        assertEquals(12.87, metrics.trailingTwelveMonthYieldPercent!!, 0.01)
    }

    private fun dividend(date: String, amount: Double) =
        HistoricalDividend("SBER", LocalDate.parse(date), amount, "RUB")
}
