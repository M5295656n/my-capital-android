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

/** Decide whether a holding should receive dividend-history processing. */
internal fun shouldIncludeInDividendHistory(assetType: String, moexClassification: Boolean?): Boolean {
    if (assetType.contains("акци", ignoreCase = true) || assetType.contains("share", ignoreCase = true)) return true
    if (listOf("облигац", "офз", "bond", "фонд", "пай", "валют", "металл").any {
            assetType.contains(it, ignoreCase = true)
        }) return false
    // If metadata is unavailable, let the history endpoint validate the ticker.
    return moexClassification != false
}

/** Parse the equity class from the MOEX ISS description dictionary. */
internal fun parseMoexEquityClassification(body: String): Boolean? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val block = root.optJSONObject("description") ?: return null
    val columns = block.optJSONArray("columns") ?: return null
    val rows = block.optJSONArray("data") ?: return null
    val indexes = (0 until columns.length()).associate { columns.optString(it).lowercase(Locale.ROOT) to it }
    val nameIndex = indexes["name"] ?: return null
    val valueIndex = indexes["value"] ?: return null
    var type = ""
    var group = ""
    for (i in 0 until rows.length()) {
        val row = rows.optJSONArray(i) ?: continue
        if (nameIndex >= row.length() || valueIndex >= row.length()) continue
        when (row.optString(nameIndex).uppercase(Locale.ROOT)) {
            "TYPENAME" -> type = row.optString(valueIndex).lowercase(Locale.ROOT)
            "GROUPNAME" -> group = row.optString(valueIndex).lowercase(Locale.ROOT)
        }
    }
    if (type.isBlank() && group.isBlank()) return null
    if (listOf("облигац", "bond", "фонд", "пай", "валют", "драгметалл").any { type.contains(it) || group.contains(it) }) return false
    return type.startsWith("акция") || type.startsWith("share") || group.contains("акци") || group.contains("shares")
}
class UnsupportedCorporateEventsInstrumentException(message: String) : IOException(message)

data class ImportedPayoutEvent(
    val ticker: String,
    val type: String,
    val amountPerUnit: Double,
    val eventDate: Long,
    val sourceKey: String
)

internal fun parseBondMaturityEvent(
    ticker: String,
    body: String,
    today: LocalDate = LocalDate.now(ZoneId.of("Europe/Moscow"))
): ImportedPayoutEvent? {
    // Keep this parser JVM-testable: org.json is an Android platform stub in local unit tests.
    val values = Regex("""\[\s*"([^"]+)"\s*,\s*(?:"([^"]*)"|null)\s*\]""")
        .findAll(body)
        .associate { match ->
            match.groupValues[1].uppercase(Locale.ROOT) to match.groupValues[2]
        }
    val maturityDate = parseMoexEventDate(values["MATDATE"].orEmpty()) ?: return null
    if (!isEventDateUpcoming(maturityDate, today)) return null
    val faceValue = values["FACEVALUE"]?.replace(',', '.')?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val dateText = maturityDate.toString()
    val normalizedTicker = ticker.trim().uppercase(Locale.ROOT)
    return ImportedPayoutEvent(
        ticker = normalizedTicker,
        type = "Погашение",
        amountPerUnit = faceValue,
        eventDate = maturityDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        sourceKey = "$normalizedTicker|Погашение|$dateText"
    )
}

class CorporateEventsClient {
    private val classificationClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()

    fun isEquityForDividendHistory(ticker: String, assetType: String): Boolean {
        if (assetType.contains("акци", ignoreCase = true) || assetType.contains("share", ignoreCase = true)) return true
        if (listOf("облигац", "офз", "bond", "фонд", "пай", "валют", "металл").any {
                assetType.contains(it, ignoreCase = true)
            }) return false
        val encoded = java.net.URLEncoder.encode(ticker.trim().uppercase(Locale.ROOT), Charsets.UTF_8.name())
        val url = "https://iss.moex.com/iss/securities/$encoded.json?iss.meta=off&iss.only=description&description.columns=name,value"
        val request = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "MyCapital-Android/0.4").build()
        val classification = runCatching {
            classificationClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else parseMoexEquityClassification(response.body?.string().orEmpty())
            }
        }.getOrNull()
        return shouldIncludeInDividendHistory(assetType, classification)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    fun getEvents(ticker: String, assetType: String): List<ImportedPayoutEvent> {
        val isBond = isBondInstrument(ticker = ticker, assetType = assetType)
        val encoded = java.net.URLEncoder.encode(ticker.trim().uppercase(), Charsets.UTF_8.name())
        val url = if (isBond) {
            "https://iss.moex.com/iss/securities/$encoded/bondization.json" +
                "?iss.meta=off&iss.only=coupons,amortizations&coupons.columns=coupondate,value" +
                "&amortizations.columns=amortdate,value&start=0&limit=100"
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
            val events = mutableListOf<ImportedPayoutEvent>()
            fun appendRows(block: JSONObject, dateColumn: String, type: String) {
                val columns = block.optJSONArray("columns") ?: return
                val rows = block.optJSONArray("data") ?: return
                val indexes = (0 until columns.length()).associate {
                    columns.getString(it).lowercase(Locale.ROOT) to it
                }
                val dateIndex = indexes[dateColumn] ?: return
                val amountIndex = listOf("value", "issuevalue", "couponvalue")
                    .firstNotNullOfOrNull { indexes[it] } ?: return
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONArray(i) ?: continue
                    if (dateIndex >= row.length() || amountIndex >= row.length()) continue
                    val dateText = row.optString(dateIndex)
                    val localDate = parseMoexEventDate(dateText) ?: continue
                    val amount = if (row.isNull(amountIndex)) Double.NaN else row.optDouble(amountIndex, Double.NaN)
                    if (!amount.isFinite() || amount <= 0.0 || !isEventDateUpcoming(localDate)) continue
                    val normalizedTicker = ticker.trim().uppercase(Locale.ROOT)
                    val date = localDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                    events += ImportedPayoutEvent(
                        normalizedTicker, type, amount, date, "$normalizedTicker|$type|$dateText"
                    )
                }
            }
            if (isBond) {
                appendRows(table, "coupondate", "Купон")
                root.optJSONObject("amortizations")?.let { appendRows(it, "amortdate", "Амортизация") }
                // The ISS bondization schedule may omit final principal repayment. Add the
                // maturity from the security description, unless an amortization already
                // represents principal repayment on that exact date.
                val maturity = runCatching {
                    val maturityUrl = "https://iss.moex.com/iss/securities/$encoded.json" +
                        "?iss.meta=off&iss.only=description&description.columns=name,value"
                    val maturityRequest = Request.Builder().url(maturityUrl)
                        .header("Accept", "application/json")
                        .header("User-Agent", "MyCapital-Android/0.1").build()
                    client.newCall(maturityRequest).execute().use { maturityResponse ->
                        if (!maturityResponse.isSuccessful) null
                        else maturityResponse.body?.string()?.let { parseBondMaturityEvent(ticker, it) }
                    }
                }.getOrNull()
                if (maturity != null && events.none {
                    it.type == "Амортизация" && it.eventDate == maturity.eventDate
                }) events += maturity
            } else {
                appendRows(table, "paymentdate", "Дивиденд")
            }
            return events.distinctBy { it.sourceKey }
        }
    }
}
