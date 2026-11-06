package ru.mycapital.app.data

import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Applies issuer-confirmed dividend announcements that may arrive later than
 * third-party forecast feeds. X5's 204 RUB recommendation for 9M 2026 was
 * published by the issuer on 2026-11-06 with record date 2026-12-11.
 *
 * Long/Short's 98 RUB forecast for 2027-01-06 represented the same expected
 * interim distribution and must not be added on top of the official amount.
 */
internal fun mergeOfficialDividendAnnouncements(
    events: List<DividendCalendarEvent>,
    today: LocalDate
): List<DividendCalendarEvent> {
    val x5SupersededForecastDate = LocalDate.of(2027, 1, 6)
    val filtered = events.filterNot { event ->
        event.ticker.equals("X5", true) &&
            event.status.equals("FORECAST", true) &&
            event.recordDate?.let {
                java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
            } == x5SupersededForecastDate
    }.toMutableList()

    val x5RecordDate = LocalDate.of(2026, 12, 11)
    if (!today.isAfter(x5RecordDate)) {
        filtered.removeAll { event ->
            event.ticker.equals("X5", true) &&
                event.recordDate?.let {
                    java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                } == x5RecordDate
        }
        filtered += DividendCalendarEvent(
            ticker = "X5",
            amountPerUnit = 204.0,
            status = "RECOMMENDED",
            recordDate = x5RecordDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            paymentDate = null,
            sourceKey = "X5:OFFICIAL:2026-11-06:9M-2026"
        )
    }

    return filtered.distinctBy { it.sourceKey }
}
