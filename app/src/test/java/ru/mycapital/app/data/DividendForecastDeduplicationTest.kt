package ru.mycapital.app.data

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class DividendForecastDeduplicationTest {
    private fun millis(date: String) =
        LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun removesForecastWhenConfirmedEventHasSameRecordDateAndLaterPaymentDate() {
        val events = listOf(
            DividendCalendarEvent("TATN", 31.44, "FORECAST", millis("2027-01-10"), null, "forecast"),
            DividendCalendarEvent("TATN", 31.44, "RECOMMENDED", millis("2027-01-10"), millis("2027-01-19"), "recommended")
        )
        val result = removeSupersededDividendForecasts(events)
        assertEquals(listOf("recommended"), result.map { it.sourceKey })
    }

    @Test
    fun removesForecastWhenOnlyCrossRoleRecordAndPaymentDatesAreAvailable() {
        val events = listOf(
            DividendCalendarEvent("TATN", 31.44, "FORECAST", millis("2027-01-10"), null, "forecast"),
            DividendCalendarEvent("TATN", 31.44, "RECOMMENDED", null, millis("2027-01-19"), "recommended")
        )
        assertEquals(listOf("recommended"), removeSupersededDividendForecasts(events).map { it.sourceKey })
    }

    @Test
    fun doesNotRemoveDifferentTickerOrDifferentAmountForecast() {
        val events = listOf(
            DividendCalendarEvent("TATN", 31.44, "FORECAST", millis("2027-01-10"), null, "forecast-tatn"),
            DividendCalendarEvent("SBER", 31.44, "RECOMMENDED", null, millis("2027-01-19"), "other-ticker"),
            DividendCalendarEvent("TATN", 20.00, "RECOMMENDED", null, millis("2027-01-19"), "other-amount")
        )
        assertEquals(3, removeSupersededDividendForecasts(events).size)
    }

    @Test
    fun keepsSeparateConfirmedEventsAndForecastInAnotherMonth() {
        val events = listOf(
            DividendCalendarEvent("TATN", 31.44, "FORECAST", millis("2027-01-10"), null, "jan-forecast"),
            DividendCalendarEvent("TATN", 31.44, "RECOMMENDED", null, millis("2027-01-19"), "jan-confirmed"),
            DividendCalendarEvent("TATN", 12.00, "FORECAST", millis("2027-07-10"), null, "july-forecast")
        )
        assertEquals(setOf("jan-confirmed", "july-forecast"),
            removeSupersededDividendForecasts(events).map { it.sourceKey }.toSet())
    }
    @Test
    fun historyForecastIsSuppressedByPaymentMonthEvenWhenRecordDateIsInAnotherMonth() {
        val events = listOf(
            DividendCalendarEvent("TATN", 31.44, "RECOMMENDED",
                millis("2026-12-20"), millis("2027-01-19"), "recommended")
        )
        assertEquals(true, hasProviderDividendInMonth("TATN", LocalDate.parse("2027-01-10"), events))
        assertEquals(false, hasProviderDividendInMonth("TATN", LocalDate.parse("2027-02-10"), events))
    }

    @Test
    fun historyForecastIsSuppressedByRecordDateMonthWhenPaymentDateIsLater() {
        val events = listOf(
            DividendCalendarEvent("TATN", 31.44, "RECOMMENDED",
                millis("2027-01-10"), millis("2027-02-19"), "recommended")
        )
        assertEquals(true, hasProviderDividendInMonth("TATN", LocalDate.parse("2027-01-25"), events))
        assertEquals(true, hasProviderDividendInMonth("TATN", LocalDate.parse("2027-02-10"), events))
    }

}
