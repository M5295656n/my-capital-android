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
    // Five-year annualized dividend growth (CAGR), matching Snowball:
    // compare the annual per-share payout five years ago with the latest year.
    // Intermediate years with no payout do not become fabricated dividend rows.
    val firstYear = today.year - 5
    val lastYear = today.year
    val annual = rubleDividends.groupBy { it.recordDate.year }.mapValues { (_, rows) ->
        rows.sumOf { it.amountPerShare }
    }
    val first = annual[firstYear]
    val last = annual[lastYear]
    val growth = if (
        annualCoverageVerified && first != null && last != null &&
        first > 0.0 && last > 0.0
    ) {
        (Math.pow(last / first, 1.0 / 5.0) - 1.0) * 100.0
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
        val secids = linkedSetOf(normalized)
        val security = requestJson("https://iss.moex.com/iss/securities/$normalized.json?iss.meta=off")
        val isin = table(security, "description").firstNotNullOfOrNull { row ->
            val name = row.optString("name").uppercase(Locale.ROOT)
            if (name == "ISIN") row.optString("value").takeIf { it.isNotBlank() && it != "null" } else null
        }
        var annualCoverageVerified = false
        if (!isin.isNullOrBlank()) {
            annualCoverageVerified = runCatching {
                var start = 0
                var total: Int? = null
                var foundAny = false
                do {
                    val search = requestJson("https://iss.moex.com/iss/securities.json?q=$isin&start=$start&iss.meta=off")
                    val rows = table(search, "securities")
                    val matchingRows = rows.filter { it.optString("isin").equals(isin, true) }
                    matchingRows.forEach { row ->
                        val candidate = row.optString("secid")
                        if (candidate.isNotBlank()) secids += candidate
                    }
                    foundAny = foundAny || matchingRows.isNotEmpty()
                    val cursor = search.optJSONObject("securities.cursor")
                    val cursorColumns = cursor?.optJSONArray("columns")
                    val cursorData = cursor?.optJSONArray("data")?.optJSONArray(0)
                    val totalIndex = (0 until (cursorColumns?.length() ?: 0))
                        .firstOrNull { cursorColumns?.optString(it)?.equals("TOTAL", true) == true }
                    total = totalIndex?.let { cursorData?.optInt(it) }
                    start += rows.size
                    if (rows.isEmpty()) break
                } while (total != null && start < total!!)
                foundAny && total != null && start >= total!!
            }.getOrDefault(false)
        }
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
