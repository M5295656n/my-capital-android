package ru.mycapital.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class ImportedPayoutEvent(
    val ticker: String,
    val type: String,
    val amountPerUnit: Double,
    val eventDate: Long,
    val sourceKey: String
)

class CorporateEventsClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    fun getEvents(ticker: String, assetType: String): List<ImportedPayoutEvent> {
        val isBond = assetType.contains("облигац", ignoreCase = true)
        val encoded = java.net.URLEncoder.encode(ticker.trim().uppercase(), Charsets.UTF_8.name())
        val url = if (isBond) {
            "https://iss.moex.com/iss/statistics/engines/stock/markets/bonds/bondization/$encoded.json" +
                "?iss.meta=off&iss.only=coupons&coupons.columns=coupondate,value"
        } else {
            "https://iss.moex.com/iss/securities/$encoded/dividends.json?iss.meta=off"
        }
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "MyCapital-Android/0.1").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("MOEX ISS вернул HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Пустой ответ MOEX ISS")
            val root = try {
                JSONObject(body)
            } catch (error: Exception) {
                throw IOException("Некорректный JSON от MOEX ISS", error)
            }
            val blockName = if (isBond) "coupons" else "dividends"
            val table = root.optJSONObject(blockName)
                ?: throw IOException("В ответе MOEX ISS отсутствует таблица $blockName")
            val columns = table.optJSONArray("columns")
                ?: throw IOException("В таблице MOEX ISS отсутствуют столбцы")
            val rows = table.optJSONArray("data")
                ?: throw IOException("В таблице MOEX ISS отсутствуют данные")
            val indexes = (0 until columns.length()).associate { columns.getString(it).lowercase(Locale.ROOT) to it }
            val dateFields = if (isBond) listOf("coupondate", "paymentdate") else
                listOf("paymentdate", "registryclosedate", "recorddate", "date")
            val dateIndex = dateFields.firstNotNullOfOrNull { indexes[it] } ?: return emptyList()
            val amountIndex = listOf("value", "issuevalue", "couponvalue")
                .firstNotNullOfOrNull { indexes[it] } ?: return emptyList()
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val today = LocalDate.now(ZoneOffset.UTC)
            val events = mutableListOf<ImportedPayoutEvent>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                if (dateIndex >= row.length() || amountIndex >= row.length()) continue
                val dateText = row.optString(dateIndex).takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: continue
                val localDate = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: continue
                val date = runCatching { formatter.parse(dateText)?.time }.getOrNull() ?: continue
                val amount = if (row.isNull(amountIndex)) Double.NaN else row.optDouble(amountIndex, Double.NaN)
                if (!amount.isFinite() || amount <= 0.0 || localDate.isBefore(today)) continue
                val type = if (isBond) "Купон" else "Дивиденд"
                val normalizedTicker = ticker.trim().uppercase()
                val key = "$normalizedTicker|$type|$dateText"
                events += ImportedPayoutEvent(normalizedTicker, type, amount, date, key)
            }
            return events.distinctBy { it.sourceKey }
        }
    }
}
