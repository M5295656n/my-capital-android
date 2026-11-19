package ru.mycapital.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class MoexIndexCandle(
    val begin: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

class MoexIndexCandlesClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    fun getCandles(
        indexCode: String = "IMOEX",
        fromDate: String,
        tillDate: String,
        interval: Int
    ): List<MoexIndexCandle> {
        require(indexCode.matches(Regex("[A-Z0-9]{2,16}")))
        require(fromDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        require(tillDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        require(interval in setOf(1, 10, 60, 24))
        val code = URLEncoder.encode(indexCode, StandardCharsets.UTF_8.name())
        val bases = listOf(
            "https://iss.moex.com/iss/engines/stock/markets/index/securities/$code/candles.json",
            "https://iss.moex.com/iss/engines/stock/markets/index/boards/SNDX/securities/$code/candles.json"
        )
        var lastError: Exception? = null
        val merged = linkedMapOf<String, MoexIndexCandle>()
        for (base in bases) {
            try {
                var start = 0
                for (page in 0 until 30) {
                    val url = "$base?from=$fromDate&till=$tillDate&interval=$interval&start=$start" +
                        "&limit=500&candles.limit=500&iss.meta=off&iss.only=candles&candles.columns=begin,open,high,low,close"
                    val request = Request.Builder().url(url)
                        .header("Accept", "application/json")
                        .header("User-Agent", "MyCapital-Android/0.4.18")
                        .build()
                    val rows = client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("MOEX ISS HTTP ${response.code}")
                        val body = response.body?.string() ?: throw IOException("Пустой ответ MOEX ISS")
                        parseCandles(body)
                    }
                    if (rows.isEmpty()) break
                    rows.forEach { merged[it.begin] = it }
                    if (rows.size < 500) break
                    start += rows.size
                }
            } catch (error: Exception) {
                lastError = error
            }
        }
        if (merged.isNotEmpty()) return merged.values.sortedBy { it.begin }
        lastError?.let { throw it }
        return emptyList()
    }

    companion object {
        internal fun parseCandles(json: String): List<MoexIndexCandle> {
            val block = JSONObject(json).optJSONObject("candles") ?: return emptyList()
            val columns = block.optJSONArray("columns") ?: return emptyList()
            val rows = block.optJSONArray("data") ?: return emptyList()
            val indexes = (0 until columns.length()).associate { columns.getString(it) to it }
            val beginIndex = indexes["begin"] ?: return emptyList()
            val openIndex = indexes["open"] ?: return emptyList()
            val highIndex = indexes["high"] ?: return emptyList()
            val lowIndex = indexes["low"] ?: return emptyList()
            val closeIndex = indexes["close"] ?: return emptyList()
            return buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONArray(index) ?: continue
                    if (row.isNull(beginIndex)) continue
                    fun value(column: Int): Double? =
                        if (row.isNull(column)) null else row.optDouble(column, Double.NaN)
                            .takeIf { it.isFinite() && it > 0.0 }
                    val open = value(openIndex) ?: continue
                    val high = value(highIndex) ?: continue
                    val low = value(lowIndex) ?: continue
                    val close = value(closeIndex) ?: continue
                    if (high < maxOf(open, close, low) || low > minOf(open, close, high)) continue
                    add(MoexIndexCandle(row.optString(beginIndex), open, high, low, close))
                }
            }.distinctBy { it.begin }.sortedBy { it.begin }
        }
    }
}
