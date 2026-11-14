package ru.mycapital.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class MoexIndexPoint(
    val date: String,
    val value: Double
)

/**
 * Historical daily closing values from the public MOEX ISS index history endpoint.
 * Empty results are valid: the exchange may not publish a series through this endpoint
 * for a given index or date range. Callers must not substitute another series silently.
 */
class MoexIndexHistoryClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun getHistory(indexCode: String, fromDate: String, tillDate: String): List<MoexIndexPoint> {
        require(indexCode.matches(Regex("[A-Z0-9]{2,16}")))
        require(fromDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        require(tillDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))

        val encodedCode = URLEncoder.encode(indexCode, StandardCharsets.UTF_8.name())
        val baseUrls = listOf(
            "https://iss.moex.com/iss/history/engines/stock/markets/index/boards/SNDX/securities/$encodedCode.json",
            "https://iss.moex.com/iss/history/engines/stock/markets/index/securities/$encodedCode.json"
        )
        var lastError: Exception? = null
        val combinedByDate = linkedMapOf<String, MoexIndexPoint>()

        // The board-specific SNDX history can contain only a recent subset.
        // Query both routes and merge them so the benchmark reaches the
        // portfolio's first available date instead of silently starting later.
        for (baseUrl in baseUrls) {
            try {
                val result = mutableListOf<MoexIndexPoint>()
                var start = 0
                for (page in 0 until 50) {
                    val url = "$baseUrl?from=$fromDate&till=$tillDate&start=$start" +
                        "&iss.meta=off&iss.only=history&history.columns=TRADEDATE,SECID,CLOSE"
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .header("User-Agent", "MyCapital-Android/0.1")
                        .build()

                    val rows = client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw java.io.IOException("MOEX ISS HTTP ${response.code}")
                        }
                        val body = response.body?.string()
                            ?: throw java.io.IOException("Пустой ответ MOEX ISS")
                        val history = JSONObject(body).optJSONObject("history")
                            ?: return@use emptyList<MoexIndexPoint>()
                        val columns = history.optJSONArray("columns")
                            ?: return@use emptyList<MoexIndexPoint>()
                        val data = history.optJSONArray("data")
                            ?: return@use emptyList<MoexIndexPoint>()
                        val indexes = (0 until columns.length()).associate { columns.getString(it) to it }
                        val dateIndex = indexes["TRADEDATE"] ?: return@use emptyList<MoexIndexPoint>()
                        val codeIndex = indexes["SECID"]
                        val closeIndex = indexes["CLOSE"] ?: return@use emptyList<MoexIndexPoint>()
                        buildList {
                            for (rowIndex in 0 until data.length()) {
                                val row = data.optJSONArray(rowIndex) ?: continue
                                if (codeIndex != null && row.optString(codeIndex) != indexCode) continue
                                val date = row.optString(dateIndex)
                                val value = if (row.isNull(closeIndex)) Double.NaN else row.optDouble(closeIndex, Double.NaN)
                                if (date.isNotBlank() && value.isFinite() && value > 0.0) {
                                    add(MoexIndexPoint(date, value))
                                }
                            }
                        }
                    }

                    if (rows.isEmpty()) break
                    result += rows
                    if (rows.size < 100) break
                    start += rows.size
                }
                result.forEach { point -> combinedByDate[point.date] = point }
            } catch (error: Exception) {
                lastError = error
            }
        }
        if (combinedByDate.isNotEmpty()) return combinedByDate.values.sortedBy { it.date }
        lastError?.let { throw it }
        return emptyList()
    }
}
