package ru.mycapital.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ru.mycapital.app.data.PayoutEntity
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

internal data class MarketQuoteRow(val ticker: String, val label: String, val price: Double?, val changePercent: Double?)
internal data class MarketMoverRow(val ticker: String, val name: String, val price: Double, val change: Double, val changePercent: Double)
internal data class MarketOverviewData(
    val quotes: List<MarketQuoteRow> = emptyList(),
    val gainers: List<MarketMoverRow> = emptyList(),
    val losers: List<MarketMoverRow> = emptyList(),
    val error: String? = null
)

internal class MarketOverviewClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun load(): MarketOverviewData = withContext(Dispatchers.IO) {
        val quotes = listOf(
            quote("USD000UTSTOM", "USD/RUB"),
            quote("EUR_RUB__TOM", "EUR/RUB"),
            quote("CNYRUB_TOM", "CNY/RUB"),
            quote("GLDRUB_TOM", "GLDRUB")
        ).filterNotNull()
        val movers = loadMovers()
        MarketOverviewData(
            quotes = quotes,
            gainers = movers.sortedByDescending { it.changePercent }.take(5),
            losers = movers.sortedBy { it.changePercent }.take(5),
            error = if (quotes.isEmpty() && movers.isEmpty()) "Рыночные данные пока недоступны" else null
        )
    }

    private fun quote(ticker: String, label: String): MarketQuoteRow? = runCatching {
        val market = if (ticker == "USD000UTSTOM" || ticker == "EUR_RUB__TOM" || ticker == "CNYRUB_TOM") "currency/selt" else "currency/selt"
        val url = "https://iss.moex.com/iss/engines/currency/markets/selt/securities/$ticker.json?iss.meta=off&iss.only=marketdata&marketdata.columns=SECID,LAST,CHANGE"
        val json = getJson(url)
        val table = json.optJSONObject("marketdata") ?: return@runCatching null
        val columns = table.optJSONArray("columns") ?: return@runCatching null
        val rows = table.optJSONArray("data") ?: return@runCatching null
        val indexes = (0 until columns.length()).associate { columns.optString(it) to it }
        val row = (0 until rows.length()).mapNotNull { rows.optJSONArray(it) }
            .firstOrNull { it.optString(indexes["SECID"] ?: -1) == ticker } ?: return@runCatching null
        val price = row.optDouble(indexes["LAST"] ?: -1, Double.NaN).takeIf { it.isFinite() && it > 0.0 }
        val change = row.optDouble(indexes["CHANGE"] ?: -1, Double.NaN).takeIf { it.isFinite() }
        MarketQuoteRow(ticker, label, price, change?.let { delta -> price?.let { p -> if (p - delta != 0.0) delta / (p - delta) * 100.0 else null } })
    }.getOrNull()

    private fun loadMovers(): List<MarketMoverRow> = runCatching {
        val url = "https://iss.moex.com/iss/engines/stock/markets/shares/boardgroups/57/securities.json?iss.meta=off&iss.only=securities,marketdata&securities.columns=SECID,SHORTNAME&marketdata.columns=SECID,LAST,CHANGE"
        val json = getJson(url)
        val securities = json.optJSONObject("securities") ?: return@runCatching emptyList()
        val secCols = securities.optJSONArray("columns") ?: return@runCatching emptyList()
        val secRows = securities.optJSONArray("data") ?: return@runCatching emptyList()
        val secIndexes = (0 until secCols.length()).associate { secCols.optString(it) to it }
        val names = (0 until secRows.length()).mapNotNull { i ->
            val row = secRows.optJSONArray(i) ?: return@mapNotNull null
            val ticker = row.optString(secIndexes["SECID"] ?: -1)
            if (ticker.isBlank()) null else ticker to row.optString(secIndexes["SHORTNAME"] ?: -1, ticker)
        }.toMap()
        val table = json.optJSONObject("marketdata") ?: return@runCatching emptyList()
        val cols = table.optJSONArray("columns") ?: return@runCatching emptyList()
        val rows = table.optJSONArray("data") ?: return@runCatching emptyList()
        val ix = (0 until cols.length()).associate { cols.optString(it) to it }
        (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONArray(i) ?: return@mapNotNull null
            val ticker = row.optString(ix["SECID"] ?: -1)
            val price = row.optDouble(ix["LAST"] ?: -1, Double.NaN)
            val change = row.optDouble(ix["CHANGE"] ?: -1, Double.NaN)
            val previous = price - change
            if (ticker.isBlank() || !price.isFinite() || price <= 0.0 || !change.isFinite() || previous <= 0.0) null
            else MarketMoverRow(ticker, names[ticker] ?: ticker, price, change, change / previous * 100.0)
        }.filter { it.changePercent.isFinite() && it.changePercent != 0.0 }
    }.getOrDefault(emptyList())

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "MyCapital-Android/0.3").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("MOEX ISS: HTTP ${response.code}")
            return JSONObject(response.body?.string() ?: throw IllegalStateException("Пустой ответ MOEX ISS"))
        }
    }
}

