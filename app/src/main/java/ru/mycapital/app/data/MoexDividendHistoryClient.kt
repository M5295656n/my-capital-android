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
 * Estimate regular dividend events from the issuer's real MOEX payment history.
 *
 * Calendar-month recurrence is deliberately not used: a month-of-year match
 * can invent extra payments and cannot establish the issuer's payment frequency.
 * We infer a recurring interval from actual consecutive record dates instead.
 * Monthly/adjacent-month patterns are rejected; supported schedules are
 * quarterly (75-149 days), semiannual (150-239 days), or annual (300-430 days).
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
    val rawHistory = dividends.filter {
        it.secid.equals(normalizedTicker, ignoreCase = true) &&
            it.currency.equals("RUB", ignoreCase = true) &&
            it.amountPerShare.isFinite() && it.amountPerShare > 0.0 &&
            !it.recordDate.isBefore(historyStart) && !it.recordDate.isAfter(today)
    }.distinctBy {
        "${it.recordDate}:${"%.6f".format(Locale.ROOT, it.amountPerShare)}"
    }

    // More than one distinct event in a calendar month is contradictory for
    // this forecast model. Exclude that month rather than creating two payouts.
    val usable = rawHistory.groupBy { java.time.YearMonth.from(it.recordDate) }
        .values
        .filter { it.map(HistoricalDividend::recordDate).distinct().size == 1 }
        .map { rows ->
            val representative = rows.first()
            representative.copy(amountPerShare = rows.map { it.amountPerShare }.average())
        }
        .sortedBy { it.recordDate }

    if (usable.size < 2) return emptyList()

    fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else sorted[middle]
    }

    // Use the recent 42-month cadence window. A missed quarter may create a
    // long gap, so classify intervals individually and require repeated support.
    val cadenceRows = usable.filter { !it.recordDate.isBefore(today.minusMonths(42)) }
    if (cadenceRows.size < 2) return emptyList()
    val intervals = cadenceRows.zipWithNext { first, second ->
        java.time.temporal.ChronoUnit.DAYS.between(first.recordDate, second.recordDate).toDouble()
    }
    val quarterly = intervals.filter { it in 75.0..149.0 }
    val semiannual = intervals.filter { it in 150.0..239.0 }
    val annual = intervals.filter { it in 300.0..430.0 }

    val (supportingIntervals, minSupport) = when {
        quarterly.size >= 2 -> quarterly to 2
        semiannual.size >= 2 -> semiannual to 2
        // One annual interval is enough only when the history contains exactly
        // two observations. With 3+ observations, require two consecutive annual
        // intervals so an isolated late payment cannot create a false schedule.
        annual.isNotEmpty() && (
            cadenceRows.size == 2 ||
                intervals.takeLast(2).all { it in 300.0..430.0 }
            ) -> annual to 1
        else -> return emptyList()
    }
    if (supportingIntervals.size < minSupport) return emptyList()

    val stepDays = Math.round(median(supportingIntervals)).coerceAtLeast(1L)
    val amount = median(cadenceRows.takeLast(3).map { it.amountPerShare })
    if (!amount.isFinite() || amount <= 0.0) return emptyList()

    val endDate = java.time.YearMonth.from(today)
        .plusMonths(horizonMonths.toLong()).atEndOfMonth()
    val anchor = (cadenceRows.map { it.recordDate } +
        futureAnchorDates.filter { !it.isBefore(today) }).maxOrNull()
        ?: cadenceRows.last().recordDate

    val forecasts = mutableListOf<HistoricalDividendForecast>()
    var date = anchor.plusDays(stepDays)
    var previousForecastMonth: java.time.YearMonth? = null
    while (!date.isAfter(endDate)) {
        val month = java.time.YearMonth.from(date)
        val lastMonth = previousForecastMonth ?: java.time.YearMonth.from(anchor)
        val monthGap = java.time.temporal.ChronoUnit.MONTHS.between(lastMonth, month)
        // A forecast must never land in the same or immediately following month.
        if (!date.isBefore(today) && monthGap >= 2L) {
            forecasts += HistoricalDividendForecast(
                ticker = normalizedTicker,
                amountPerShare = amount,
                recordDate = date,
                sourceKey = "MYCAPITAL_HISTORY:$normalizedTicker:$date"
            )
            previousForecastMonth = month
        }
        date = date.plusDays(stepDays)
    }
    return forecasts.distinctBy { it.recordDate }.sortedBy { it.recordDate }
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
    // The current portfolio SECID must be available before forecasts are trusted.
    val annualCoverageVerified: Boolean,
    // Empty history is authoritative only when every related SECID was checked.
    val allRelatedSecidsVerified: Boolean = annualCoverageVerified
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
            // Forward yield is a cash-flow estimate: prefer payment date.
            // Fall back to registry date only when the source has not published one.
            val timestamp = event.paymentDate ?: event.recordDate ?: return@mapNotNull null
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

        // Historical forecasts require a multi-year record-date series.
        // Long/Short dividends-ru.csv is a current/upcoming calendar, not a
        // complete historical archive, so it must never be treated as history.
        // Keep MOEX history retrieval as the current source until a full,
        // independently validated historical provider is integrated.

        // Prefer the ISIN attached to the exact SECID row returned by securities search.
        // Description labels can be localized or ambiguous (for example, TATN/TATNP);
        // therefore description is only a fallback, never the primary identity source.
        val description = runCatching {
            requestJson("https://iss.moex.com/iss/securities/$normalized.json?iss.meta=off")
        }.getOrNull()
        val descriptionIsin = table(description ?: JSONObject(), "description")
            .firstNotNullOfOrNull { row ->
                if (row.optString("name").contains("ISIN", ignoreCase = true)) {
                    row.optString("value")
                        .replace(" ", "")
                        .uppercase(Locale.ROOT)
                        .takeIf { it.matches(Regex("[A-Z]{2}[A-Z0-9]{10}")) }
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
        val isin = tickerSearchIsin ?: descriptionIsin

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

        val result = mutableListOf<HistoricalDividend>()
        var successfulHistoryEndpoints = 0
        var currentSecidVerified = false
        var allRelatedSecidsVerified = true
        var lastHistoryError: Exception? = null
        relatedSecids.forEach { secid ->
            try {
                val payload = requestJson(
                    "https://iss.moex.com/iss/securities/$secid/dividends.json?iss.meta=off"
                )
                val dividendBlock = payload.optJSONObject("dividends")
                    ?: throw IOException("MOEX не вернул блок dividends для SECID $secid")
                val dividendColumns = dividendBlock.optJSONArray("columns")
                    ?: throw IOException("MOEX не вернул columns для dividends SECID $secid")
                val columnNames = (0 until dividendColumns.length())
                    .map { dividendColumns.optString(it).lowercase(Locale.ROOT) }
                if ("value" !in columnNames ||
                    ("registryclosedate" !in columnNames && "recorddate" !in columnNames)
                ) {
                    throw IOException("MOEX вернул неполную структуру dividends для SECID $secid: $columnNames")
                }
                successfulHistoryEndpoints++
                if (secid.equals(normalized, ignoreCase = true)) currentSecidVerified = true
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
                allRelatedSecidsVerified = false
                lastHistoryError = error
            }
        }
        // A retired SECID can legitimately stop serving history while the current
        // SECID remains available. Keep usable partial history; fail only when none
        // of the candidate security IDs could be queried.
        if (successfulHistoryEndpoints == 0 || !currentSecidVerified) {
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
            annualCoverageVerified = currentSecidVerified,
            allRelatedSecidsVerified = allRelatedSecidsVerified
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
