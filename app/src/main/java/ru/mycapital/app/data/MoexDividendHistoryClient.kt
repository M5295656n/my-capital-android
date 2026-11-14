package ru.mycapital.app.data

import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal data class HistoricalDividend(
    val secid: String,
    val recordDate: LocalDate,
    val amountPerShare: Double,
    val currency: String
)

internal data class HistoricalSplit(
    val secid: String,
    val tradedate: LocalDate,
    val before: Double,
    val after: Double
) {
    val shareMultiplier: Double get() = after / before
}

/**
 * Convert pre-split dividends to today's share units. MOEX split ratio is
 * after / before; an old per-share amount is divided by every later ratio.
 */
internal fun adjustHistoricalDividendsForSplits(
    dividends: List<HistoricalDividend>,
    splits: List<HistoricalSplit>
): List<HistoricalDividend> = dividends.map { dividend ->
    val laterMultiplier = splits.asSequence()
        .filter { it.secid.equals(dividend.secid, ignoreCase = true) }
        .filter { it.tradedate.isAfter(dividend.recordDate) }
        .map { it.shareMultiplier }
        .filter { it.isFinite() && it > 0.0 }
        .fold(1.0) { total, multiplier -> total * multiplier }
    if (laterMultiplier == 1.0) dividend
    else dividend.copy(amountPerShare = dividend.amountPerShare / laterMultiplier)
}

internal data class DividendHistoryMetrics(
    val fiveYearGrowthPercent: Double?,
    val historyCount: Int
)

internal data class HistoricalDividendForecast(
    val ticker: String,
    val amountPerShare: Double,
    val recordDate: LocalDate,
    val sourceKey: String
)

/**
 * Estimate recurring dividend months from MOEX history only.
 *
 * A month is forecast only when it appeared in at least two different years
 * during the last five years. The amount and day are medians of up to the
 * three most recent observations for that month. The result is an estimated
 * record date, not an announced payment date.
 */
