package ru.mycapital.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Collapse duplicate representations of the same issuer event even when
 * different feeds assign different source keys. Distinct record dates remain
 * distinct, so two real dividends in one month are not merged.
 */
private fun removeDuplicateDividendEvents(
    events: List<DividendCalendarEvent>
): List<DividendCalendarEvent> {
    fun eventDate(value: Long?): LocalDate? = value?.let {
        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
    }

    val result = mutableListOf<DividendCalendarEvent>()
    events.forEach { candidate ->
        val candidateRecordDate = eventDate(candidate.recordDate)
        val candidatePaymentDate = eventDate(candidate.paymentDate)
        val duplicateIndex = result.indexOfFirst { existing ->
            if (!existing.ticker.equals(candidate.ticker, ignoreCase = true)) return@indexOfFirst false
            if (!existing.amountPerUnit.isFinite() || !candidate.amountPerUnit.isFinite() ||
                kotlin.math.abs(existing.amountPerUnit - candidate.amountPerUnit) > 0.01
            ) return@indexOfFirst false

            val existingRecordDate = eventDate(existing.recordDate)
            val existingPaymentDate = eventDate(existing.paymentDate)
            when {
                existingRecordDate != null && candidateRecordDate != null ->
                    existingRecordDate == candidateRecordDate
                else -> existingPaymentDate != null && candidatePaymentDate != null &&
                    existingPaymentDate == candidatePaymentDate
            }
        }

        if (duplicateIndex < 0) {
            result += candidate
        } else {
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
 * A provider recommendation may carry a slightly different record date from
 * the earlier forecast for the same dividend. Match those rows by issuer,
 * calendar month and per-share amount, consuming each confirmed event once.
 * This prevents one confirmed event from deleting multiple independent forecasts.
 */
internal fun removeSupersededDividendForecasts(
    events: List<DividendCalendarEvent>
): List<DividendCalendarEvent> {
    fun recordDate(value: Long?): LocalDate? = value?.let {
        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
    }

    val normalizedEvents = removeDuplicateDividendEvents(events)
    val confirmed = normalizedEvents.withIndex().filter {
        it.value.status.equals("ANNOUNCED", true) || it.value.status.equals("RECOMMENDED", true)
    }
    val consumedConfirmed = mutableSetOf<Int>()

    return normalizedEvents.filterNot { forecast ->
        if (!forecast.status.equals("FORECAST", true)) return@filterNot false
        val forecastDate = recordDate(forecast.recordDate) ?: return@filterNot false
        val match = confirmed.firstOrNull { indexed ->
            if (indexed.index in consumedConfirmed) return@firstOrNull false
            val announcement = indexed.value
            if (!forecast.ticker.equals(announcement.ticker, ignoreCase = true)) return@firstOrNull false
            val confirmedDate = recordDate(announcement.recordDate) ?: return@firstOrNull false
            val sameRecordDate = confirmedDate == forecastDate
            val sameMonthAndAmount = YearMonth.from(confirmedDate) == YearMonth.from(forecastDate) &&
                forecast.amountPerUnit.isFinite() && announcement.amountPerUnit.isFinite() &&
                kotlin.math.abs(forecast.amountPerUnit - announcement.amountPerUnit) <= 0.01
            sameRecordDate || sameMonthAndAmount
        }
        if (match == null) false else {
            consumedConfirmed += match.index
            true
        }
    }
}

/**
 * Check whether a provider-confirmed event replaces a history forecast.
 * Exact record dates always match; different dates in the same month match
 * only when the estimated per-share amount is also supplied and agrees.
 */
internal fun hasProviderDividendOnRecordDate(
    ticker: String,
    forecastDate: LocalDate,
    events: List<DividendCalendarEvent>,
    amountPerShare: Double? = null
): Boolean = events.asSequence()
    .filter { it.ticker.equals(ticker, ignoreCase = true) }
    .filter { it.status.equals("ANNOUNCED", true) || it.status.equals("RECOMMENDED", true) }
    .mapNotNull { event ->
        val date = event.recordDate?.let {
            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
        } ?: return@mapNotNull null
        date to event.amountPerUnit
    }
    .any { (confirmedDate, confirmedAmount) ->
        confirmedDate == forecastDate ||
            (amountPerShare != null &&
                YearMonth.from(confirmedDate) == YearMonth.from(forecastDate) &&
                amountPerShare.isFinite() && confirmedAmount.isFinite() &&
                kotlin.math.abs(amountPerShare - confirmedAmount) <= 0.01)
    }
