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
            if (!existing.amountPerUnit.isFinite() || !candidate.amountPerUnit.isFinite() ||
                kotlin.math.abs(existing.amountPerUnit - candidate.amountPerUnit) > 0.01) return@indexOfFirst false
            val existingRecordDate = eventDate(existing.recordDate)
            val existingPaymentDate = eventDate(existing.paymentDate)
            when {
                existingRecordDate != null && candidateRecordDate != null -> existingRecordDate == candidateRecordDate
                else -> existingPaymentDate != null && candidatePaymentDate != null && existingPaymentDate == candidatePaymentDate
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
        val forecastDate = eventDate(forecast.recordDate) ?: eventDate(forecast.paymentDate) ?: return@filterNot false
        val match = confirmed.firstOrNull { indexed ->
            if (indexed.index in consumedConfirmed) return@firstOrNull false
            val announcement = indexed.value
            if (!forecast.ticker.equals(announcement.ticker, ignoreCase = true)) return@firstOrNull false
            val confirmedDate = eventDate(announcement.recordDate) ?: eventDate(announcement.paymentDate)
                ?: return@firstOrNull false
            val sameMonth = YearMonth.from(confirmedDate) == YearMonth.from(forecastDate)
            val sameAmount = forecast.amountPerUnit.isFinite() && announcement.amountPerUnit.isFinite() &&
                kotlin.math.abs(forecast.amountPerUnit - announcement.amountPerUnit) <= 0.01
            sameMonth && sameAmount
        }
        if (match == null) false else { consumedConfirmed += match.index; true }
    }
}

/** True when an issuer-confirmed payout supersedes a history estimate. */
internal fun hasProviderDividendOnRecordDate(
    ticker: String, forecastDate: LocalDate, events: List<DividendCalendarEvent>, amountPerShare: Double? = null
): Boolean = events.asSequence()
    .filter { it.ticker.equals(ticker, ignoreCase = true) }
    .filter { it.status.equals("ANNOUNCED", true) || it.status.equals("RECOMMENDED", true) }
    .mapNotNull { event ->
        val dateMillis = event.recordDate ?: event.paymentDate ?: return@mapNotNull null
        val date = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
        Triple(date, event.amountPerUnit)
    }
    .any { (confirmedDate, confirmedAmount) ->
        val sameMonth = YearMonth.from(confirmedDate) == YearMonth.from(forecastDate)
        val sameAmount = amountPerShare != null && amountPerShare.isFinite() &&
            confirmedAmount.isFinite() && kotlin.math.abs(amountPerShare - confirmedAmount) <= 0.01
        (confirmedDate == forecastDate) || (sameMonth && sameAmount)
    }
