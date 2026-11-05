package ru.mycapital.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.concurrent.TimeUnit

data class MoexDailyClose(
    val date: LocalDate,
    val close: Double
)

/**
 * Public delayed daily candles from MOEX ISS. This client deliberately supports
 * shares and exchange-traded funds only. Bond candles are percentage-of-face
 * quotations and must not be valued as rubles without historical face value and
 * accrued coupon interest.
 */
class MoexSecurityHistoryClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun getDailyCloses(
        ticker: String,
        assetType: String,
        fromDate: LocalDate,
        tillDate: LocalDate
    ): List<MoexDailyClose> {
        require(ticker.matches(Regex("[A-Za-z0-9._-]{1,32}"))) { "Некорректный тикер" }
        require(fromDate <= tillDate) { "Начальная дата позже конечной" }
        val type = assetType.lowercase()
        require(type.contains("акци") || type.contains("фонд") || type.contains("пай")) {
            "Историческая оценка облигаций пока не поддерживается"
        }

        val market = "shares"
        val safeTicker = URLEncoder.encode(ticker.trim().uppercase(), StandardCharsets.UTF_8.name())
        val baseUrl = "https://iss.moex.com/iss/engines/stock/markets/$market/securities/$safeTicker/candles.json"
        val result = linkedMapOf<LocalDate, MoexDailyClose>()
        var start = 0

        repeat(50) {
            val url = "$baseUrl?from=$fromDate&till=$tillDate&interval=24&start=$start" +
                "&iss.meta=off&iss.only=candles&candles.columns=begin,close"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "MyCapital-Android/0.1")
                .build()

            val page = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("MOEX ISS вернул HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("Пустой ответ MOEX ISS")
                parseDailyCandles(body)
            }
            if (page.isEmpty()) return result.values.sortedBy { it.date }
            page.forEach { result[it.date] = it }
            if (page.size < 500) return result.values.sortedBy { it.date }
            start += page.size
        }
        return result.values.sortedBy { it.date }
    }
}

internal fun parseDailyCandles(json: String): List<MoexDailyClose> {
    val root = try {
        JSONObject(json)
    } catch (error: Exception) {
        throw IOException("Некорректный JSON от MOEX ISS", error)
    }
    val candles = root.optJSONObject("candles")
        ?: throw IOException("В ответе MOEX ISS отсутствуют дневные свечи")
    val columns = candles.optJSONArray("columns")
        ?: throw IOException("В ответе MOEX ISS отсутствуют столбцы свечей")
    val rows = candles.optJSONArray("data")
        ?: throw IOException("В ответе MOEX ISS отсутствуют данные свечей")
    val indexes = (0 until columns.length()).associate { columns.getString(it) to it }
    val beginIndex = indexes["begin"] ?: throw IOException("MOEX ISS не вернул дату свечи")
    val closeIndex = indexes["close"] ?: throw IOException("MOEX ISS не вернул цену закрытия")

    val rawRows = buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            if (row.isNull(beginIndex) || row.isNull(closeIndex)) continue
            add(row.optString(beginIndex) to row.optDouble(closeIndex, Double.NaN))
        }
    }
    return parseDailyCandleRows(rawRows)
}

internal fun parseDailyCandleRows(rows: List<Pair<String, Double?>>): List<MoexDailyClose> =
    rows.mapNotNull { (dateText, close) ->
        val date = runCatching { LocalDate.parse(dateText.take(10)) }.getOrNull()
            ?: return@mapNotNull null
        if (close == null || !close.isFinite() || close <= 0.0) return@mapNotNull null
        MoexDailyClose(date, close)
    }.distinctBy { it.date }.sortedBy { it.date
}
