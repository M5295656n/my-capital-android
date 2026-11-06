package ru.mycapital.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.TimeUnit

internal fun isEventDateUpcoming(date: LocalDate, today: LocalDate = LocalDate.now(ZoneId.of("Europe/Moscow"))): Boolean =
    !date.isBefore(today)

/**
 * Returns only a true payment-date column. Registry-close and record dates are
 * not payment dates and must never be shown to the user as expected cash dates.
 */
internal fun paymentDateColumn(isBond: Boolean, columnIndexes: Map<String, Int>): Int? {
    val candidates = if (isBond) listOf("coupondate", "paymentdate") else listOf("paymentdate")
    return candidates.firstNotNullOfOrNull { columnIndexes[it] }
}

internal fun parseMoexEventDate(dateText: String): LocalDate? {
    if (!dateText.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return null
    return runCatching { LocalDate.parse(dateText) }.getOrNull()
}

internal fun isBondInstrument(ticker: String, name: String = "", assetType: String = ""): Boolean {
    val normalizedTicker = ticker.trim().uppercase(Locale.ROOT)
    val normalizedName = name.trim().lowercase(Locale.ROOT)
    val normalizedType = assetType.trim().lowercase(Locale.ROOT)
    return normalizedType.contains("облигац") ||
        normalizedType.contains("офз") ||
        normalizedType.contains("bond") ||
        normalizedName.contains("офз") ||
        normalizedName.contains("облигац") ||
        Regex("^SU\\d{5}RMFS\\d$").matches(normalizedTicker)
}

class UnsupportedCorporateEventsInstrumentException(message: String) : IOException(message)

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
        val isBond = isBondInstrument(ticker = ticker, assetType = assetType)
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
            if (response.code == 404 || response.code == 400) {
                throw UnsupportedCorporateEventsInstrumentException("MOEX ISS не поддерживает инструмент $ticker (HTTP ${response.code})")
            }
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
            val indexes = (0 until columns.length()).associate {
                columns.getString(it).lowercase(Locale.ROOT) to it
            }
            val dateIndex = paymentDateColumn(isBond, indexes) ?: return emptyList()
            val amountIndex = listOf("value", "issuevalue", "couponvalue")
                .firstNotNullOfOrNull { indexes[it] } ?: return emptyList()

            val events = mutableListOf<ImportedPayoutEvent>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                if (dateIndex >= row.length() || amountIndex >= row.length()) continue
                val dateText = row.optString(dateIndex)
                val localDate = parseMoexEventDate(dateText) ?: continue
                val date = localDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                val amount = if (row.isNull(amountIndex)) Double.NaN else row.optDouble(amountIndex, Double.NaN)
                if (!amount.isFinite() || amount <= 0.0 || !isEventDateUpcoming(localDate)) continue
                val type = if (isBond) "Купон" else "Дивиденд"
                val normalizedTicker = ticker.trim().uppercase()
                val key = "$normalizedTicker|$type|$dateText"
                events += ImportedPayoutEvent(normalizedTicker, type, amount, date, key)
            }
            return events.distinctBy { it.sourceKey }
        }
    }
}
