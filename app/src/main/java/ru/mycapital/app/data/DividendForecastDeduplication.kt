package ru.mycapital.app.data

import java.time.Instant
import java.time.ZoneOffset

/**
 * Removes a provider FORECAST only when a more authoritative announcement
 * identifies the same ticker and dividend dates. Amount alone is never a key.
 *
 * Exact date equality is strongest. A cross-role record/payment-date match is
 * accepted only in the same calendar month and with the same per-share amount,
 * covering feeds that publish the estimated record date in one row and the
 * payment date in the confirmed row.
 */
internal fun removeSupersededDividendForecasts(
    events: List<DividendCalendarEvent>
): List<DividendCalendarEvent> {
    fun date(value: Long?): java.time.LocalDate? = value?.let {
        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
    }

    val confirmed = events.filter {
        it.status.equals("ANNOUNCED", true) || it.status.equals("RECOMMENDED", true)
    }
    return events.filterNot { forecast ->
        if (!forecast.status.equals("FORECAST", true)) return@filterNot false
        confirmed.any { announcement ->
            if (!forecast.ticker.equals(announcement.ticker, true)) return@any false
            val forecastRecord = date(forecast.recordDate)
            val forecastPayment = date(forecast.paymentDate)
            val announcedRecord = date(announcement.recordDate)
            val announcedPayment = date(announcement.paymentDate)
            val exactDateMatch = listOfNotNull(forecastRecord, forecastPayment)
                .any { candidate -> candidate == announcedRecord || candidate == announcedPayment }
            val sameMonthCrossRole =
                (forecastRecord != null && announcedPayment != null &&
                    YearMonth.from(forecastRecord) == YearMonth.from(announcedPayment)) ||
                (forecastPayment != null && announcedRecord != null &&
                    YearMonth.from(forecastPayment) == YearMonth.from(announcedRecord))
            exactDateMatch || (
                sameMonthCrossRole &&
                    kotlin.math.abs(forecast.amountPerUnit - announcement.amountPerUnit) < 0.01
                )
        }
    }
}

/** True when either the record date or payment date identifies this forecast month. */
internal fun hasProviderDividendInMonth(
    ticker: String,
    forecastDate: java.time.LocalDate,
    events: List<DividendCalendarEvent>
): Boolean {
    val forecastMonth = java.time.YearMonth.from(forecastDate)
    return events.asSequence()
        .filter { it.ticker.equals(ticker, ignoreCase = true) }
        .flatMap { sequenceOf(it.recordDate, it.paymentDate).filterNotNull() }
        .map { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        .any { java.time.YearMonth.from(it) == forecastMonth }
}