internal fun calculateHistoricalDividendForecasts(
    ticker: String,
    dividends: List<HistoricalDividend>,
    today: LocalDate,
    horizonMonths: Int = 24,
    futureAnchorDates: List<LocalDate> = emptyList()
): List<HistoricalDividendForecast> {
    if (horizonMonths <= 0) return emptyList()
    val normalizedTicker = ticker.trim().uppercase(Locale.ROOT)
    if (normalizedTicker.isBlank()) return emptyList()

    val historyStart = today.minusYears(5)
    val usable = dividends.filter {
        it.secid.equals(normalizedTicker, ignoreCase = true) &&
            it.currency.equals("RUB", ignoreCase = true) &&
            it.amountPerShare.isFinite() && it.amountPerShare > 0.0 &&
            !it.recordDate.isBefore(historyStart) && !it.recordDate.isAfter(today)
    }.distinctBy { "${it.recordDate}:${"%.6f".format(Locale.ROOT, it.amountPerShare)}" }

    val recurringByMonth = usable.groupBy { it.recordDate.monthValue }
        .filterValues { rows -> rows.map { it.recordDate.year }.distinct().size >= 2 }
    if (recurringByMonth.isEmpty()) return emptyList()

    fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else sorted[middle]
    }

    val startMonth = java.time.YearMonth.from(today)
    val endMonth = startMonth.plusMonths(horizonMonths.toLong())
    val forecasts = mutableListOf<HistoricalDividendForecast>()
    var month = startMonth
    while (!month.isAfter(endMonth)) {
        val observations = recurringByMonth[month.monthValue]
        if (observations != null) {
            val recent = observations.sortedBy { it.recordDate }.takeLast(3)
            val estimatedDay = median(recent.map { it.recordDate.dayOfMonth.toDouble() })
                .let { Math.round(it).toInt() }.coerceIn(1, month.lengthOfMonth())
            val date = month.atDay(estimatedDay)
            if (!date.isBefore(today)) {
                val amount = median(recent.map { it.amountPerShare })
                if (amount.isFinite() && amount > 0.0) {
                    forecasts += HistoricalDividendForecast(
                        ticker = normalizedTicker,
                        amountPerShare = amount,
                        recordDate = date,
                        sourceKey = "MYCAPITAL_HISTORY:$normalizedTicker:$date"
                    )
                }
            }
        }
        month = month.plusMonths(1)
    }
    // Quarterly issuers may not repeat the same record month in two separate
    // years. When recent observations show a stable 2–5 month cadence, project
    // it from the latest confirmed upcoming event or latest historical record.
    val cadenceRows = usable
        .filter { !it.recordDate.isBefore(today.minusMonths(18)) }
        .sortedBy { it.recordDate }
    if (cadenceRows.size >= 3) {
        val intervals = cadenceRows.zipWithNext { first, second ->
            java.time.temporal.ChronoUnit.DAYS.between(first.recordDate, second.recordDate).toDouble()
        }.filter { it > 0.0 }
        if (intervals.size >= 2) {
            val cadenceDays = median(intervals)
            if (cadenceDays in 60.0..150.0) {
                val anchor = (cadenceRows.map { it.recordDate } + futureAnchorDates.filter { !it.isBefore(today) })
                    .maxOrNull() ?: cadenceRows.last().recordDate
                val amount = median(cadenceRows.takeLast(3).map { it.amountPerShare })
                val cadenceForecasts = mutableListOf<HistoricalDividendForecast>()
                val stepDays = Math.round(cadenceDays).coerceAtLeast(1L)
                var date = anchor.plusDays(stepDays)
                while (!date.isAfter(endMonth.atEndOfMonth())) {
                    if (!date.isBefore(today) && amount.isFinite() && amount > 0.0) {
                        cadenceForecasts += HistoricalDividendForecast(
                            ticker = normalizedTicker,
                            amountPerShare = amount,
                            recordDate = date,
                            sourceKey = "MYCAPITAL_HISTORY:$normalizedTicker:$date"
                        )
                    }
                    date = date.plusDays(stepDays)
                }
                if (cadenceForecasts.isNotEmpty()) return cadenceForecasts
            }
        }
    }

    return forecasts
}

/** Forward dividend yield based on payout records already stored in the portfolio database. */
internal fun calculateForwardDividendYieldPercentFromPayouts(
    payouts: List<PayoutEntity>,
    ticker: String,
    currentPrice: Double,
    today: LocalDate
): Double? {
    if (!currentPrice.isFinite() || currentPrice <= 0.0) return null
    val endDate = today.plusDays(365)
    val amount = payouts.asSequence()
        .filter { it.ticker.equals(ticker, true) && it.payoutType.equals("Дивиденд", true) }
        .filter { it.status in setOf("ANNOUNCED", "RECOMMENDED", "FORECAST") && it.actualDate == null }
        .mapNotNull { payout ->
            val timestamp = payout.expectedDate ?: payout.recordDate ?: return@mapNotNull null
            val date = java.time.Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.of("Europe/Moscow")).toLocalDate()
            if (!date.isBefore(today) && !date.isAfter(endDate)) {
                (payout.sourceKey ?: "${payout.ticker}:${timestamp}") to payout.amountPerUnit
            } else null
        }
        .distinctBy { it.first }
        .sumOf { it.second }
    return amount.takeIf { it > 0.0 }?.div(currentPrice)?.times(100.0)
}

internal data class DividendHistoryFetchResult(
    val dividends: List<HistoricalDividend>,
    // True when the direct security history endpoint loaded successfully.
    // In that case, a year with no dividend records is a verified zero, not missing data.
    val annualCoverageVerified: Boolean
)

