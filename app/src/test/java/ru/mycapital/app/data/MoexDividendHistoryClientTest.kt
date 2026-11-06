package ru.mycapital.app.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.time.ZoneId
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
    fun forwardYieldUsesDeclaredAndForecastPaymentsInNext365Days() {
        val today = LocalDate.of(2026, 11, 6)
        fun millis(date: String) = LocalDate.parse(date).atStartOfDay(ZoneId.of("Europe/Moscow"))
            .toInstant().toEpochMilli()
        val events = listOf(
            DividendCalendarEvent("SBER", 18.70, "FORECAST", millis("2027-05-10"), null, "forecast-2027"),
            DividendCalendarEvent("SBER", 5.00, "ANNOUNCED", millis("2027-08-10"), null, "announced-2027"),
            DividendCalendarEvent("GAZP", 20.00, "FORECAST", millis("2027-05-10"), null, "gazp-2027"),
            DividendCalendarEvent("SBER", 99.00, "FORECAST", millis("2028-01-10"), null, "outside-window")
        )

        val yield = calculateForwardDividendYieldPercent(events, "SBER", 292.43, today)

        assertEquals((23.70 / 292.43) * 100.0, yield!!, 0.001)
    }

    private fun dividend(date: String, amount: Double) =
        HistoricalDividend("SBER", LocalDate.parse(date), amount, "RUB")
}
