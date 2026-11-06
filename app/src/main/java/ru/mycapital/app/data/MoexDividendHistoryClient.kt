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
    val trailingTwelveMonthYieldPercent: Double?,
    val fiveYearGrowthPercent: Double?,
    val historyCount: Int
)

internal fun calculateDividendHistoryMetrics(
    dividends: List<HistoricalDividend>,
    currentPrice: Double,
    today: LocalDate
): DividendHistoryMetrics {
    val rubleDividends = dividends.filter {
        it.currency.equals("RUB", true) && it.amountPerShare > 0.0 &&
            it.amountPerShare.isFinite() && !it.recordDate.isAfter(today)
    }.distinctBy { "${it.recordDate}:${"%.6f".format(Locale.ROOT, it.amountPerShare)}" }
    val trailingStart = today.minusDays(365)
    val trailingAmount = rubleDividends.filter {
        !it.recordDate.isBefore(trailingStart)
    }.sumOf { it.amountPerShare }
    val yieldPercent = if (currentPrice.isFinite() && currentPrice > 0.0 && trailingAmount > 0.0) {
        trailingAmount / currentPrice * 100.0
    } else null

    // Compare the last five completed calendar years with the five completed years before them.
    // A five-year CAGR needs both endpoints and a complete annual series; missing years are not zero.
    val lastCompletedYear = if (today.monthValue == 1 && today.dayOfMonth == 1) today.year - 1
        else if (today.isBefore(LocalDate.of(today.year, 12, 31))) today.year - 1 else today.year - 1
    val recentYears = (lastCompletedYear - 4..lastCompletedYear).toList()
    val priorYears = (lastCompletedYear - 9 until lastCompletedYear - 4).toList()
    val annual = rubleDividends.groupBy { it.recordDate.year }.mapValues { (_, rows) ->
        rows.sumOf { it.amountPerShare }
    }
    val allYears = priorYears + recentYears
    val complete = allYears.all { annual.containsKey(it) }
    val first = annual[priorYears.first()]
    val last = annual[recentYears.last()]
    val growth = if (complete && first != null && last != null && first > 0.0 && last > 0.0) {
        (Math.pow(last / first, 1.0 / 5.0) - 1.0) * 100.0
    } else null
    return DividendHistoryMetrics(yieldPercent, growth, rubleDividends.size)
}

internal class MoexDividendHistoryClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun getHistory(ticker: String): List<HistoricalDividend> {
        val normalized = ticker.trim().uppercase(Locale.ROOT)
        require(normalized.matches(Regex("[A-Z0-9_]+"))) { "Некорректный тикер: $ticker" }
        val secids = linkedSetOf(normalized)
        val security = requestJson("https://iss.moex.com/iss/securities/$normalized.json?iss.meta=off")
        val isin = table(security, "description").firstNotNullOfOrNull { row ->
            val name = row.optString("name").uppercase(Locale.ROOT)
            if (name == "ISIN") row.optString("value").takeIf { it.isNotBlank() && it != "null" } else null
        }
        if (!isin.isNullOrBlank()) {
            runCatching {
                val search = requestJson("https://iss.moex.com/iss/securities.json?q=$isin&iss.meta=off")
                table(search, "securities").forEach { row ->
                    val candidateIsin = row.optString("isin")
                    val candidate = row.optString("secid")
                    if (candidateIsin.equals(isin, true) && candidate.isNotBlank()) secids += candidate
                }
            }
        }
        val result = mutableListOf<HistoricalDividend>()
        secids.forEach { secid ->
            val payload = requestJson("https://iss.moex.com/iss/securities/$secid/dividends.json?iss.meta=off")
            table(payload, "dividends").forEach { row ->
                val dateText = row.optString("registryclosedate").takeIf { it.isNotBlank() && it != "null" }
                    ?: row.optString("recorddate").takeIf { it.isNotBlank() && it != "null" }
                val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: return@forEach
                val amount = row.optDouble("value", Double.NaN)
                val currency = row.optString("currencyid").ifBlank { "RUB" }
                if (amount.isFinite() && amount > 0.0) {
                    result += HistoricalDividend(secid, date, amount, currency)
                }
            }
        }
        return result.distinctBy { "${it.recordDate}:${"%.6f".format(Locale.ROOT, it.amountPerShare)}:${it.currency}" }
            .sortedBy { it.recordDate }
    }

    private fun requestJson(url: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "MyCapital-Android/0.3.20")
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
