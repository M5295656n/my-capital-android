package ru.mycapital.app

import ru.mycapital.app.data.PayoutEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class MonthlyPayoutForecast(
    val month: YearMonth,
    val amount: Double,
    val paymentCount: Int
) {
    fun displayMonth(locale: Locale = Locale("ru", "RU")): String =
        month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

internal data class PayoutForecastSummary(
    val next30Days: Double,
    val next90Days: Double,
    val monthly: List<MonthlyPayoutForecast>
)

/** Forecasts only expected payouts with a known payment date; received and overdue events are excluded. */
internal fun calculatePayoutForecast(
    payouts: List<PayoutEntity>,
    today: LocalDate = LocalDate.now(),
    zoneId: ZoneId = ZoneId.of("UTC")
): PayoutForecastSummary {
    val datedExpected = payouts.mapNotNull { payout ->
        if (payout.status != "EXPECTED" || payout.expectedDate == null) return@mapNotNull null
        val date = Instant.ofEpochMilli(payout.expectedDate).atZone(zoneId).toLocalDate()
        if (date.isBefore(today)) return@mapNotNull null
        date to payout.totalAmount
    }

    val next30 = today.plusDays(30)
    val next90 = today.plusDays(90)
    val monthly = datedExpected
        .groupBy { YearMonth.from(it.first) }
        .toSortedMap()
        .map { (month, items) ->
            MonthlyPayoutForecast(month, items.sumOf { it.second }, items.size)
        }

    return PayoutForecastSummary(
        next30Days = datedExpected.filter { !it.first.isAfter(next30) }.sumOf { it.second },
        next90Days = datedExpected.filter { !it.first.isAfter(next90) }.sumOf { it.second },
        monthly = monthly
    )
}
