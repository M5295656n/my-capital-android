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
    // Keep distinct real events, including multiple independent payouts in one
    // month. Only exact same-date/same-amount source rows are duplicates.
    val history = dividends.asSequence().filter {
        it.secid.equals(normalizedTicker, ignoreCase = true) &&
            it.currency.equals("RUB", ignoreCase = true) &&
            it.amountPerShare.isFinite() && it.amountPerShare > 0.0 &&
            !it.recordDate.isBefore(historyStart) && !it.recordDate.isAfter(today)
    }.distinctBy {
        "${it.recordDate}:${"%.6f".format(Locale.ROOT, it.amountPerShare)}"
    }.sortedBy { it.recordDate }.toList()
    if (history.size < 2) return emptyList()

    fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0
        else sorted[middle]
    }

    val endDate = java.time.YearMonth.from(today).plusMonths(horizonMonths.toLong()).atEndOfMonth()
    val annualSlotForecasts = mutableListOf<HistoricalDividendForecast>()
    val recurringAnnualSlotMonths = mutableSetOf<Int>()
    val cadenceForecasts = mutableListOf<HistoricalDividendForecast>()
    var selectedCadence: String? = null

    // First identify independent annual payment slots. This is essential for
    // issuers with 2-4 separate distributions per year: sorting all dates into
    // one interval series would mistake the short gap between slots for a cadence.
    history.groupBy { it.recordDate.monthValue }.forEach { (_, monthRows) ->
        val byYear = monthRows.groupBy { it.recordDate.year }
        val oneEventPerYear = byYear.values.all { it.size == 1 }
        val yearlyRows = byYear.values.mapNotNull { it.singleOrNull() }.sortedBy { it.recordDate }
        if (oneEventPerYear && yearlyRows.size >= 2) {
            val yearGaps = yearlyRows.zipWithNext { a, b -> b.recordDate.year - a.recordDate.year }
            if (yearGaps.all { it in 1..2 }) {
                recurringAnnualSlotMonths += monthRows.first().recordDate.monthValue
                val latest = yearlyRows.last()
                val amount = median(yearlyRows.takeLast(3).map { it.amountPerShare })
                // A missing year must not turn a biennial gap into a 18-month forecast.
                // Once a recurring month slot is established, advance by calendar year.
                var date = latest.recordDate.plusYears(1)
                while (!date.isAfter(endDate)) {
                    if (!date.isBefore(today)) {
                        annualSlotForecasts += HistoricalDividendForecast(
                            normalizedTicker, amount, date,
                            "MYCAPITAL_HISTORY:$normalizedTicker:$date"
                        )
                    }
                    date = date.plusYears(1)
                }
            }
        }
    }

    // Infer regular 3/4/2/1-times-per-year cadence from the dominant repeated
    // interval. A skipped payment creates a long interval and does not erase
    // the shorter repeated intervals that establish the issuer's normal cycle.
    val cadenceRows = history.filter { !it.recordDate.isBefore(today.minusMonths(42)) }
    if (cadenceRows.size >= 2) {
        val intervals = cadenceRows.zipWithNext { first, second ->
            java.time.temporal.ChronoUnit.DAYS.between(first.recordDate, second.recordDate).toDouble()
        }
        val candidates = listOf(
            "QUARTERLY" to intervals.filter { it in 75.0..109.0 },
            "THREE_PER_YEAR" to intervals.filter { it in 110.0..149.0 },
            "SEMIANNUAL" to intervals.filter { it in 150.0..239.0 },
            "ANNUAL" to intervals.filter { it in 300.0..430.0 }
        )
        val selected = candidates.maxByOrNull { it.second.size }
        selectedCadence = selected?.first
        val support = selected?.second.orEmpty()
        val requiredSupport = if (selected?.first == "ANNUAL" && cadenceRows.size == 2) 1 else 2
        if (support.size >= requiredSupport) {
            val stepDays = Math.round(median(support)).coerceAtLeast(1L)
            val amount = median(cadenceRows.takeLast(3).map { it.amountPerShare })
            val lastCadenceEvent = cadenceRows.last().recordDate
            val anchor = (listOf(lastCadenceEvent) +
                futureAnchorDates.filter { !it.isBefore(today) }).maxOrNull() ?: lastCadenceEvent
            var date = anchor.plusDays(stepDays)
            while (!date.isAfter(endDate)) {
                if (!date.isBefore(today)) {
                    cadenceForecasts += HistoricalDividendForecast(
                        normalizedTicker, amount, date,
                        "MYCAPITAL_HISTORY:$normalizedTicker:$date"
                    )
                }
                date = date.plusDays(stepDays)
            }
        }
    }

    // Some issuers pay twice per year but move the registry dates between
    // months from one year to the next. MDMG is a real example: its latest two
    // calendar years each contain two paid events, while short 81-97 day gaps
    // make an interval vote incorrectly label the series quarterly. Only use
    // this fallback late in the calendar year, when the current year's second
    // payment has had time to appear in the published paid history.
    val latestCalendarYears = history.groupBy { it.recordDate.year }
        .toSortedMap().entries.toList().takeLast(2)
    val recentTwoPerYearForecasts = mutableListOf<HistoricalDividendForecast>()
    val hasTwoPaymentsInEachRecentYear = today.monthValue >= 10 &&
        latestCalendarYears.size == 2 &&
        latestCalendarYears.all { it.value.size == 2 } &&
        selectedCadence in setOf("QUARTERLY", "THREE_PER_YEAR")
    if (hasTwoPaymentsInEachRecentYear) {
        val previousYearRows = latestCalendarYears[0].value.sortedBy { it.recordDate }
        val latestYearRows = latestCalendarYears[1].value.sortedBy { it.recordDate }
        for (slot in 0..1) {
            val amount = median(listOf(previousYearRows[slot].amountPerShare, latestYearRows[slot].amountPerShare))
            var date = latestYearRows[slot].recordDate.plusYears(1)
            while (!date.isAfter(endDate)) {
                if (!date.isBefore(today)) {
                    recentTwoPerYearForecasts += HistoricalDividendForecast(
                        normalizedTicker, amount, date,
                        "MYCAPITAL_HISTORY:$normalizedTicker:$date"
                    )
                }
                date = date.plusYears(1)
            }
        }
    }

    // Stable calendar-year slots are stronger evidence than a noisy interval
    // vote when an issuer has two or three distinct recurring installments.
    // With uneven gaps (for example 4 + 8 months), interval voting can mistake
    // the shorter gap for a quarterly/three-per-year cadence and multiply the
    // forecast. Four recurring slots remain eligible for a genuine quarterly
    // schedule; one slot remains the annual-payer path.
    val hasStableAnnualSlotsAgainstNoisyCadence =
        annualSlotForecasts.isNotEmpty() && (
            // Three irregular annual installments can look quarterly to an
            // interval vote (e.g. 3/6/3-month gaps).
            (recurringAnnualSlotMonths.size == 3 && selectedCadence == "QUARTERLY") ||
                // Two annual periods can look like three-per-year when one gap
                // is short and the other long (e.g. 4/8-month gaps).
                (recurringAnnualSlotMonths.size == 2 && selectedCadence in setOf("THREE_PER_YEAR", "SEMIANNUAL"))
            )
    val forecasts = when {
        recentTwoPerYearForecasts.isNotEmpty() -> recentTwoPerYearForecasts
        hasStableAnnualSlotsAgainstNoisyCadence -> annualSlotForecasts
        cadenceForecasts.isNotEmpty() -> cadenceForecasts
        else -> annualSlotForecasts
    }

    // Forecasts represent expected issuer distributions, not separate source
    // rows. Never create two estimated payouts for the same ticker in one
    // calendar month, even when the inferred dates/amounts differ slightly.
    val reconciled = mutableListOf<HistoricalDividendForecast>()
    forecasts.sortedBy { it.recordDate }.forEach { candidate ->
        val alreadyForecastedMonth = reconciled.any { existing ->
            java.time.YearMonth.from(existing.recordDate) == java.time.YearMonth.from(candidate.recordDate)
        }
        if (!alreadyForecastedMonth) reconciled += candidate
    }
    return reconciled.sortedBy { it.recordDate }
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

