package ru.mycapital.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

data class MoexDailyClose(
    val date: LocalDate,
    /** For shares: RUB per share. For bonds: dirty RUB price per bond (clean price + accrued coupon). */
    val close: Double
)

internal data class MoexBondCoupon(
    val couponDate: LocalDate,
    val value: Double?,
    val startDate: LocalDate? = null
)

internal data class MoexBondAmortization(
    val date: LocalDate,
    val value: Double
)

/**
 * Delayed daily candles and instrument data from MOEX ISS.
 * Bond candles are quoted as a percentage of face value; they are converted
 * into dirty RUB prices per bond using the face value, amortizations and
 * accrued coupon interest reconstructed from the published coupon schedule.
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
        return when {
            type.contains("акци") || type.contains("фонд") || type.contains("пай") ->
                loadCandles(ticker, "shares", fromDate, tillDate)
            type.contains("облиг") || type.contains("офз") ->
                loadBondCloses(ticker, fromDate, tillDate)
            else -> throw IllegalArgumentException("Тип актива не поддерживается историческим графиком")
        }
    }

    private fun loadBondCloses(
        ticker: String,
        fromDate: LocalDate,
        tillDate: LocalDate
    ): List<MoexDailyClose> {
        val safeTicker = encodeTicker(ticker)
        val description = getJson(
            "https://iss.moex.com/iss/securities/$safeTicker.json?iss.meta=off&iss.only=description&description.columns=name,value"
        )
        val terms = parseBondTerms(description)
        require(terms.faceUnit.equals("RUB", ignoreCase = true) || terms.faceUnit.equals("SUR", ignoreCase = true)) {
            "Историческая оценка облигаций в валюте, отличной от RUB, пока не поддерживается"
        }

        val schedule = getJson(
            "https://iss.moex.com/iss/securities/$safeTicker/bondization.json?iss.meta=off&iss.only=coupons,amortizations&limit=unlimited"
        )
        val coupons = parseBondCoupons(schedule)
        require(coupons.isNotEmpty()) { "MOEX ISS не вернул график купонов для $ticker" }
        val amortizations = parseBondAmortizations(schedule)

        val candles = loadCandles(ticker, "bonds", fromDate, tillDate)
        val dirtyPrices = candles.mapNotNull { candle ->
            val accrued = calculateBondAccruedInterest(candle.date, coupons) ?: return@mapNotNull null
            val nominal = historicalBondFaceValue(terms.faceValue, amortizations, candle.date, tillDate)
            if (nominal <= 0.0) return@mapNotNull null
            val dirtyPriceRub = calculateBondDirtyPrice(candle.close, nominal, accrued)
            if (dirtyPriceRub.isFinite() && dirtyPriceRub > 0.0) {
                MoexDailyClose(candle.date, dirtyPriceRub)
            } else null
        }
        if (dirtyPrices.isEmpty()) {
            throw IOException("Не удалось рассчитать историческую цену $ticker: нет полного графика купонов для периода")
        }
        return dirtyPrices
    }

    private fun loadCandles(
        ticker: String,
        market: String,
        fromDate: LocalDate,
        tillDate: LocalDate
    ): List<MoexDailyClose> {
        val safeTicker = encodeTicker(ticker)
        val baseUrl = "https://iss.moex.com/iss/engines/stock/markets/$market/securities/$safeTicker/candles.json"
        val result = linkedMapOf<LocalDate, MoexDailyClose>()
        var start = 0

        repeat(50) {
            val url = "$baseUrl?from=$fromDate&till=$tillDate&interval=24&start=$start" +
                "&iss.meta=off&iss.only=candles&candles.columns=begin,close"
            val page = parseDailyCandles(getJson(url))
            if (page.isEmpty()) return result.values.sortedBy { it.date }
            page.forEach { result[it.date] = it }
            if (page.size < 500) return result.values.sortedBy { it.date }
            start += page.size
        }
        return result.values.sortedBy { it.date }
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "MyCapital-Android/0.2.8")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("MOEX ISS вернул HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Пустой ответ MOEX ISS")
            try {
                JSONObject(body)
            } catch (error: Exception) {
                throw IOException("Некорректный JSON от MOEX ISS", error)
            }
        }
    }
}

internal data class MoexBondTerms(val faceValue: Double, val faceUnit: String)

internal fun parseBondTerms(json: JSONObject): MoexBondTerms {
    val description = json.optJSONObject("description")
        ?: throw IOException("MOEX ISS не вернул спецификацию облигации")
    val columns = description.optJSONArray("columns")
        ?: throw IOException("В спецификации облигации отсутствуют столбцы")
    val rows = description.optJSONArray("data")
        ?: throw IOException("В спецификации облигации отсутствуют данные")
    val indexes = (0 until columns.length()).associate { columns.getString(it).lowercase() to it }
    val nameIndex = indexes["name"] ?: throw IOException("В спецификации облигации нет названий полей")
    val valueIndex = indexes["value"] ?: throw IOException("В спецификации облигации нет значений полей")
    val fields = (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONArray(index) ?: return@mapNotNull null
        if (row.isNull(nameIndex) || row.isNull(valueIndex)) return@mapNotNull null
        row.optString(nameIndex).uppercase() to row.optString(valueIndex)
    }.toMap()
    val faceValue = fields["FACEVALUE"]?.toDoubleOrNull()
        ?: fields["INITIALFACEVALUE"]?.toDoubleOrNull()
        ?: throw IOException("MOEX ISS не вернул номинал облигации")
    val faceUnit = fields["FACEUNIT"]?.takeIf(String::isNotBlank)
        ?: throw IOException("MOEX ISS не вернул валюту номинала облигации")
    if (!faceValue.isFinite() || faceValue <= 0.0) throw IOException("Некорректный номинал облигации")
    return MoexBondTerms(faceValue, faceUnit)
}

internal fun parseBondCoupons(json: JSONObject): List<MoexBondCoupon> =
    parseBondScheduleRows(json, "coupons", "coupondate", "value").mapNotNull { row ->
        val date = row.first ?: return@mapNotNull null
        val value = row.second
        val start = row.third
        if (value != null && (!value.isFinite() || value < 0.0)) null else MoexBondCoupon(date, value, start)
    }.distinctBy { it.couponDate }.sortedBy { it.couponDate }

internal fun parseBondAmortizations(json: JSONObject): List<MoexBondAmortization> =
    parseBondScheduleRows(json, "amortizations", "amortdate", "value").mapNotNull { row ->
        val date = row.first ?: return@mapNotNull null
        val value = row.second ?: return@mapNotNull null
        if (!value.isFinite() || value <= 0.0) null else MoexBondAmortization(date, value)
    }.distinctBy { it.date }.sortedBy { it.date }

private fun parseBondScheduleRows(
    json: JSONObject,
    blockName: String,
    dateColumn: String,
    valueColumn: String
): List<Triple<LocalDate?, Double?, LocalDate?>> {
    val block = json.optJSONObject(blockName) ?: return emptyList()
    val columns = block.optJSONArray("columns") ?: return emptyList()
    val rows = block.optJSONArray("data") ?: return emptyList()
    val indexes = (0 until columns.length()).associate { columns.getString(it).lowercase() to it }
    val dateIndex = indexes[dateColumn] ?: return emptyList()
    val valueIndex = indexes[valueColumn] ?: return emptyList()
    val startIndex = indexes["startdate"]
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            val date = if (row.isNull(dateIndex)) null else runCatching {
                LocalDate.parse(row.optString(dateIndex).take(10))
            }.getOrNull()
            val value = if (row.isNull(valueIndex)) null else row.optString(valueIndex).toDoubleOrNull()
            val startDate = if (startIndex == null || row.isNull(startIndex)) null else runCatching {
                LocalDate.parse(row.optString(startIndex).take(10))
            }.getOrNull()
            add(Triple(date, value, startDate))
        }
    }
}

/**
 * Coupon value is the scheduled cash payment per bond. Accrued coupon interest
 * is accrued linearly over the actual coupon period (actual/actual day count).
 */
