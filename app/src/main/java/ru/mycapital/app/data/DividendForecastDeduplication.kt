package ru.mycapital.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

private fun removeDuplicateDividendEvents(events: List<DividendCalendarEvent>): List<DividendCalendarEvent> {
    fun eventDate(value: Long?): LocalDate? = value?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    val result = mutableListOf<DividendCalendarEvent>()
    events.forEach { candidate ->
        val candidateRecordDate = eventDate(candidate.recordDate)
        val candidatePaymentDate = eventDate(candidate.paymentDate)
        val duplicateIndex = result.indexOfFirst { existing ->
            if (!existing.ticker.equals(candidate.ticker, ignoreCase = true)) return@indexOfFirst false
            val existingRecordDate = eventDate(existing.recordDate)
            val existingPaymentDate = eventDate(existing.paymentDate)
            val existingExpectedDate = existingPaymentDate ?: existingRecordDate
            val candidateExpectedDate = candidatePaymentDate ?: candidateRecordDate
            // Historical estimates are limited to one event per ticker/month.
            // Two different model dates/amounts in the same month are not two
            // independently confirmed issuer distributions.
            val bothForecasts = existing.status.equals("FORECAST", true) &&
                candidate.status.equals("FORECAST", true)
            if (bothForecasts && existingExpectedDate != null && candidateExpectedDate != null &&
                YearMonth.from(existingExpectedDate) == YearMonth.from(candidateExpectedDate)
            ) return@indexOfFirst true
            if (!existing.amountPerUnit.isFinite() || !candidate.amountPerUnit.isFinite() ||
                kotlin.math.abs(existing.amountPerUnit - candidate.amountPerUnit) > 0.01) return@indexOfFirst false
            // The calendar is grouped by expected cash-payment date, so compare
            // payment dates first. Record dates are only a fallback when a source
            // does not publish a payment date.
            when {
                existingPaymentDate != null && candidatePaymentDate != null -> existingPaymentDate == candidatePaymentDate
                existingRecordDate != null && candidateRecordDate != null -> existingRecordDate == candidateRecordDate
                else -> false
            }
        }
        if (duplicateIndex < 0) result += candidate
        else {
            val existing = result[duplicateIndex]
            val candidatePriority = ru.mycapital.app.dividendStatusPriority(candidate.status)
            val existingPriority = ru.mycapital.app.dividendStatusPriority(existing.status)
            val preferred = when {
                candidatePriority < existingPriority -> candidate
                candidatePriority > existingPriority -> existing
                (candidate.recordDate != null).compareTo(existing.recordDate != null) > 0 -> candidate
                (candidate.paymentDate != null).compareTo(existing.paymentDate != null) > 0 -> candidate
                else -> existing
            }
            val other = if (preferred === candidate) existing else candidate
            result[duplicateIndex] = preferred.copy(
                recordDate = preferred.recordDate ?: other.recordDate,
                paymentDate = preferred.paymentDate ?: other.paymentDate
            )
        }
    }
    return result
}

/**
 * A history forecast is an estimate, not a separate issuer declaration.
 * Replace it with a confirmed event for the same ticker/month/amount even
 * when estimated and official registry dates differ. Separate confirmed
 * events are never collapsed here: multiple real payouts in one month are valid.
 */
