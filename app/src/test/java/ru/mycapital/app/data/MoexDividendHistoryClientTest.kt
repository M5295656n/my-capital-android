package ru.mycapital.app.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import java.time.ZoneId
import org.junit.Test

class MoexDividendHistoryClientTest {
    @Test
    fun sberFiveYearGrowthUsesLatestCompletedFiveYearPeriod() {
        val rows = listOf(
            dividend("2020-05-12", 18.70),
            // Sber paid no dividend in 2022; no event row is expected for that year.
            dividend("2022-05-11", 25.00),
            dividend("2023-07-11", 33.30),
            dividend("2024-07-18", 34.84),
            dividend("2025-07-20", 37.64)
        )

        val metrics = calculateDividendHistoryMetrics(
            rows,
            currentPrice = 292.43,
            today = LocalDate.of(2026, 11, 6),
            annualCoverageVerified = false
        )

        assertEquals((Math.pow(37.64 / 18.70, 1.0 / 5.0) - 1.0) * 100.0, metrics.fiveYearGrowthPercent!!, 0.01)
    }

    @Test
    fun currentPartialYearIsExcludedFromFiveYearGrowth() {
        val rows = listOf(
            dividend("2020-07-12", 10.0),
            dividend("2025-07-18", 20.0),
            dividend("2026-07-18", 100.0)
        )
        val metrics = calculateDividendHistoryMetrics(
            rows, 100.0, LocalDate.of(2026, 11, 6), annualCoverageVerified = false
        )
        assertEquals((Math.pow(2.0, 1.0 / 5.0) - 1.0) * 100.0, metrics.fiveYearGrowthPercent!!, 0.001)
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
