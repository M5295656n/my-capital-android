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

    @Test fun sameMonthIssuerEventReplacesForecastEvenWhenAmountAndDateDiffer() {
        val events = listOf(
            event("SBER", "FORECAST", "2027-07-02", null, "sber-forecast", 44.68),
            event("SBER", "ANNOUNCED", "2027-07-20", null, "sber-announced", 37.64)
        )
        assertEquals(listOf("sber-announced"), removeSupersededDividendForecasts(events).map { it.sourceKey })
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

    @Test fun sameMonthProviderEventSupersedesHistoryForecastDespiteDifferentAmount() {
        val forecasts = listOf(
            HistoricalDividendForecast("MOEX", 20.44, LocalDate.parse("2027-07-09"), "moex-history-forecast")
        )
        val events = listOf(
            event("MOEX", "ANNOUNCED", "2027-07-20", null, "moex-provider-event", 19.57)
        )

        val remaining = removeHistoryForecastsSupersededByProvider("MOEX", forecasts, events)

        assertEquals(0, remaining.size)
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

    @Test
    fun providerForecastSupersedesHistoryForecastForSameTickerAndMonth() {
        val historyForecasts = listOf(
            HistoricalDividendForecast("YDEX", 35.0, LocalDate.parse("2027-04-12"), "history-yandex")
        )
        val providerForecasts = listOf(
            event("YDEX", "FORECAST", "2027-04-25", "2027-04-30", "longshort-yandex", 40.0)
        )

        val remaining = removeHistoryForecastsSupersededByProvider(
            "YDEX", historyForecasts, providerForecasts
        )

        assertEquals(0, remaining.size)
    }

    @Test
    fun providerForecastAndHistoryForecastDoNotDoubleSevenKnownIssuerMonthCases() {
        val cases = listOf(
            "NVTK" to "2027-04-12",
            "YDEX" to "2027-04-18",
            "IRAO" to "2027-06-15",
            "MOEX" to "2027-07-10",
            "MDMG" to "2027-07-14",
            "SBER" to "2027-07-20",
            "MDMG" to "2027-10-19"
        )

        cases.forEachIndexed { index, (ticker, date) ->
            val historyForecast = HistoricalDividendForecast(
                ticker, 10.0, LocalDate.parse(date), "history-$index"
            )
            val providerForecast = event(
                ticker, "FORECAST", date, null, "longshort-$index", 12.0
            )

            val remaining = removeHistoryForecastsSupersededByProvider(
                ticker, listOf(historyForecast), listOf(providerForecast)
            )

            assertEquals("$ticker $date must not appear twice from two forecast sources", 0, remaining.size)
        }
    }

    @Test
    fun providerForecastMatchingIsOneToOneForTwoIndependentSameMonthEvents() {
        val historyForecasts = listOf(
            HistoricalDividendForecast("TATN", 31.44, LocalDate.parse("2027-01-10"), "history-a"),
            HistoricalDividendForecast("TATN", 20.00, LocalDate.parse("2027-01-25"), "history-b")
        )
        val providerForecasts = listOf(
            event("TATN", "FORECAST", "2027-01-11", null, "provider-a", 31.44)
        )

        val remaining = removeHistoryForecastsSupersededByProvider(
            "TATN", historyForecasts, providerForecasts
        )

        assertEquals(listOf("history-b"), remaining.map { it.sourceKey })
    }
}