/**
 * Historical dividend data moved out of the old public MOEX ISS dividends route.
 * Read the public, periodically refreshed issuer datasets first; MOEX ISS remains
 * a last-resort compatibility source. One provider is selected per ticker so
 * differently dated rows from different aggregators are never added together.
 */
internal fun parseDohodHistoricalDividends(
    ticker: String,
    indexJson: String,
    historyJson: String,
    today: LocalDate = LocalDate.now(ZoneId.of("Europe/Moscow"))
): List<HistoricalDividend> {
    val normalized = ticker.trim().uppercase(Locale.ROOT)
    val index = JSONObject(indexJson)
    val tickers = index.optJSONArray("tickers") ?: return emptyList()
    if ((0 until tickers.length()).none { tickers.optString(it).equals(normalized, true) }) return emptyList()
    val history = JSONObject(historyJson)
    if (!history.optString("ticker").equals(normalized, ignoreCase = true)) return emptyList()
    if (!isRecentDividendDataset(history.optString("scrapedAt"), today)) return emptyList()
    val payments = history.optJSONArray("payments") ?: return emptyList()
    val rows = mutableListOf<HistoricalDividend>()
    for (i in 0 until payments.length()) {
        val payment = payments.optJSONObject(i) ?: continue
        if (payment.optBoolean("isForecast", false)) continue
        val date = runCatching { LocalDate.parse(payment.optString("recordDate")) }.getOrNull() ?: continue
        val amount = payment.optDouble("amount", Double.NaN)
        if (!amount.isFinite() || amount <= 0.0 || date.isAfter(today)) continue
        rows += HistoricalDividend(normalized, date, amount, "RUB")
    }
    return rows.distinctBy {
        "${it.recordDate}:${"%.6f".format(Locale.ROOT, it.amountPerShare)}"
    }.sortedBy { it.recordDate }
}

