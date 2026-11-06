package ru.mycapital.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

data class BondMarketInfo(
    val maturityDate: LocalDate?,
    val couponYieldPercent: Double?
)

/**
 * Coupon yield is the annual coupon amount divided by the current face value.
 * It is not current yield, which uses the market price as its denominator.
 */
internal fun calculateCouponYieldPercent(
    couponValue: Double?,
    couponPeriodDays: Double?,
    couponsPerYear: Double?,
    faceValue: Double?,
    couponPercentFromExchange: Double? = null
): Double? {
    couponPercentFromExchange?.takeIf { it.isFinite() && it > 0.0 }?.let { return it }
    if (couponValue == null || !couponValue.isFinite() || couponValue <= 0.0) return null
    if (faceValue == null || !faceValue.isFinite() || faceValue <= 0.0) return null
    val annualizationFactor = couponPeriodDays?.takeIf { it.isFinite() && it > 0.0 }?.let { 365.0 / it }
        ?: couponsPerYear?.takeIf { it.isFinite() && it > 0.0 }
        ?: return null
    val result = couponValue * annualizationFactor / faceValue * 100.0
    return result.takeIf { it.isFinite() && it > 0.0 }
}

internal fun parseBondMaturityDate(value: String?): LocalDate? {
    if (value.isNullOrBlank() || value == "null") return null
    return runCatching { LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
}

internal fun createBondMarketInfo(
    maturityDate: String?,
    couponValue: Double?,
    couponPeriodDays: Double?,
    couponsPerYear: Double?,
    faceValue: Double?,
    couponPercentFromExchange: Double?
): BondMarketInfo = BondMarketInfo(
    maturityDate = parseBondMaturityDate(maturityDate),
    couponYieldPercent = calculateCouponYieldPercent(
        couponValue = couponValue,
        couponPeriodDays = couponPeriodDays,
        couponsPerYear = couponsPerYear,
        faceValue = faceValue,
        couponPercentFromExchange = couponPercentFromExchange
    )
)

internal fun parseBondMarketInfo(root: JSONObject): BondMarketInfo {
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

    return createBondMarketInfo(
        maturityDate = string("MATDATE"),
        couponValue = number("COUPONVALUE"),
        couponPeriodDays = number("COUPONPERIOD"),
        couponsPerYear = number("COUPONFREQUENCY"),
        faceValue = number("FACEVALUE"),
        couponPercentFromExchange = number("COUPONPERCENT")
    )
}

class BondMarketInfoClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    fun getInfo(ticker: String): BondMarketInfo {
        val safeTicker = java.net.URLEncoder.encode(ticker.trim().uppercase(Locale.ROOT), Charsets.UTF_8.name())
        val url = "https://iss.moex.com/iss/engines/stock/markets/bonds/securities/$safeTicker.json" +
            "?iss.meta=off&iss.only=securities" +
            "&securities.columns=SECID,MATDATE,COUPONPERCENT,COUPONVALUE,COUPONFREQUENCY,COUPONPERIOD,FACEVALUE"
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
            return parseBondMarketInfo(root)
        }
    }
}
