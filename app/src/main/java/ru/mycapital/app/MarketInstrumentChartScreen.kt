package ru.mycapital.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import ru.mycapital.app.data.MoexIndexCandle

@Composable
internal fun MarketInstrumentChartScreen(ticker: String, quote: MarketQuoteRow?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val isUsd = ticker == "USD_RUB_CBR"
    val isEur = ticker == "EUR_RUB_CBR"
    val isCurrency = isUsd || isEur
    val title = if (isUsd) "Доллар США" else if (isEur) "Евро" else "Золото"
    val periods = listOf("1Д" to 1L, "1Н" to 7L, "1М" to 31L, "3М" to 92L, "6М" to 183L, "1Г" to 366L, "3Г" to 1096L, "5Л" to 1826L, "Всё" to null)
    var period by remember { mutableStateOf("1М") }
    var candles by remember { mutableStateOf<List<MoexIndexCandle>>(emptyList()) }
    var candleMode by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(ticker, period, refresh) {
        loading = true
        error = null
        try {
            val end = LocalDate.now(ZoneId.of("Europe/Moscow"))
            val days = periods.first { it.first == period }.second
            val start = days?.let { end.minusDays(it) } ?: LocalDate.of(1992, 1, 1)
            candles = withContext(Dispatchers.IO) {
                if (isCurrency) loadCbrRateSeries(if (isUsd) "R01235" else "R01239", start, end).map { MoexIndexCandle(it.first, it.second, it.second, it.second, it.second) }
                else loadMoexGoldSeries(ticker, start, end)
            }
            if (candles.isEmpty()) error = "История за выбранный период не опубликована."
        } catch (e: Exception) { error = e.message ?: "Не удалось загрузить историю." }
        finally { loading = false }
    }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (isCurrency) "Банк России · официальный курс" else "$ticker · Московская биржа · ₽/г", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(quote?.price?.let { String.format(Locale("ru", "RU"), "%,.2f ₽", it) } ?: "—", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        quote?.changePercent?.let { Text(String.format(Locale("ru", "RU"), "%+.2f%%", it), color = if (it >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            periods.forEach { (label, _) -> FilterChip(selected = period == label, onClick = { period = label }, label = { Text(label) }) }
        }
        when {
            loading && candles.isEmpty() -> Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            candles.isNotEmpty() -> {
                if (!isCurrency) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !candleMode, onClick = { candleMode = false }, label = { Text("Линейный") })
                    FilterChip(selected = candleMode, onClick = { candleMode = true }, label = { Text("Свечной") })
                }
                InstrumentHistoryChart(candles, candleMode && !isCurrency, Modifier.fillMaxWidth().height(280.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(candles.first().begin.take(10), style = MaterialTheme.typography.labelSmall)
                    Text(candles.last().begin.take(10), style = MaterialTheme.typography.labelSmall)
                }
            }
            else -> Text(error ?: "История пока недоступна.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading && candles.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(if (isCurrency) "Показаны ежедневные официальные курсы ЦБ РФ. Для валют не строятся свечи: ЦБ не публикует OHLC." else "MOEX ISS: линейный график по закрытиям; свечной — реальные OHLC открытия, максимума, минимума и закрытия.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Обновить график") }
    }
}

private fun loadCbrRateSeries(id: String, from: LocalDate, till: LocalDate): List<Pair<String, Double>> {
    val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    val url = "https://www.cbr.ru/scripts/XML_dynamic.asp?date_req1=" + from.format(fmt) + "&date_req2=" + till.format(fmt) + "&VAL_NM_RQ=" + id
    val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    val xml = client.newCall(Request.Builder().url(url).header("User-Agent", "MyCapital-Android/0.4.18").build()).execute().use {
        if (!it.isSuccessful) throw java.io.IOException("Банк России HTTP " + it.code)
        it.body?.string() ?: throw java.io.IOException("Пустой ответ Банка России")
    }
    return parseCbrDynamicRateXml(xml)
}

internal fun parseCbrDynamicRateXml(xml: String): List<Pair<String, Double>> {
    val regex = Regex("<Record\\s+Date=\"([^\"]+)\"[^>]*>.*?<Value>([^<]+)</Value>.*?</Record>", RegexOption.DOT_MATCHES_ALL)
    return regex.findAll(xml).mapNotNull {
        val date = runCatching { LocalDate.parse(it.groupValues[1], DateTimeFormatter.ofPattern("dd.MM.yyyy")).toString() }.getOrNull() ?: return@mapNotNull null
        val rate = it.groupValues[2].replace(",", ".").toDoubleOrNull() ?: return@mapNotNull null
        if (rate > 0.0 && rate.isFinite()) date to rate else null
    }.sortedBy { it.first }
}

private fun loadMoexGoldSeries(ticker: String, from: LocalDate, till: LocalDate): List<MoexIndexCandle> {
    val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    val merged = linkedMapOf<String, MoexIndexCandle>()
    var start = 0
    repeat(20) {
        val url = "https://iss.moex.com/iss/engines/currency/markets/selt/boards/CETS/securities/$ticker/candles.json?from=" + from + "&till=" + till + "&interval=24&start=" + start + "&limit=500&iss.meta=off&iss.only=candles&candles.columns=begin,open,high,low,close"
        val json = client.newCall(Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "MyCapital-Android/0.4.18").build()).execute().use {
            if (!it.isSuccessful) throw java.io.IOException("MOEX ISS HTTP " + it.code)
            it.body?.string() ?: throw java.io.IOException("Пустой ответ MOEX ISS")
        }
        val rows = ru.mycapital.app.data.MoexIndexCandlesClient.parseCandles(json)
        if (rows.isEmpty()) return merged.values.sortedBy { it.begin }
        rows.forEach { merged[it.begin] = it }
        if (rows.size < 500) return merged.values.sortedBy { it.begin }
        start += rows.size
    }
    return merged.values.sortedBy { it.begin }
}

@Composable
private fun InstrumentHistoryChart(candles: List<MoexIndexCandle>, candleMode: Boolean, modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)
    val line = MaterialTheme.colorScheme.primary
    val min = candles.minOf { it.low }
    val max = candles.maxOf { it.high }
    val span = (max - min).takeIf { it > 0.0 } ?: 1.0
    val axis = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    Canvas(modifier) {
        val left = 5.dp.toPx(); val right = size.width - 68.dp.toPx()
        val top = 10.dp.toPx(); val bottom = size.height - 10.dp.toPx()
        fun y(value: Double) = bottom - ((value - min) / span).toFloat() * (bottom - top)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = axis; textSize = 11.sp.toPx() }
        val fmt = java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols(Locale("ru", "RU")).apply { groupingSeparator = ' '; decimalSeparator = ',' })
        for (i in 0..4) {
            val yy = top + (bottom - top) * i / 4f
            drawLine(grid, Offset(left, yy), Offset(right, yy), 1.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText(fmt.format(max - span * i / 4.0), right + 5.dp.toPx(), yy + 4.dp.toPx(), paint)
        }
        if (!candleMode) {
            if (candles.size == 1) drawCircle(line, 5.dp.toPx(), Offset((left + right) / 2f, y(candles.single().close)))
            val path = Path()
            candles.forEachIndexed { i, candle ->
                val x = left + (right - left) * i / candles.lastIndex.coerceAtLeast(1)
                if (i == 0) path.moveTo(x, y(candle.close)) else path.lineTo(x, y(candle.close))
            }
            drawPath(path, line, style = Stroke(2.5.dp.toPx()))
        } else {
            val slot = (right - left) / candles.size.coerceAtLeast(1)
            val bodyWidth = (slot * .62f).coerceIn(2.dp.toPx(), 12.dp.toPx())
            candles.forEachIndexed { i, candle ->
                val x = left + slot * (i + .5f)
                val color = if (candle.close >= candle.open) androidx.compose.ui.graphics.Color(0xFF28B77A) else androidx.compose.ui.graphics.Color(0xFFE05B64)
                drawLine(color, Offset(x, y(candle.high)), Offset(x, y(candle.low)), 1.2.dp.toPx())
                val topY = y(maxOf(candle.open, candle.close)); val bottomY = y(minOf(candle.open, candle.close))
                drawRect(color, Offset(x - bodyWidth / 2f, topY), androidx.compose.ui.geometry.Size(bodyWidth, (bottomY - topY).coerceAtLeast(1.5.dp.toPx())))
            }
        }
    }
}