internal fun calculateBondAccruedInterest(
    date: LocalDate,
    coupons: List<MoexBondCoupon>
): Double? {
    val ordered = coupons.sortedBy { it.couponDate }
    val next = ordered.firstOrNull { it.couponDate.isAfter(date) } ?: return null
    val previous = ordered.lastOrNull { !it.couponDate.isAfter(date) }
    val periodStart = previous?.couponDate ?: next.startDate ?: return null
    if (date.isBefore(periodStart)) return null
    val couponValue = next.value ?: return null
    val totalDays = ChronoUnit.DAYS.between(periodStart, next.couponDate)
    val accruedDays = ChronoUnit.DAYS.between(periodStart, date)
    if (!couponValue.isFinite() || couponValue < 0.0 || totalDays <= 0L ||
        accruedDays < 0L || accruedDays > totalDays
    ) return null
    return couponValue * accruedDays.toDouble() / totalDays.toDouble()
}

/**
 * FACEVALUE in MOEX instrument metadata is today's outstanding nominal.
 * Reconstruct a past nominal by adding back amortizations paid after the
 * historical date, but not payments later than the current valuation date.
 */
internal fun historicalBondFaceValue(
    currentFaceValue: Double,
    amortizations: List<MoexBondAmortization>,
    historicalDate: LocalDate,
    currentDate: LocalDate
): Double {
    require(currentFaceValue.isFinite() && currentFaceValue > 0.0)
    return currentFaceValue + amortizations.asSequence()
        .filter { it.date.isAfter(historicalDate) && !it.date.isAfter(currentDate) }
        .sumOf { it.value }
}

internal fun calculateBondDirtyPrice(
    cleanPricePercent: Double,
    outstandingFaceValue: Double,
    accruedInterest: Double
): Double {
    require(cleanPricePercent.isFinite() && cleanPricePercent > 0.0)
    require(outstandingFaceValue.isFinite() && outstandingFaceValue > 0.0)
    require(accruedInterest.isFinite() && accruedInterest >= 0.0)
    return outstandingFaceValue * cleanPricePercent / 100.0 + accruedInterest
}

internal fun parseDailyCandles(json: JSONObject): List<MoexDailyClose> {
    val candles = json.optJSONObject("candles")
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
    }.distinctBy { it.date }.sortedBy { it.date }

private fun encodeTicker(ticker: String): String =
    URLEncoder.encode(ticker.trim().uppercase(), StandardCharsets.UTF_8.name())
