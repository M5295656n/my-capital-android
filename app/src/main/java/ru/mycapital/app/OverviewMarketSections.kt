package ru.mycapital.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
            val currencyQuotes = async(Dispatchers.IO) { cbrCurrencyQuotes() }
            val goldQuote = async(Dispatchers.IO) { quote("GLDRUB_TOM", "Золото ₽/г") }
            currencyQuotes.await() + listOfNotNull(goldQuote.await())
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

    // Use one Bank of Russia rates snapshot for USD, EUR and CNY so all three
    // currency cards share the same publication date and previous-rate basis.
    private fun cbrCurrencyQuotes(): List<MarketQuoteRow> = runCatching {
        val request = Request.Builder()
            .url("https://www.cbr-xml-daily.ru/daily_json.js")
            .header("Accept", "application/json")
            .header("User-Agent", "MyCapital-Android/0.3.9")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Курсы валют: HTTP ${response.code}")
            response.body?.string() ?: throw IllegalStateException("Пустой ответ с курсами валют")
        }
        listOfNotNull(
            parseCbrCurrencyRateJson(body, "USD", "USD_RUB_CBR", "USD/RUB"),
            parseCbrCurrencyRateJson(body, "EUR", "EUR_RUB_CBR", "EUR/RUB"),
            parseCbrCurrencyRateJson(body, "CNY", "CNY_RUB_CBR", "CNY/RUB")
        )
    }.getOrDefault(emptyList())

    // Gold is traded on MOEX's FX and precious-metals market, main board CETS.
    // Request the board explicitly and retain the last published price outside session.
    private fun quote(ticker: String, label: String): MarketQuoteRow? = runCatching {
        val url = "https://iss.moex.com/iss/engines/currency/markets/selt/boards/CETS/securities/$ticker.json?iss.meta=off&iss.only=marketdata&marketdata.columns=SECID,LAST,CHANGE,PREVPRICE,MARKETPRICE"
        val json = getJson(url)
        val table = json.optJSONObject("marketdata") ?: return@runCatching null
        val columns = table.optJSONArray("columns") ?: return@runCatching null
        val rows = table.optJSONArray("data") ?: return@runCatching null
        val indexes = (0 until columns.length()).associate { columns.optString(it) to it }
        val row = (0 until rows.length()).mapNotNull { rows.optJSONArray(it) }
            .firstOrNull { it.optString(indexes["SECID"] ?: -1) == ticker } ?: return@runCatching null
        fun value(column: String): Double? {
            val index = indexes[column] ?: return null
            return row.optDouble(index, Double.NaN).takeIf { it.isFinite() && it > 0.0 }
        }
        val last = value("LAST")
        val marketPrice = value("MARKETPRICE")
        val previous = value("PREVPRICE")
        val price = last ?: marketPrice ?: previous
        val change = indexes["CHANGE"]?.let { row.optDouble(it, Double.NaN) }
            ?.takeIf { it.isFinite() }
        val changePercent = when {
            last != null && previous != null -> calculateQuoteChangePercent(last, previous)
            last != null && change != null -> calculateQuoteChangePercent(last, last - change)
            marketPrice != null && previous != null -> calculateQuoteChangePercent(marketPrice, previous)
            else -> null
        }
        MarketQuoteRow(ticker, label, price, changePercent)
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

internal fun calculateQuoteChangePercent(current: Double, previous: Double): Double? {
    if (!current.isFinite() || !previous.isFinite() || current <= 0.0 || previous <= 0.0) return null
    return ((current - previous) / previous * 100.0).takeIf { it.isFinite() }
}

internal fun parseCbrCurrencyRateJson(
    json: String,
    currencyCode: String,
    ticker: String,
    label: String
): MarketQuoteRow? {
    val currencyFields = Regex("\\\"$currencyCode\\\"\\s*:\\s*\\{([^}]*)\\}", RegexOption.DOT_MATCHES_ALL)
        .find(json)?.groupValues?.getOrNull(1) ?: return null

    fun rate(field: String): Double? =
        Regex("\\\"$field\\\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
            .find(currencyFields)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    val nominal = rate("Nominal") ?: 1.0
    if (!nominal.isFinite() || nominal <= 0.0) return null
    val current = (rate("Value") ?: return null) / nominal
    val previous = rate("Previous")?.div(nominal)
    if (!current.isFinite() || current <= 0.0) return null
    return MarketQuoteRow(
        ticker = ticker,
        label = label,
        price = current,
        changePercent = previous?.let { calculateQuoteChangePercent(current, it) }
    )
}

internal fun parseEuroRateJson(json: String): MarketQuoteRow? =
    parseCbrCurrencyRateJson(json, "EUR", "EUR_RUB_CBR", "EUR/RUB")

@Composable
internal fun MarketQuotesSection(marketData: MarketOverviewData) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Мои котировки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
    val months = (1..12).map { YearMonth.of(now.year, it) }.filter { !it.isAfter(now) }.takeLast(6)
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
    val years = ((now.year - 3)..now.year).toList()
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
    val isGrowth = color == Color(0xFF35CC79)
    Card(
        modifier,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .34f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101D32))
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (isGrowth) "↗" else "♔", color = Color(0xFF35A8F5), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, color = Color(0xFFDCE8FF))
            }
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB2C1DA), maxLines = 1)
            DividendBarChart(values = values, labels = labels, barColor = color, modifier = Modifier.fillMaxWidth().height(142.dp))
        }
    }
}


