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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun load(heldTickers: Set<String>): MarketOverviewData = withContext(Dispatchers.IO) {
        // MOEX endpoints are independent. Fetch them concurrently so one slow endpoint
        // cannot keep the dashboard in its loading state for several sequential timeouts.
        val quotes = coroutineScope {
            listOf(
                async(Dispatchers.IO) { quote("USD000UTSTOM", "USD/RUB") },
                async(Dispatchers.IO) { quote("EUR_RUB__TOM", "EUR/RUB") },
                async(Dispatchers.IO) { quote("CNYRUB_TOM", "CNY/RUB") },
                async(Dispatchers.IO) { quote("GLDRUB_TOM", "GLDRUB") }
            ).awaitAll().filterNotNull()
        }
        val movers = coroutineScope {
            listOf(
                async(Dispatchers.IO) { loadMoversFrom("shares", "57") },
                async(Dispatchers.IO) { loadMoversFrom("bonds", "58") }
            ).awaitAll().flatten().distinctBy { it.ticker }
        }.filter { it.ticker.uppercase() in heldTickers }
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

        private fun loadMoversFrom(market: String, boardGroup: String): List<MarketMoverRow> = runCatching {
        val url = "https://iss.moex.com/iss/engines/stock/markets/$market/boardgroups/$boardGroup/securities.json?iss.meta=off&iss.only=securities,marketdata&securities.columns=SECID,SHORTNAME&marketdata.columns=SECID,LAST,CHANGE"
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Мои котировки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Все →", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        if (marketData.quotes.isEmpty()) {
            Text(marketData.error ?: "Загружаем котировки…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                marketData.quotes.take(4).forEach { quote ->
                    Card(Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .62f))) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            MarketInstrumentIcon(quote.ticker, Modifier.size(29.dp))
                            Text(quote.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(quote.price?.let { "${marketMoney(it)} ₽" } ?: "—", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            val change = quote.changePercent
                            Text(change?.let { "${if (it >= 0) "▲" else "▼"}${percent(it)}%" } ?: "—", fontSize = 10.sp, color = change?.let { if (it >= 0) Color(0xFF55C99A) else Color(0xFFE87979) } ?: MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
@Composable
internal fun MarketMoversSection(title: String, rows: List<MarketMoverRow>, positive: Boolean) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))) {
        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (positive) "↗" else "↘", color = if (positive) Color(0xFF55C99A) else Color(0xFFE87979), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2)
            }
            if (rows.isEmpty()) {
                Text("Нет данных по активам портфеля", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else rows.take(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MarketInstrumentIcon(row.ticker, Modifier.size(27.dp))
                    Text(row.name, Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    Text("${if (row.changePercent >= 0) "+" else ""}${percent(row.changePercent)}%", color = if (positive) Color(0xFF55C99A) else Color(0xFFE87979), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
@Composable
internal fun DividendHistorySection(payouts: List<PayoutEntity>) {
    val zone = ZoneId.systemDefault()
    val now = YearMonth.now()
    val months = (1..12).map { YearMonth.of(now.year, it) }.filter { !it.isAfter(now) }
    // The dashboard tracks all cash income, not dividends alone: bond coupons
    // are received payouts too and must be included in both monthly and yearly totals.
    val received = payouts.filter {
        it.status == "RECEIVED" && it.actualDate != null &&
            (it.payoutType == "Дивиденд" || it.payoutType == "Купон")
    }
    val monthValues = months.map { month ->
        received.filter { YearMonth.from(Instant.ofEpochMilli(it.actualDate!!).atZone(zone)) == month }
            .sumOf { (it.reportedNetAmount ?: (it.totalAmount - it.taxAmount)).coerceAtLeast(0.0) }
    }
    val firstYear = minOf(2022, received.minOfOrNull { Instant.ofEpochMilli(it.actualDate!!).atZone(zone).year } ?: now.year)
    val years = (firstYear..now.year).toList()
    val yearValues = years.map { year ->
        received.filter { Instant.ofEpochMilli(it.actualDate!!).atZone(zone).year == year }
            .sumOf { (it.reportedNetAmount ?: (it.totalAmount - it.taxAmount)).coerceAtLeast(0.0) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DividendChartCard("Полученные выплаты", "${marketMoney(monthValues.sum())} ₽ за год", monthValues, months.map { it.monthShortRu() }, Color(0xFF16B5F4), Modifier.weight(1f))
        DividendChartCard("Выплаты по годам", "Дивиденды и купоны", yearValues, years.map(Int::toString), Color(0xFF35CC79), Modifier.weight(1f))
    }
}

@Composable
private fun DividendChartCard(title: String, subtitle: String, values: List<Double>, labels: List<String>, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, minLines = 2)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            DividendBarChart(values = values, labels = labels, barColor = color, modifier = Modifier.fillMaxWidth().height(142.dp))
        }
    }
}


@Composable
private fun MarketInstrumentIcon(ticker: String, modifier: Modifier = Modifier) {
    val key = ticker.uppercase()
    val background = when (key) {
        "USD000UTSTOM" -> Color(0xFF244A87)
        "EUR_RUB__TOM" -> Color(0xFF2454A6)
        "CNYRUB_TOM" -> Color(0xFFB52D35)
        "GLDRUB_TOM" -> Color(0xFFB88B24)
        "SBER", "SBERP" -> Color(0xFF20A66A)
        "YDEX" -> Color(0xFFFF4438)
        "ROSN" -> Color(0xFF252525)
        "TATN" -> Color(0xFF168A59)
        "T" -> Color(0xFFFFD400)
        "LKOH" -> Color(0xFFB51F2A)
        "GAZP" -> Color(0xFF168BCB)
        "X5" -> Color(0xFF00A66C)
        "MOEX" -> Color(0xFF2464A8)
        else -> MaterialTheme.colorScheme.surface
    }
    val mark = when (key) {
        "USD000UTSTOM" -> "$"
        "EUR_RUB__TOM" -> "€"
        "CNYRUB_TOM" -> "¥"
        "GLDRUB_TOM" -> "Au"
        else -> key.take(3)
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(background), contentAlignment = Alignment.Center) {
        Text(mark, color = if (key == "T") Color(0xFF171717) else Color.White,
            fontWeight = FontWeight.Bold, fontSize = if (mark.length > 2) 10.sp else 15.sp)
    }
}

@Composable
private fun DividendBarChart(values: List<Double>, labels: List<String>, barColor: Color, modifier: Modifier = Modifier) {
    val rawMax = max(1.0, values.maxOrNull() ?: 0.0)
    val magnitude = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(rawMax)))
    val normalized = rawMax / magnitude
    val stepFactor = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    val step = stepFactor * magnitude / 4.0
    val axisMax = kotlin.math.ceil(rawMax / step) * step
    val ticks = (0..4).map { axisMax - step * it }
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(122.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(
                Modifier.width(42.dp).fillMaxHeight().padding(vertical = 2.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                ticks.forEach { tick ->
                    val tickLabel = when {
                        tick >= 1_000_000 -> String.format(Locale("ru", "RU"), "%.1fм", tick / 1_000_000.0)
                        tick >= 10_000 -> String.format(Locale("ru", "RU"), "%.0fк", tick / 1_000.0)
                        tick >= 1_000 -> String.format(Locale("ru", "RU"), "%.1fк", tick / 1_000.0)
                        else -> String.format(Locale("ru", "RU"), "%.0f", tick)
                    }
                    Text(tickLabel, fontSize = 9.sp, color = labelColor, maxLines = 1)
                }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val left = 2.dp.toPx()
                val right = size.width - 2.dp.toPx()
                val top = 4.dp.toPx()
                val bottom = size.height - 3.dp.toPx()
                val chartHeight = bottom - top
                for (line in 0..4) {
                    val y = top + chartHeight * line / 4f
                    drawLine(axisColor, Offset(left, y), Offset(right, y), 1.dp.toPx())
                }
                if (values.isNotEmpty()) {
                    val slot = (right - left) / values.size
                    values.forEachIndexed { index, value ->
                        val barHeight = (value / axisMax).toFloat().coerceIn(0f, 1f) * chartHeight
                        val x = left + slot * index + slot * .22f
                        drawRoundRect(
                            barColor,
                            Offset(x, bottom - barHeight),
                            androidx.compose.ui.geometry.Size(slot * .56f, barHeight),
                            androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 46.dp, top = 3.dp)) {
            values.forEachIndexed { index, _ ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (index % 2 == 0 || index == values.lastIndex) {
                        Text(labels.getOrNull(index).orEmpty(), fontSize = 9.sp,
                            color = labelColor, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun List<Double>.runningTotals(): List<Double> { var total = 0.0; return map { total += it; total } }

private fun YearMonth.monthShortRu(): String = when (monthValue) {
    1 -> "янв"; 2 -> "фев"; 3 -> "мар"; 4 -> "апр"; 5 -> "май"; 6 -> "июн"
    7 -> "июл"; 8 -> "авг"; 9 -> "сен"; 10 -> "окт"; 11 -> "ноя"; else -> "дек"
}
private fun percent(value: Double): String = String.format(Locale("ru", "RU"), "%.2f", value)

private fun marketMoney(value: Double): String = String.format(Locale("ru", "RU"), "%,.2f", value)
