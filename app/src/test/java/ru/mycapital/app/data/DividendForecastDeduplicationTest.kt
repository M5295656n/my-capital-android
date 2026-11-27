package ru.mycapital.app.data

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class DividendForecastDeduplicationTest {
    private fun millis(date: String) = LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun event(ticker: String, status: String, recordDate: String?, paymentDate: String?, key: String, amount: Double = 31.44) =
        DividendCalendarEvent(ticker, amount, status, recordDate?.let(::millis), paymentDate?.let(::millis), key)

    @Test fun differentEstimatedAndOfficialDatesInSameMonthReplaceForecast() {
        val events = listOf(
            event("TATN", "FORECAST", "2027-01-10", null, "jan-forecast"),
            event("TATN", "RECOMMENDED", "2027-01-19", "2027-01-28", "jan-confirmed")
        )
        assertEquals(listOf("jan-confirmed"), removeSupersededDividendForecasts(events).map { it.sourceKey })
    }

    @Test fun januaryPaymentDateMatchesEvenWhenOfficialRecordDateIsInDecember() {
        val events = listOf(
            event("TATN", "FORECAST", "2027-01-08", null, "jan-forecast"),
            event("TATN", "RECOMMENDED", "2026-12-18", "2027-01-15", "jan-confirmed")
        )
        assertEquals(listOf("jan-confirmed"), removeSupersededDividendForecasts(events).map { it.sourceKey })
    }

    @Test fun differentAmountDoesNotReplaceForecast() {
        val events = listOf(
            event("TATN", "FORECAST", "2027-01-10", null, "jan-forecast", 31.44),
            event("TATN", "RECOMMENDED", "2027-01-19", null, "jan-confirmed", 20.00)
        )
        assertEquals(2, removeSupersededDividendForecasts(events).size)
    }

    @Test fun twoHistoricalForecastRowsInSameMonthCollapseEvenWhenAmountsDiffer() {
        val events = listOf(
            event("SBER", "FORECAST", "2027-05-10", null, "sber-forecast-a", 35.0),
            event("SBER", "FORECAST", "2027-05-24", null, "sber-forecast-b", 38.0)
        )
        assertEquals(1, removeSupersededDividendForecasts(events).size)
    }

    @Test fun twoConfirmedTatneftPayoutsInSameMonthRemainSeparate() {
        val events = listOf(
            event("TATN", "RECOMMENDED", "2027-01-10", "2027-01-19", "confirmed-a", 31.44),
            event("TATN", "ANNOUNCED", "2027-01-20", "2027-01-29", "confirmed-b", 20.00)
        )
        assertEquals(setOf("confirmed-a", "confirmed-b"), removeSupersededDividendForecasts(events).map { it.sourceKey }.toSet())
    }

    @Test fun forecastIsReplacedOnlyOnceWhenMultipleConfirmedEventsExist() {
        val events = listOf(
            event("TATN", "FORECAST", "2027-01-10", null, "forecast"),
            event("TATN", "RECOMMENDED", "2027-01-19", null, "confirmed-a"),
            event("TATN", "ANNOUNCED", "2027-01-25", null, "confirmed-b", 20.00)
        )
        assertEquals(setOf("confirmed-a", "confirmed-b"), removeSupersededDividendForecasts(events).map { it.sourceKey }.toSet())
    }

    @Test fun differentTickersAndMonthsRemainIndependent() {
        val events = listOf(
            event("TATN", "FORECAST", "2027-01-10", null, "tatn-jan"),
            event("SBER", "RECOMMENDED", "2027-01-10", null, "sber-jan"),
            event("TATN", "FORECAST", "2027-05-10", null, "tatn-may")
        )
        assertEquals(3, removeSupersededDividendForecasts(events).size)
    }

    @Test fun oneConfirmedPayoutDoesNotSuppressTwoIndependentSameMonthForecasts() {
        val forecasts = listOf(
            HistoricalDividendForecast("TATN", 31.44, LocalDate.parse("2027-01-10"), "forecast-a"),
            HistoricalDividendForecast("TATN", 31.44, LocalDate.parse("2027-01-20"), "forecast-b")
        )
        val events = listOf(
            event("TATN", "RECOMMENDED", "2027-01-19", "2027-01-28", "confirmed")
        )

        val remaining = removeHistoryForecastsSupersededByProvider("TATN", forecasts, events)

        assertEquals(listOf("forecast-b"), remaining.map { it.sourceKey })
    }

    @Test fun twoConfirmedPayoutsSuppressTwoMatchingHistoryForecastsOneToOne() {
        val forecasts = listOf(
            HistoricalDividendForecast("TATN", 31.44, LocalDate.parse("2027-01-10"), "forecast-a"),
            HistoricalDividendForecast("TATN", 20.00, LocalDate.parse("2027-01-20"), "forecast-b")
        )
        val events = listOf(
            event("TATN", "RECOMMENDED", "2027-01-19", null, "confirmed-a", 31.44),
            event("TATN", "ANNOUNCED", "2027-01-25", null, "confirmed-b", 20.00)
        )

        val remaining = removeHistoryForecastsSupersededByProvider("TATN", forecasts, events)

        assertEquals(0, remaining.size)
    }

    @Test fun confirmedPaymentDoesNotHideDifferentIndependentForecastInSameMonth() {
        val forecasts = listOf(
            HistoricalDividendForecast("TATN", 31.44, LocalDate.parse("2027-01-10"), "forecast-main"),
            HistoricalDividendForecast("TATN", 20.00, LocalDate.parse("2027-01-25"), "forecast-second")
        )
        val events = listOf(event("TATN", "RECOMMENDED", "2027-01-19", null, "confirmed-main", 31.44))

        val remaining = removeHistoryForecastsSupersededByProvider("TATN", forecasts, events)

        assertEquals(listOf("forecast-second"), remaining.map { it.sourceKey })
    }

    @Test fun historyForecastMatcherUsesMonthAndAmountWhenDatesDiffer() {
        val events = listOf(event("TATN", "RECOMMENDED", "2027-01-19", null, "confirmed"))
        assertEquals(true, hasProviderDividendOnRecordDate("TATN", LocalDate.parse("2027-01-10"), events, amountPerShare = 31.44))
        assertEquals(false, hasProviderDividendOnRecordDate("TATN", LocalDate.parse("2027-01-10"), events, amountPerShare = 20.0))
    }
}