internal fun parseSmartlabHistoricalDividends(
    ticker: String,
    indexJson: String,
    historyJson: String,
    today: LocalDate = LocalDate.now(ZoneId.of("Europe/Moscow"))
): List<HistoricalDividend> {
    val normalized = ticker.trim().uppercase(Locale.ROOT)
    val index = JSONObject(indexJson)
    val tickers = index.optJSONArray("tickers") ?: return emptyList()
    if ((0 until tickers.length()).none { tickers.optString(it).equals(normalized, true) }) return emptyList()
    val history = JSONObject(historyJson)
    if (!history.optString("ticker").equals(normalized, ignoreCase = true)) return emptyList()
    if (!isRecentDividendDataset(history.optString("scrapedAt"), today)) return emptyList()
    val payments = history.optJSONArray("payments") ?: return emptyList()
    val rows = mutableListOf<HistoricalDividend>()
    for (i in 0 until payments.length()) {
        val payment = payments.optJSONObject(i) ?: continue
        if (!payment.optString("status").equals("paid", true)) continue
        val currency = payment.optString("currency").ifBlank { "RUB" }
        if (!currency.equals("RUB", true)) continue
        val date = runCatching { LocalDate.parse(payment.optString("recordDate")) }.getOrNull() ?: continue
        val amount = payment.optDouble("amount", Double.NaN)
        if (!amount.isFinite() || amount <= 0.0 || date.isAfter(today)) continue
        rows += HistoricalDividend(normalized, date, amount, "RUB")
    }
    return rows.distinctBy {
        "${it.recordDate}:${"%.6f".format(Locale.ROOT, it.amountPerShare)}"
    }.sortedBy { it.recordDate }
}

/**
 * Select one provider's complete issuer series; never combine dates from two
 * aggregators because their registry-date conventions may differ.
 */
