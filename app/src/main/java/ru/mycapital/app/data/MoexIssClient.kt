package ru.mycapital.app.data

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class UnsupportedMoexInstrumentException(message: String) : IOException(message)

data class MoexQuote(val ticker: String, val price: Double, val board: String?)

internal fun preferredMoexBoards(assetType: String): List<String> = when {
    assetType.contains("облигац", ignoreCase = true) ->
        // Government bonds (OFZ) trade on TQOB; corporate and municipal bonds on TQCB.
        listOf("TQOB", "TQCB")
    assetType.contains("акци", ignoreCase = true) -> listOf("TQBR")
    assetType.contains("фонд", ignoreCase = true) || assetType.contains("пай", ignoreCase = true) -> listOf("TQTF")
    else -> emptyList()
}

/** MOEX bond prices are quoted as a percentage of face value; add accrued coupon interest to get dirty price. */
internal fun normalizeMoexPrice(price: Double, assetType: String, faceValue: Double?, accruedInterest: Double? = 0.0): Double {
    if (!assetType.contains("облигац", ignoreCase = true)) return price
    require(faceValue != null && faceValue.isFinite() && faceValue > 0.0) {
        "MOEX ISS не вернул номинал облигации; котировка не обновлена."
    }
    require(accruedInterest != null && accruedInterest.isFinite() && accruedInterest >= 0.0) {
        "MOEX ISS не вернул корректный НКД; котировка не обновлена."
    }
    return price * faceValue / 100.0 + accruedInterest
}

class MoexIssClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    fun getQuote(ticker: String, assetType: String): MoexQuote? {
        val market = if (assetType.contains("облигац", ignoreCase = true)) "bonds" else "shares"
        val safeTicker = java.net.URLEncoder.encode(ticker.trim().uppercase(), Charsets.UTF_8.name())
        val url = "https://iss.moex.com/iss/engines/stock/markets/$market/securities/$safeTicker.json" +
            "?iss.meta=off&iss.only=securities,marketdata&securities.columns=SECID,FACEVALUE&marketdata.columns=SECID,BOARDID,LAST,WAPRICE,LCURRENTPRICE,MARKETPRICE,ACCRUEDINT"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "MyCapital-Android/0.1")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 400 || response.code == 404) {
                throw UnsupportedMoexInstrumentException("MOEX ISS не поддерживает инструмент $ticker (HTTP ${response.code})")
            }
            if (!response.isSuccessful) throw IOException("MOEX ISS вернул HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Пустой ответ MOEX ISS")
            val root = try {
                JSONObject(body)
            } catch (error: Exception) {
                throw IOException("Некорректный JSON от MOEX ISS", error)
            }
            val faceValue = if (market == "bonds") {
                val securities = root.optJSONObject("securities")
                val securityColumns = securities?.optJSONArray("columns")
                val securityRows = securities?.optJSONArray("data")
                val faceIndex = securityColumns?.let { cols -> (0 until cols.length()).firstOrNull { cols.optString(it) == "FACEVALUE" } }
                val idIndex = securityColumns?.let { cols -> (0 until cols.length()).firstOrNull { cols.optString(it) == "SECID" } }
                (0 until (securityRows?.length() ?: 0)).firstNotNullOfOrNull { index ->
                    val securityRow = securityRows?.optJSONArray(index) ?: return@firstNotNullOfOrNull null
                    if (idIndex != null && securityRow.optString(idIndex) != ticker.trim().uppercase()) return@firstNotNullOfOrNull null
                    faceIndex?.let { securityRow.optDouble(it, Double.NaN) }?.takeIf { it.isFinite() && it > 0.0 }
                }
            } else null
            val table = root.optJSONObject("marketdata")
                ?: throw IOException("В ответе MOEX ISS отсутствует таблица котировок")
            val columns = table.optJSONArray("columns")
                ?: throw IOException("В таблице котировок MOEX ISS отсутствуют столбцы")
            val rows = table.optJSONArray("data")
                ?: throw IOException("В таблице котировок MOEX ISS отсутствуют данные")
            val indexes = (0 until columns.length()).associate { columns.getString(it) to it }
            fun value(row: org.json.JSONArray, column: String): Double? {
                val index = indexes[column] ?: return null
                if (index >= row.length() || row.isNull(index)) return null
                val number = row.optDouble(index, Double.NaN)
                return number.takeIf { it.isFinite() && it > 0.0 }
            }
            val boardIndex = indexes["BOARDID"]
            val secidIndex = indexes["SECID"]
            val matchingRows = (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONArray(index) ?: return@mapNotNull null
                if (secidIndex != null && row.optString(secidIndex) != ticker.trim().uppercase()) return@mapNotNull null
                val board = boardIndex?.let { row.optString(it).takeIf(String::isNotBlank) }
                row to board
            }
            val preferredBoards = preferredMoexBoards(assetType)
            // MOEX returns several market boards for one security. OFZ and corporate
            // bonds use different main boards (TQOB and TQCB respectively), so accept
            // either main bond board and rank them explicitly instead of rejecting
            // corporate-bond quotes or falling through to an auxiliary board.
            val selectedRows = if (preferredBoards.isNotEmpty()) {
                preferredBoards.flatMap { board -> matchingRows.filter { it.second == board } }
            } else matchingRows
            for ((row, board) in selectedRows) {
                val price = value(row, "LAST")
                    ?: value(row, "WAPRICE")
                    ?: value(row, "LCURRENTPRICE")
                    ?: value(row, "MARKETPRICE")
                    ?: continue
                val accruedInterest = if (market == "bonds") {
                    val accruedIndex = indexes["ACCRUEDINT"]
                        ?: throw IOException("MOEX ISS не вернул поле ACCRUEDINT для облигации $ticker")
                    if (accruedIndex >= row.length() || row.isNull(accruedIndex)) {
                        throw IOException("MOEX ISS не вернул НКД для облигации $ticker; котировка не обновлена.")
                    }
                    row.optDouble(accruedIndex, Double.NaN).takeIf { it.isFinite() && it >= 0.0 }
                        ?: throw IOException("MOEX ISS вернул некорректный НКД для облигации $ticker")
                } else 0.0
                val normalizedPrice = try {
                    normalizeMoexPrice(price, assetType, faceValue, accruedInterest)
                } catch (error: IllegalArgumentException) {
                    throw IOException(error.message ?: "Не удалось пересчитать цену облигации", error)
                }
                return MoexQuote(ticker.trim().uppercase(), normalizedPrice, board)
            }
            return null
        }
    }
}