@Composable
private fun MarketInstrumentIcon(ticker: String, modifier: Modifier = Modifier) {
    val key = ticker.trim().uppercase()
    val context = LocalContext.current
    val isOfz = key.startsWith("SU")
    val logoPath = if (isOfz) null else AssetLogoCatalog.assetPathFor(key)
    if (isOfz) {
        Box(
            modifier.clip(CircleShape).background(Color(0xFF29354F)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data("file:///android_asset/logos/russian_coat_of_arms_white.svg")
                    .crossfade(false)
                    .build(),
                imageLoader = AssetLogoCatalog.imageLoader(context),
                contentDescription = "Герб ОФЗ",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(.88f).fillMaxHeight(.88f)
            )
        }
    } else if (logoPath != null) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data("file:///android_asset/$logoPath")
                .crossfade(false)
                .build(),
            imageLoader = AssetLogoCatalog.imageLoader(context),
            contentDescription = "Логотип $key",
            contentScale = ContentScale.Fit,
            modifier = modifier.clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(2.dp)
        )
    } else {
        // Currency and commodity symbols are not company logos. Unknown issuers
        // keep a neutral ticker fallback until their authentic offline logo is verified.
        val mark = when (key) {
            "USD000UTSTOM", "USD_RUB_CBR" -> "$"
            "EUR_RUB__TOM", "EUR_RUB_CBR" -> "€"
            "CNYRUB_TOM", "CNY_RUB_CBR" -> "¥"
            "GLDRUB_TOM" -> "Au"
            else -> key.take(3)
        }
        Box(
            modifier.clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                mark,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                fontSize = if (mark.length > 2) 10.sp else 13.sp
            )
        }
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
    val step = stepFactor * magnitude / 3.0
    val axisMax = (kotlin.math.ceil(rawMax / step) * step).coerceAtLeast(1.0)
    val axisColor = Color(0xFF52627A).copy(alpha = .58f)
    val labelColor = Color(0xFFC3D0E5)
    val density = LocalDensity.current
    val tickPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor.copy(alpha = .9f).toArgb()
        textAlign = android.graphics.Paint.Align.RIGHT
        textSize = with(density) { 8.sp.toPx() }
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    val xLabelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor.toArgb()
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = with(density) { 8.sp.toPx() }
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    Canvas(modifier.fillMaxWidth().height(142.dp)) {
        val axisWidth = 29.dp.toPx()
        val left = axisWidth + 3.dp.toPx()
        val right = size.width - 2.dp.toPx()
        val top = 5.dp.toPx()
        val bottom = size.height - 25.dp.toPx()
        val chartHeight = (bottom - top).coerceAtLeast(1f)
        val chartWidth = (right - left).coerceAtLeast(1f)

        // Four evenly spaced positive ticks; zero is the baseline.
        for (line in 0..3) {
            val fraction = line / 3f
            val y = bottom - chartHeight * fraction
            drawLine(axisColor, Offset(left, y), Offset(right, y), 1.dp.toPx())
            val tick = axisMax * fraction
            val tickLabel = when {
                tick >= 1_000_000 -> String.format(Locale("ru", "RU"), "%.1fм", tick / 1_000_000.0)
                tick >= 10_000 -> String.format(Locale("ru", "RU"), "%.0fк", tick / 1_000.0)
                tick >= 1_000 -> String.format(Locale("ru", "RU"), "%.0f", tick)
                else -> String.format(Locale("ru", "RU"), "%.0f", tick)
            }
            drawContext.canvas.nativeCanvas.drawText(tickLabel, axisWidth - 3.dp.toPx(), y + tickPaint.textSize * .34f, tickPaint)
        }

        if (values.isNotEmpty()) {
            val slot = chartWidth / values.size
            values.forEachIndexed { index, value ->
                val barHeight = (value / axisMax).toFloat().coerceIn(0f, 1f) * chartHeight
                val barWidth = (slot * .58f).coerceAtMost(14.dp.toPx())
                val x = left + slot * index + (slot - barWidth) / 2f
                if (barHeight > 0f) {
                    drawRoundRect(
                        barColor,
                        Offset(x, bottom - barHeight),
                        androidx.compose.ui.geometry.Size(barWidth, barHeight),
                        androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                    )
                }
                val label = labels.getOrNull(index).orEmpty()
                if (label.isNotBlank()) {
                    drawContext.canvas.nativeCanvas.drawText(label, left + slot * (index + .5f), size.height - 3.dp.toPx(), xLabelPaint)
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
