package ru.mycapital.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class BondMarketInfo(
    val maturityDate: LocalDate?,
    val couponYieldPercent: Double?
)

internal fun calculateCurrentCouponYieldPercent(
    couponValue: Double?,
    couponsPerYear: Double?,
    cleanPrice: Double?
): Double? {
    if (couponValue == null || !couponValue.isFinite() || couponValue <= 0.0) return null
    if (couponsPerYear == null || !couponsPerYear.isFinite() || couponsPerYear <= 0.0) return null
    if (cleanPrice == null || !cleanPrice.isFinite() || cleanPrice <= 0.0) return null
    val result = couponValue * couponsPerYear / cleanPrice * 100.0
    return result.takeIf { it.isFinite() && it > 0.0 }
}

internal fun parseBondMaturityDate(value: String?): LocalDate? {
    if (value.isNullOrBlank() || value == "null") return null
    return runCatching { LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
}

internal fun couponFrequencyPerYear(frequency: Double?, periodDays: Double?): Double? {
    frequency?.takeIf { it.isFinite() && it > 0.0 }?.let { return it }
    return periodDays?.takeIf { it.isFinite() && it > 0.0 }?.let { 365.0 / it }
}

internal fun parseBondMarketInfo(
    root: JSONObject,
    currentDirtyPrice: Double
): BondMarketInfo {
    val securities = root.optJSONObject("securities")
        ?: throw IOException("MOEX ISS не вернул данные облигации")
    val columns = securities.optJSONArray("columns")
        ?: throw IOException("MOEX ISS не вернул названия полей облигации")
    val rows = securities.optJSONArray("data")
        ?: throw IOException("MOEX ISS не вернул значения полей облигации")
    val indexes = (0 until columns.length()).associate {
        columns.optString(it).lowercase(Locale.ROOT) to it
    }
    val row = (0 until rows.length()).firstNotNullOfOrNull { rows.optJSONArray(it) }
        ?: throw IOException("MOEX ISS не нашёл облигацию")
    fun number(name: String): Double? {
        val index = indexes[name.lowercase(Locale.ROOT)] ?: return null
        if (index >= row.length() || row.isNull(index)) return null
        return row.optDouble(index, Double.NaN).takeIf { it.isFinite() }
    }
    fun string(name: String): String? {
        val index = indexes[name.lowercase(Locale.ROOT)] ?: return null
        if (index >= row.length() || row.isNull(index)) return null
        return row.optString(index).takeIf { it.isNotBlank() && it != "null" }
    }

    val marketData = root.optJSONObject("marketdata")
    val marketColumns = marketData?.optJSONArray("columns")
    val marketRows = marketData?.optJSONArray("data")
    var accruedInterest = 0.0
    if (marketColumns != null && marketRows != null) {
        val accruedIndex = (0 until marketColumns.length()).firstOrNull {
            marketColumns.optString(it).equals("ACCRUEDINT", ignoreCase = true)
        }
        val marketAccrued = (0 until marketRows.length()).firstNotNullOfOrNull { marketRows.optJSONArray(it) }
        if (accruedIndex != null && marketAccrued != null &&
            accruedIndex < marketAccrued.length() && !marketAccrued.isNull(accruedIndex)
        ) {
            accruedInterest = marketAccrued.optDouble(accruedIndex, 0.0)
                .takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        }
    }
    val cleanPrice = (currentDirtyPrice - accruedInterest).takeIf { it.isFinite() && it > 0.0 }
    val frequency = couponFrequencyPerYear(number("COUPONFREQUENCY"), number("COUPONPERIOD"))
    val couponYield = calculateCurrentCouponYieldPercent(number("COUPONVALUE"), frequency, cleanPrice)

    return BondMarketInfo(
        maturityDate = parseBondMaturityDate(string("MATDATE")),
        couponYieldPercent = couponYield
    )
}

class BondMarketInfoClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    fun getInfo(ticker: String, currentDirtyPrice: Double): BondMarketInfo {
        val safeTicker = java.net.URLEncoder.encode(ticker.trim().uppercase(Locale.ROOT), Charsets.UTF_8.name())
        val url = "https://iss.moex.com/iss/engines/stock/markets/bonds/securities/$safeTicker.json" +
            "?iss.meta=off&iss.only=securities,marketdata" +
            "&securities.columns=SECID,MATDATE,COUPONVALUE,COUPONFREQUENCY,COUPONPERIOD" +
            "&marketdata.columns=SECID,BOARDID,ACCRUEDINT"
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "MyCapital-Android/0.1").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 400 || response.code == 404) {
                throw IOException("MOEX ISS не поддерживает облигацию $ticker (HTTP ${response.code})")
            }
            if (!response.isSuccessful) throw IOException("MOEX ISS вернул HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Пустой ответ MOEX ISS")
            val root = try { JSONObject(body) } catch (error: Exception) {
                throw IOException("Некорректный JSON с параметрами облигации", error)
            }
            return parseBondMarketInfo(root, currentDirtyPrice)
        }
    }
}
