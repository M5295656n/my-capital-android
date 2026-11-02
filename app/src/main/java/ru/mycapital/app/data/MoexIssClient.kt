package ru.mycapital.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class MoexQuote(val ticker: String, val price: Double, val board: String?)

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
            "?iss.meta=off&iss.only=marketdata&marketdata.columns=SECID,BOARDID,LAST,WAPRICE,LCURRENTPRICE,MARKETPRICE"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "MyCapital-Android/0.1")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val root = JSONObject(body)
            val table = root.optJSONObject("marketdata") ?: return null
            val columns = table.optJSONArray("columns") ?: return null
            val rows = table.optJSONArray("data") ?: return null
            val indexes = (0 until columns.length()).associate { columns.getString(it) to it }
            fun value(row: org.json.JSONArray, column: String): Double? {
                val index = indexes[column] ?: return null
                if (index >= row.length() || row.isNull(index)) return null
                val number = row.optDouble(index, Double.NaN)
                return number.takeIf { it.isFinite() && it > 0.0 }
            }
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                val secidIndex = indexes["SECID"]
                if (secidIndex != null && row.optString(secidIndex) != ticker.trim().uppercase()) continue
                val price = value(row, "LAST")
                    ?: value(row, "WAPRICE")
                    ?: value(row, "LCURRENTPRICE")
                    ?: value(row, "MARKETPRICE")
                    ?: continue
                val boardIndex = indexes["BOARDID"]
                val board = boardIndex?.let { row.optString(it).takeIf(String::isNotBlank) }
                return MoexQuote(ticker.trim().uppercase(), price, board)
            }
            return null
        }
    }
}
