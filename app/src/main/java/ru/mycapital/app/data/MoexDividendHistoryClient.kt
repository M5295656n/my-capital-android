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
    horizonMonths: Int = 18
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
                .let { kotlin.math.round(it).toInt() }.coerceIn(1, month.lengthOfMonth())
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
    return forecasts
}

/** Forward dividend yield based on payout records already stored in the portfolio database. */
internal fun calculateForwardDividendYieldPercent(
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
            val timestamp = payout.recordDate ?: payout.expectedDate ?: return@mapNotNull null
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
    // True only when the issuer ISIN lookup and every discovered security history loaded.
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun getHistory(ticker: String): DividendHistoryFetchResult {
        val normalized = ticker.trim().uppercase(Locale.ROOT)
        require(normalized.matches(Regex("[A-Z0-9_]+"))) { "Некорректный тикер: $ticker" }
        // Fetch the direct security history in one request. The forecast uses only
        // this ticker's own dividend records; no cross-issuer ISIN aggregation is needed.
        val secids = linkedSetOf(normalized)
        var annualCoverageVerified = true
        val result = mutableListOf<HistoricalDividend>()
        try {
        secids.forEach { secid ->
            val payload = requestJson("https://iss.moex.com/iss/securities/$secid/dividends.json?iss.meta=off")
            table(payload, "dividends").forEach { row ->
                val dateText = row.optString("registryclosedate").takeIf { it.isNotBlank() && it != "null" }
                    ?: row.optString("recorddate").takeIf { it.isNotBlank() && it != "null" }
                val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: return@forEach
                val amount = row.optDouble("value", Double.NaN)
                val rawCurrency = row.optString("currencyid")
                val currency = if (rawCurrency.isBlank() || rawCurrency == "null") "RUB" else rawCurrency
                if (amount.isFinite() && amount > 0.0) {
                    result += HistoricalDividend(secid, date, amount, currency)
                }
            }
        }
        } catch (e: Exception) {
            annualCoverageVerified = false
            throw e
        }
        return DividendHistoryFetchResult(
            result.distinctBy { "${it.recordDate}:${"%.6f".format(Locale.ROOT, it.amountPerShare)}:${it.currency}" }
                .sortedBy { it.recordDate },
            annualCoverageVerified
        )
    }

    private fun requestJson(url: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "MyCapital-Android/0.3.25")
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