internal fun selectMoreCompleteDividendHistory(
    dohod: List<HistoricalDividend>?,
    smartlab: List<HistoricalDividend>?
): List<HistoricalDividend>? {
    if (dohod.isNullOrEmpty()) return smartlab
    if (smartlab.isNullOrEmpty()) return dohod
    val dohodStart = dohod.minOfOrNull { it.recordDate } ?: LocalDate.MAX
    val smartlabStart = smartlab.minOfOrNull { it.recordDate } ?: LocalDate.MAX
    return when {
        smartlab.size > dohod.size -> smartlab
        smartlab.size == dohod.size && smartlabStart.isBefore(dohodStart) -> smartlab
        else -> dohod
    }
}

private fun isRecentDividendDataset(scrapedAt: String, today: LocalDate): Boolean {
    val scrapedDate = runCatching { java.time.Instant.parse(scrapedAt).atZone(ZoneId.of("Europe/Moscow")).toLocalDate() }
        .getOrNull() ?: return false
    val ageDays = java.time.temporal.ChronoUnit.DAYS.between(scrapedDate, today)
    return ageDays in 0..30
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

        // Public historical data is now preferred: the former unauthenticated
        // MOEX ISS /securities/{secid}/dividends route no longer exposes dividends.
        // Dohod is selected first; Smart-Lab is used only when Dohod has no complete
        // dataset for this ticker. Never merge their rows for one issuer.

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

        val publishedHistory = fetchAlternativeDividendHistory(normalized)
        if (!publishedHistory.isNullOrEmpty()) {
            result += publishedHistory.map { it.copy(secid = normalized) }
            successfulHistoryEndpoints = 1
            currentSecidVerified = true
            allRelatedSecidsVerified = true
        } else {
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
        }
        // A retired SECID can legitimately stop serving history while the current
        // SECID remains available. Keep usable partial history; fail only when none
        // of the candidate security IDs could be queried.
        if (successfulHistoryEndpoints == 0 || !currentSecidVerified) {
            throw IOException(
                "Не удалось получить историю дивидендов для $normalized из альтернативных источников и MOEX ISS: " +
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


    private var dohodIndex: JSONObject? = null
    private var smartlabIndex: JSONObject? = null
    private var dohodIndexAttempted = false
    private var smartlabIndexAttempted = false

    private fun fetchAlternativeDividendHistory(ticker: String): List<HistoricalDividend>? {
        val base = "https://raw.githubusercontent.com/amchercashin/heroincome-data/main/data/stocks"
        val today = LocalDate.now(ZoneId.of("Europe/Moscow"))

        // Fetch both independent archives and choose the more complete single
        // issuer series. Never merge providers: their record dates may differ.
        val dohod = runCatching {
            if (!dohodIndexAttempted) {
                dohodIndexAttempted = true
                dohodIndex = runCatching { requestJson("$base/dohod/index.json") }.getOrNull()
            }
            val index = dohodIndex ?: return@runCatching null
            val history = requestJson("$base/dohod/$ticker.json")
            parseDohodHistoricalDividends(ticker, index.toString(), history.toString(), today)
                .takeIf { it.isNotEmpty() }
        }.getOrNull()

        val smartlab = runCatching {
            if (!smartlabIndexAttempted) {
                smartlabIndexAttempted = true
                smartlabIndex = runCatching { requestJson("$base/smartlab/index.json") }.getOrNull()
            }
            val index = smartlabIndex ?: return@runCatching null
            val history = requestJson("$base/smartlab/$ticker.json")
            parseSmartlabHistoricalDividends(ticker, index.toString(), history.toString(), today)
                .takeIf { it.isNotEmpty() }
        }.getOrNull()

        return selectMoreCompleteDividendHistory(dohod, smartlab)
    }

    private fun requestJson(url: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "MyCapital-Android/0.3.91")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Источник истории дивидендов вернул HTTP ${response.code}: ${url.substringBefore('?')}")
            return JSONObject(response.body?.string() ?: throw IOException("Пустой ответ источника истории дивидендов"))
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
