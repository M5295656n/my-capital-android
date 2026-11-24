package ru.mycapital.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Removes a provider forecast only when it points to the same issuer record date
 * as a confirmed event. Payment dates are deliberately not compared with record
 * dates: they describe different stages of the same dividend lifecycle.
 *
 * Month-level matching and amount-only matching are unsafe for issuers that pay
 * dividends more than once per year, so independent events are always retained.
 */
internal fun removeSupersededDividendForecasts(
    events: List<DividendCalendarEvent>
): List<DividendCalendarEvent> {
    fun recordDate(value: Long?): LocalDate? = value?.let {
        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
    }

    val confirmed = events.filter {
        it.status.equals("ANNOUNCED", true) || it.status.equals("RECOMMENDED", true)
    }
    return events.filterNot { forecast ->
        if (!forecast.status.equals("FORECAST", true)) return@filterNot false
        val forecastDate = recordDate(forecast.recordDate) ?: return@filterNot false
        confirmed.any { announcement ->
            forecast.ticker.equals(announcement.ticker, ignoreCase = true) &&
                announcement.recordDate != null &&
                recordDate(announcement.recordDate) == forecastDate
        }
    }
}

/** Only a confirmed record date can replace a history forecast for that date. */
internal fun hasProviderDividendOnRecordDate(
    ticker: String,
    forecastDate: LocalDate,
    events: List<DividendCalendarEvent>
): Boolean = events.asSequence()
    .filter { it.ticker.equals(ticker, ignoreCase = true) }
    .filter { it.status.equals("ANNOUNCED", true) || it.status.equals("RECOMMENDED", true) }
    .mapNotNull { it.recordDate }
    .map { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    .any { it == forecastDate }