internal fun calculateForwardDividendYieldPercent(
    events: List<DividendCalendarEvent>,
    ticker: String,
    currentPrice: Double,
    today: LocalDate
): Double? {
    if (!currentPrice.isFinite() || currentPrice <= 0.0) return null
    val endDate = today.plusDays(365)
    val amount = events.asSequence()
        .filter { it.ticker.equals(ticker, true) }
        .filter { it.status in setOf("ANNOUNCED", "RECOMMENDED", "FORECAST") }
        .mapNotNull { event ->
            val timestamp = event.recordDate ?: event.paymentDate ?: return@mapNotNull null
            val date = java.time.Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.of("Europe/Moscow")).toLocalDate()
            if (!date.isBefore(today) && !date.isAfter(endDate)) event.sourceKey to event.amountPerUnit else null
        }
        .distinctBy { it.first }
        .sumOf { it.second }
    return amount.takeIf { it > 0.0 }?.div(currentPrice)?.times(100.0)
}

internal fun calculateDividendHistoryMetrics(
    dividends: List<HistoricalDividend>,
    currentPrice: Double,
    today: LocalDate,
    annualCoverageVerified: Boolean = false
): DividendHistoryMetrics {
    val rubleDividends = dividends.filter {
        it.currency.equals("RUB", true) && it.amountPerShare > 0.0 &&
            it.amountPerShare.isFinite() && !it.recordDate.isAfter(today)
    }.distinctBy { "${it.recordDate}:${"%.6f".format(Locale.ROOT, it.amountPerShare)}" }
    // Use the latest five fully completed calendar years. The current year is
    // partial and must not distort the annual payout comparison.
    // A direct ticker dividend-history response is sufficient for this calculation;
    // the optional cross-issue ISIN completeness check is not a prerequisite.
    val lastYear = today.year - 1
    val firstYear = lastYear - 5
    val annual = rubleDividends.groupBy { it.recordDate.year }.mapValues { (_, rows) ->
        rows.sumOf { it.amountPerShare }
    }
    val first = annual[firstYear]
    val last = annual[lastYear]
    val growth = if (first != null && last != null && first > 0.0 && last > 0.0) {
        (Math.pow(last / first, 1.0 / (lastYear - firstYear)) - 1.0) * 100.0
    } else null
    return DividendHistoryMetrics(growth, rubleDividends.size)
}

internal class MoexDividendHistoryClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    fun getHistory(ticker: String): DividendHistoryFetchResult {
        val normalized = ticker.trim().uppercase(Locale.ROOT)
        require(normalized.matches(Regex("[A-Z0-9_]+"))) { "Некорректный тикер: $ticker" }

        // Resolve the canonical ISIN from the direct security description first.
        // The broad securities search endpoint is supplementary: it can omit the
        // exact ticker row or fail transiently, and must never block direct history.
        val description = runCatching {
            requestJson("https://iss.moex.com/iss/securities/$normalized.json?iss.meta=off")
        }.getOrNull()
        val descriptionIsin = table(description ?: JSONObject(), "description")
            .firstNotNullOfOrNull { row ->
                if (row.optString("name").equals("ISIN", ignoreCase = true)) {
                    row.optString("value").takeIf { it.isNotBlank() && it != "null" }
                } else null
            }