internal fun removeSupersededDividendForecasts(events: List<DividendCalendarEvent>): List<DividendCalendarEvent> {
    fun eventDate(value: Long?): LocalDate? = value?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    val normalizedEvents = removeDuplicateDividendEvents(events)
    val confirmed = normalizedEvents.withIndex().filter {
        it.value.status.equals("ANNOUNCED", true) || it.value.status.equals("RECOMMENDED", true)
    }
    val consumedConfirmed = mutableSetOf<Int>()
    return normalizedEvents.filterNot { forecast ->
        if (!forecast.status.equals("FORECAST", true)) return@filterNot false
        val forecastDate = eventDate(forecast.paymentDate) ?: eventDate(forecast.recordDate) ?: return@filterNot false
        val match = confirmed.firstOrNull { indexed ->
            if (indexed.index in consumedConfirmed) return@firstOrNull false
            val announcement = indexed.value
            if (!forecast.ticker.equals(announcement.ticker, ignoreCase = true)) return@firstOrNull false
            val confirmedDate = eventDate(announcement.paymentDate) ?: eventDate(announcement.recordDate)
                ?: return@firstOrNull false
            // An issuer-confirmed event in the same expected month supersedes
            // one model estimate even when the estimate amount or registry date
            // differs. Pair one-to-one so two separately announced payouts in
            // the same month can still replace two independent estimates.
            val sameMonth = YearMonth.from(confirmedDate) == YearMonth.from(forecastDate)
            sameMonth
        }
        if (match == null) false else { consumedConfirmed += match.index; true }
    }
}

/**
 * Suppress one history estimate for each provider event in the same expected
 * month. Provider forecasts and history forecasts are two estimates of the same
 * expected cash flow, not two separate payouts. One-to-one matching preserves
 * multiple independent same-month events when the provider actually supplies them.
 */
internal fun removeHistoryForecastsSupersededByProvider(
    ticker: String,
    forecasts: List<HistoricalDividendForecast>,
    events: List<DividendCalendarEvent>
): List<HistoricalDividendForecast> {
    val confirmed = events.withIndex().filter { indexed ->
        indexed.value.ticker.equals(ticker, ignoreCase = true) &&
            (indexed.value.status.equals("ANNOUNCED", true) ||
                indexed.value.status.equals("RECOMMENDED", true) ||
                indexed.value.status.equals("FORECAST", true))
    }
    val consumed = mutableSetOf<Int>()
    return forecasts.filterNot { forecast ->
        val match = confirmed.firstOrNull { indexed ->
            if (indexed.index in consumed) return@firstOrNull false
            val event = indexed.value
            val confirmedDates = listOfNotNull(
                eventDateForDividendDedup(event.recordDate),
                eventDateForDividendDedup(event.paymentDate)
            )
            if (confirmedDates.isEmpty()) return@firstOrNull false
            confirmedDates.any { confirmedDate ->
                val sameAmount = event.amountPerUnit.isFinite() &&
                    kotlin.math.abs(event.amountPerUnit - forecast.amountPerShare) <= 0.01
                val closeDate = kotlin.math.abs(
                    java.time.temporal.ChronoUnit.DAYS.between(confirmedDate, forecast.recordDate)
                ) <= 21L
                val sameMonth = YearMonth.from(confirmedDate) == YearMonth.from(forecast.recordDate)
                // Match one forecast to one issuer event by expected month, amount,
                // or nearby date. One-to-one consumption preserves separate payouts.
                sameMonth || sameAmount || closeDate
            }
        }
        if (match == null) false else {
            consumed += match.index
            true
        }
    }
}

private fun eventDateForDividendDedup(value: Long?): LocalDate? =
    value?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }

/** True when an issuer-confirmed payout supersedes a history estimate. */
internal fun hasProviderDividendOnRecordDate(
    ticker: String, forecastDate: LocalDate, events: List<DividendCalendarEvent>, amountPerShare: Double? = null
): Boolean = events.asSequence()
    .filter { it.ticker.equals(ticker, ignoreCase = true) }
    .filter { it.status.equals("ANNOUNCED", true) || it.status.equals("RECOMMENDED", true) }
    .mapNotNull { event ->
        val dateMillis = event.paymentDate ?: event.recordDate ?: return@mapNotNull null
        val date = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
        Pair(date, event.amountPerUnit)
    }
    .any { (confirmedDate, confirmedAmount) ->
        val sameMonth = YearMonth.from(confirmedDate) == YearMonth.from(forecastDate)
        val sameAmount = amountPerShare != null && amountPerShare.isFinite() &&
            confirmedAmount.isFinite() && kotlin.math.abs(amountPerShare - confirmedAmount) <= 0.01
        (confirmedDate == forecastDate) || (sameMonth && sameAmount)
    }
