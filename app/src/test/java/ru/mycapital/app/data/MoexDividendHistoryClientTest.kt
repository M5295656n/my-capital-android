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

    @Test
    fun forecastsRecurringDividendMonthFromRecentHistoryUsingMedianAmountAndDate() {
        val history = listOf(
            dividend("2023-05-18", 4.0),
            dividend("2024-05-22", 5.0),
            dividend("2025-05-20", 4.8),
        ).map { it.copy(secid = "T") }

        val forecasts = calculateHistoricalDividendForecasts(
            ticker = "T",
            dividends = history,
            today = LocalDate.of(2026, 11, 14),
            horizonMonths = 18
        )

        assertEquals(1, forecasts.size)
        assertEquals("T", forecasts.single().ticker)
        assertEquals(4.8, forecasts.single().amountPerShare, 0.000001)
        assertEquals(LocalDate.of(2027, 5, 20), forecasts.single().recordDate)
        assertEquals("MYCAPITAL_HISTORY:T:2027-05-20", forecasts.single().sourceKey)
    }

    @Test
    fun adjustsPreSplitDividendAmountsToCurrentShareUnitsBeforeForecasting() {
        val history = listOf(
            dividend("2025-05-16", 32.0).copy(secid = "T"),
            dividend("2026-05-25", 4.5).copy(secid = "T")
        )
        val splits = listOf(
            HistoricalSplit(
                secid = "T",
                tradedate = LocalDate.of(2026, 4, 17),
                before = 1.0,
                after = 10.0
            )
        )

        val adjusted = adjustHistoricalDividendsForSplits(history, splits)
        assertEquals(3.2, adjusted[0].amountPerShare, 0.000001)
        assertEquals(4.5, adjusted[1].amountPerShare, 0.000001)

        val forecast = calculateHistoricalDividendForecasts(
            ticker = "T",
            dividends = adjusted,
            today = LocalDate.of(2026, 11, 14)
        ).single()
        assertEquals(LocalDate.of(2027, 5, 21), forecast.recordDate)
        assertEquals(3.85, forecast.amountPerShare, 0.000001)
    }

    @Test
    fun ignoresOldAndNonRubleHistoryWhenEstimatingRecurringPayments() {
        val history = listOf(
            dividend("2020-05-20", 100.0).copy(secid = "T"),
            dividend("2023-05-20", 4.0).copy(secid = "T"),
            dividend("2024-05-20", 5.0).copy(secid = "T"),
            dividend("2025-05-20", 100.0).copy(secid = "T", currency = "USD")
        )

        val forecasts = calculateHistoricalDividendForecasts(
            ticker = "T",
            dividends = history,
            today = LocalDate.of(2026, 11, 14)
        )

        assertEquals(1, forecasts.size)
        assertEquals(4.5, forecasts.single().amountPerShare, 0.000001)
    }

    @Test
    fun doesNotForecastMonthWithOnlyOneHistoricalOccurrence() {
        val forecasts = calculateHistoricalDividendForecasts(
            ticker = "T",
            dividends = listOf(dividend("2025-05-20", 4.8).copy(secid = "T")),
            today = LocalDate.of(2026, 11, 14)
        )

        assertEquals(0, forecasts.size)
    }

    @Test
    fun historyForecastYieldUsesStoredForecastAndDeclaredPayouts() {
        val today = LocalDate.of(2026, 11, 14)
        fun millis(date: String) = LocalDate.parse(date).atStartOfDay(ZoneId.of("Europe/Moscow"))
            .toInstant().toEpochMilli()
        val payouts = listOf(
            PayoutEntity(assetId = null, ticker = "T", payoutType = "Дивиденд", amountPerUnit = 4.8, quantity = 4.0,
                totalAmount = 19.2, expectedDate = millis("2027-05-20"), recordDate = millis("2027-05-20"),
                status = "FORECAST", sourceKey = "MYCAPITAL_HISTORY:T:2027-05-20"),
            PayoutEntity(assetId = null, ticker = "T", payoutType = "Дивиденд", amountPerUnit = 2.0, quantity = 4.0,
                totalAmount = 8.0, expectedDate = millis("2027-08-20"), status = "ANNOUNCED",
                sourceKey = "ANNOUNCED:T:2027-08-20"),
            PayoutEntity(assetId = null, ticker = "SBER", payoutType = "Дивиденд", amountPerUnit = 100.0, quantity = 1.0,
                totalAmount = 100.0, expectedDate = millis("2027-05-20"), status = "FORECAST")
        )

        val yield = calculateForwardDividendYieldPercentFromPayouts(payouts, "T", 300.0, today)

        assertEquals((4.8 + 2.0) / 300.0 * 100.0, yield!!, 0.000001)
    }

    private fun dividend(date: String, amount: Double) =
        HistoricalDividend("SBER", LocalDate.parse(date), amount, "RUB")
}