@Composable
internal fun MarketQuotesSection(marketData: MarketOverviewData) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Мои котировки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("MOEX", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
        if (marketData.quotes.isEmpty()) {
            Text(marketData.error ?: "Загружаем котировки…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            marketData.quotes.forEach { quote ->
                Card(shape = RoundedCornerShape(15.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .62f))) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                            Text(quote.ticker.take(2), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Text(quote.label, Modifier.weight(1f).padding(start = 12.dp), fontWeight = FontWeight.SemiBold)
                        Text(quote.price?.let { "${money(it)} ₽" } ?: "—", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.size(10.dp))
                        val change = quote.changePercent
                        Text(change?.let { "${if (it >= 0) "▲" else "▼"} ${percent(it)}%" } ?: "—",
                            color = change?.let { if (it >= 0) Color(0xFF55C99A) else Color(0xFFE87979) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MarketMoversSection(title: String, rows: List<MarketMoverRow>, positive: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (rows.isEmpty()) {
            Text("Нет доступных данных по дневному изменению.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            rows.forEach { row ->
                val tint = if (positive) Color(0xFF55C99A) else Color(0xFFE87979)
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Text(row.ticker.take(4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(row.ticker, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${money(row.price)} ₽", fontWeight = FontWeight.SemiBold)
                        Text("${if (row.change >= 0) "+" else ""}${money(row.change)} ₽  ${if (row.change >= 0) "▲" else "▼"} ${percent(row.changePercent)}%",
                            color = tint, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DividendHistorySection(payouts: List<PayoutEntity>) {
    val zone = ZoneId.systemDefault()
    val now = YearMonth.now()
    val months = (11 downTo 0).map { now.minusMonths(it.toLong()) }
    val receivedByMonth = payouts.filter { it.status == "RECEIVED" && it.actualDate != null }
        .groupBy { YearMonth.from(Instant.ofEpochMilli(it.actualDate!!).atZone(zone)) }
        .mapValues { (_, items) -> items.sumOf { (it.reportedNetAmount ?: (it.totalAmount - it.taxAmount)).coerceAtLeast(0.0) } }
    val values = months.map { receivedByMonth[it] ?: 0.0 }
    val annual = values.sum()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Полученные дивиденды", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${money(annual)} ₽ за 12 месяцев", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DividendBarChart(values = values, labels = months.map { it.monthShortRu() }, barColor = Color(0xFF16B5F4), modifier = Modifier.fillMaxWidth().height(190.dp))
            }
        }
        Text("Рост дивидендов", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Динамика выплат по месяцам", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                DividendBarChart(values = values, labels = months.map { it.monthShortRu() }, barColor = Color(0xFF9A67F5), modifier = Modifier.fillMaxWidth().height(190.dp))
            }
        }
    }
}

@Composable
private fun DividendBarChart(values: List<Double>, labels: List<String>, barColor: Color, modifier: Modifier = Modifier) {
    val maxValue = max(1.0, values.maxOrNull() ?: 0.0)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f)
    Canvas(modifier) {
        val left = 8.dp.toPx()
        val right = size.width - 6.dp.toPx()
        val top = 10.dp.toPx()
        val bottom = size.height - 28.dp.toPx()
        val chartHeight = bottom - top
        for (line in 0..4) {
            val y = top + chartHeight * line / 4f
            drawLine(axisColor, Offset(left, y), Offset(right, y), 1.dp.toPx())
        }
        val slot = (right - left) / values.size
        values.forEachIndexed { index, value ->
            val barHeight = (value / maxValue).toFloat() * chartHeight
            val x = left + slot * index + slot * .22f
            drawRoundRect(barColor, Offset(x, bottom - barHeight), androidx.compose.ui.geometry.Size(slot * .56f, barHeight), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
    }
}

private fun YearMonth.monthShortRu(): String = when (monthValue) {
    1 -> "янв"; 2 -> "фев"; 3 -> "мар"; 4 -> "апр"; 5 -> "май"; 6 -> "июн"
    7 -> "июл"; 8 -> "авг"; 9 -> "сен"; 10 -> "окт"; 11 -> "ноя"; else -> "дек"
}
private fun percent(value: Double): String = String.format(Locale("ru", "RU"), "%.2f", value)
