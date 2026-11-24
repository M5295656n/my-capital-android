package ru.mycapital.app.data

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class DividendForecastDeduplicationTest {
    private fun millis(date: String) =
        LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun event(
        ticker: String,
        status: String,
        recordDate: String?,
        paymentDate: String?,
        key: String,
        amount: Double = 31.44
    ) = DividendCalendarEvent(
        ticker, amount, status, recordDate?.let(::millis), paymentDate?.let(::millis), key
    )

    @Test
    fun removesOnlyForecastWithTheSameIssuerRecordDate() {
        val events = listOf(
            event("TATN", "FORECAST", "2027-01-10", null, "jan-forecast"),
            event("TATN", "RECOMMENDED", "2027-01-10", "2027-01-19", "jan-confirmed"),
            event("TATN", "FORECAST", "2027-05-10", null, "may-forecast"),
            event("TATN", "FORECAST", "2027-09-10", null, "sep-forecast")
        )
        assertEquals(
            setOf("jan-confirmed", "may-forecast", "sep-forecast"),
            removeSupersededDividendForecasts(events).map { it.sourceKey }.toSet()
        )
    }

    @Test
    fun paymentDateNeverSuppressesAnIndependentRecordDateForecast() {
        val events = listOf(
            event("TATN", "FORECAST", "2027-01-10", null, "jan-forecast"),
            event("TATN", "RECOMMENDED", "2026-12-20", "2027-01-19", "dec-record-jan-payment")
        )
        assertEquals(2, removeSupersededDividendForecasts(events).size)
        assertEquals(
            false,
            hasProviderDividendOnRecordDate("TATN", LocalDate.parse("2027-01-10"), events)
        )
    }

    @Test
    fun sameMonthDifferentRecordDatesRemainSeparateEvenWhenAmountsMatch() {
        val events = listOf(
            event("TATN", "FORECAST", "2027-01-10", null, "forecast"),
            event("TATN", "RECOMMENDED", "2027-01-19", null, "confirmed", amount = 20.00)
        )
        assertEquals(2, removeSupersededDividendForecasts(events).size)
    }

    @Test
    fun sameMonthAndSameAmountForecastIsReplacedWhenRecordDatesDiffer() {
        val events = listOf(
            event("TATN", "FORECAST", "2027-01-10", null, "jan-forecast"),
            event("TATN", "RECOMMENDED", "2027-01-19", "2027-02-01", "jan-recommended")
        )

        assertEquals(
            listOf("jan-recommended"),
            removeSupersededDividendForecasts(events).map { it.sourceKey }
        )
    }

    @Test
    fun paymentDateOnlyRecommendationReplacesSameMonthSameAmountForecast() {
        val events = listOf(
            event("TATN", "FORECAST", "2027-01-10", null, "jan-forecast"),
            event("TATN", "RECOMMENDED", null, "2027-01-19", "jan-recommended")
        )

        assertEquals(
            listOf("jan-recommended"),
            removeSupersededDividendForecasts(events).map { it.sourceKey }
        )
        assertEquals(
            true,
            hasProviderDividendOnRecordDate(
                "TATN", LocalDate.parse("2027-01-10"), events, amountPerShare = 31.44
            )
        )
    }

    @Test
    fun distinctExplicitRecordDatesRemainSeparateWithSameMonthAndAmount() {
        val events = listOf(
            event("TATN", "FORECAST", "2027-01-10", null, "jan-forecast"),
            event("TATN", "RECOMMENDED", "2027-01-19", "2027-01-25", "jan-recommended")
        )

        assertEquals(2, removeSupersededDividendForecasts(events).size)
    }

    @Test
    fun oneConfirmedEventCanReplaceOnlyOneMatchingForecast() {
        val events = listOf(
            event("TATN", "FORECAST", "2027-01-10", null, "forecast-a"),
            event("TATN", "FORECAST", "2027-01-12", null, "forecast-b"),
            event("TATN", "RECOMMENDED", "2027-01-19", null, "confirmed")
        )

        assertEquals(
            setOf("forecast-b", "confirmed"),
            removeSupersededDividendForecasts(events).map { it.sourceKey }.toSet()
        )
    }

    @Test
    fun differentTickersAndIndependentConfirmedEventsArePreserved() {
        val events = listOf(
            event("TATN", "FORECAST", "2027-01-10", null, "tatn-forecast"),
            event("SBER", "RECOMMENDED", "2027-01-10", null, "sber-confirmed"),
            event("TATN", "RECOMMENDED", "2027-05-10", null, "tatn-may-confirmed")
        )
        assertEquals(3, removeSupersededDividendForecasts(events).size)
    }

    @Test
    fun historyForecastIsSuppressedOnlyByExactConfirmedRecordDate() {
        val events = listOf(
            event("TATN", "RECOMMENDED", "2027-01-10", "2027-02-19", "jan-confirmed")
        )
        assertEquals(
            true,
            hasProviderDividendOnRecordDate("TATN", LocalDate.parse("2027-01-10"), events)
        )
        assertEquals(
            false,
            hasProviderDividendOnRecordDate("TATN", LocalDate.parse("2027-02-10"), events)
        )
        assertEquals(
            true,
            hasProviderDividendOnRecordDate(
                "TATN", LocalDate.parse("2027-01-10"), events, amountPerShare = 31.44
            )
        )
        assertEquals(
            true,
            hasProviderDividendOnRecordDate(
                "TATN", LocalDate.parse("2027-01-10"), events, amountPerShare = 20.0
            )
        )
    }

    @Test
    fun quarterlyIssuerKeepsThreeIndependentForecastPeriods() {
        val events = listOf(
            event("ISSUER", "FORECAST", "2027-01-10", null, "jan"),
            event("ISSUER", "FORECAST", "2027-05-10", null, "may"),
            event("ISSUER", "FORECAST", "2027-09-10", null, "sep"),
            event("ISSUER", "RECOMMENDED", "2027-01-10", "2027-01-25", "jan-confirmed")
        )
        val result = removeSupersededDividendForecasts(events)
        assertEquals(
            setOf("jan-confirmed", "may", "sep"),
            result.map { it.sourceKey }.toSet()
        )
    }
}