        val tickerSearchIsin = runCatching {
            val payload = requestJson(
                "https://iss.moex.com/iss/securities.json?q=$normalized&iss.meta=off"
            )
            table(payload, "securities").firstOrNull {
                it.optString("secid").equals(normalized, ignoreCase = true)
            }?.optString("isin")?.takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()
        val isin = descriptionIsin ?: tickerSearchIsin

        val relatedSecids = linkedSetOf(normalized)
        if (isin != null) {
            runCatching {
                val relatedSearch = requestJson(
                    "https://iss.moex.com/iss/securities.json?q=$isin&iss.meta=off"
                )
                table(relatedSearch, "securities")
                    .filter { it.optString("isin").equals(isin, ignoreCase = true) }
                    .mapNotNull { it.optString("secid").takeIf(String::isNotBlank) }
                    .forEach { relatedSecids.add(it) }
            }
        }

        var annualCoverageVerified = true
        val result = mutableListOf<HistoricalDividend>()
        var successfulHistoryEndpoints = 0
        var lastHistoryError: Exception? = null
        relatedSecids.forEach { secid ->
            try {
                val payload = requestJson(
                    "https://iss.moex.com/iss/securities/$secid/dividends.json?iss.meta=off"
                )
                successfulHistoryEndpoints++
                table(payload, "dividends").forEach rowLoop@{ row ->
                    val dateText = row.optString("registryclosedate")
                        .takeIf { it.isNotBlank() && it != "null" }
                        ?: row.optString("recorddate").takeIf { it.isNotBlank() && it != "null" }
                    val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: return@rowLoop
                    val amount = row.optDouble("value", Double.NaN)
                    val rawCurrency = row.optString("currencyid")
                    val currency = if (rawCurrency.isBlank() || rawCurrency == "null") "RUB" else rawCurrency
                    if (amount.isFinite() && amount > 0.0) {
                        result += HistoricalDividend(secid, date, amount, currency)
                    }
                }
            } catch (error: Exception) {
                annualCoverageVerified = false
                lastHistoryError = error
            }
        }
        // A retired SECID can legitimately stop serving history while the current
        // SECID remains available. Keep usable partial history; fail only when none
        // of the candidate security IDs could be queried.
        if (successfulHistoryEndpoints == 0) {
            throw IOException(
                "Не удалось получить историю дивидендов MOEX для $normalized: " +
                    (lastHistoryError?.message ?: "нет доступных SECID")
            )
        }

        // Split events can be attached to any historical SECID of the same issue.
        // Collect them across aliases, then apply the same issue-level split history
        // to each alias before normalizing rows to the portfolio's current ticker.
        val splitRows = relatedSecids.flatMap { secid ->
            runCatching {
                val payload = requestJson(
                    "https://iss.moex.com/iss/statistics/engines/stock/splits/$secid.json?iss.meta=off"
                )
                table(payload, "splits").mapNotNull { row ->
                    val splitDate = runCatching { LocalDate.parse(row.optString("tradedate")) }.getOrNull()
                        ?: return@mapNotNull null
                    val before = row.optDouble("before", Double.NaN)
                    val after = row.optDouble("after", Double.NaN)
                    if (!before.isFinite() || !after.isFinite() || before <= 0.0 || after <= 0.0) {
                        return@mapNotNull null
                    }
                    HistoricalSplit(secid, splitDate, before, after)
                }
            }.getOrElse { emptyList() }
        }
        val issueSplits = splitRows.flatMap { split ->
            relatedSecids.map { alias -> split.copy(secid = alias) }
        }
        val splitAdjusted = adjustHistoricalDividendsForSplits(
            result.distinctBy {
                "${it.secid}:${it.recordDate}:${"%.6f".format(Locale.ROOT, it.amountPerShare)}:${it.currency}"
            },
            issueSplits
        ).map { it.copy(secid = normalized) }
        return DividendHistoryFetchResult(
            splitAdjusted.distinctBy {
                "${it.recordDate}:${"%.6f".format(Locale.ROOT, it.amountPerShare)}:${it.currency}"
            }.sortedBy { it.recordDate },
            annualCoverageVerified
        )
    }

    private fun requestJson(url: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "MyCapital-Android/0.3.91")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("MOEX ISS вернул HTTP ${response.code}")
            return JSONObject(response.body?.string() ?: throw IOException("Пустой ответ MOEX ISS"))
        }
    }

    private fun table(payload: JSONObject, blockName: String): List<JSONObject> {
        val block = payload.optJSONObject(blockName) ?: return emptyList()
        val columns = block.optJSONArray("columns") ?: return emptyList()
        val rows = block.optJSONArray("data") ?: return emptyList()
        val indexes = (0 until columns.length()).associateBy { columns.optString(it).lowercase(Locale.ROOT) }
        return (0 until rows.length()).mapNotNull { index ->
            val values = rows.optJSONArray(index) ?: return@mapNotNull null
            JSONObject().also { obj ->
                indexes.forEach { (name, column) ->
                    if (column < values.length() && !values.isNull(column)) obj.put(name, values.get(column))
                }
            }
        }
    }
}
