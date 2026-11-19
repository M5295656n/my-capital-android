package ru.mycapital.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.mycapital.app.data.MoexIndexCandle
import ru.mycapital.app.data.MoexIndexCandlesClient
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class IndexChartMode(val title: String) {
    LINE("Линейный"),
    CANDLE("Свечной")
}

private data class IndexChartPeriod(
    val label: String,
    val days: Long?,
    val interval: Int
)

@Composable
internal fun ImoexIndexChartScreen(
    quote: MarketQuoteRow?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val periods = remember {
        listOf(
            IndexChartPeriod("1Д", 1, 10),
            IndexChartPeriod("1Н", 7, 60),
            IndexChartPeriod("1М", 31, 24),
            IndexChartPeriod("3М", 92, 24),
            IndexChartPeriod("6М", 183, 24),
            IndexChartPeriod("1Г", 366, 24),
            IndexChartPeriod("3Г", 1096, 24),
            IndexChartPeriod("5Л", 1826, 24),
            IndexChartPeriod("Всё", null, 24)
        )
    }
    var selectedPeriod by remember { mutableStateOf(periods[2]) }
    var mode by remember { mutableStateOf(IndexChartMode.LINE) }
    var candles by remember { mutableStateOf<List<MoexIndexCandle>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(selectedPeriod, reload) {
        loading = true
        error = null
        try {
            val today = LocalDate.now(ZoneId.of("Europe/Moscow"))
            val from = selectedPeriod.days?.let { today.minusDays(it + if (it <= 7) 3 else 0) } ?: LocalDate.of(2000, 1, 1)
            val loaded = withContext(Dispatchers.IO) {
                MoexIndexCandlesClient().getCandles(
                    indexCode = "IMOEX",
                    fromDate = from.toString(),
                    tillDate = today.toString(),
                    interval = selectedPeriod.interval
                )
            }
            candles = if (selectedPeriod.label == "1Д" && loaded.isNotEmpty()) {
                val latestSession = loaded.last().begin.take(10)
                loaded.filter { it.begin.startsWith(latestSession) }
            } else loaded
            if (candles.isEmpty()) error = "MOEX ISS пока не вернул свечи за выбранный период."
        } catch (exception: Exception) {
            error = exception.message ?: "Не удалось загрузить историю индекса."
        } finally {
            loading = false
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Column(Modifier.weight(1f)) {
                Text("Индекс МосБиржи", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("IMOEX · Московская биржа", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                quote?.price?.let { String.format(Locale("ru", "RU"), "%,.2f", it) } ?: "—",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
            val change = quote?.changePercent
            Text(
                change?.let { "${if (it >= 0) "+" else ""}${String.format(Locale("ru", "RU"), "%.2f", it)}%" }
                    ?: "Изменение за сессию не опубликовано",
                color = change?.let { if (it >= 0) Color(0xFF35B978) else Color(0xFFE36B70) }
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IndexChartMode.entries.forEach { item ->
                FilterChip(
                    selected = mode == item,
                    onClick = { mode = item },
                    label = { Text(item.title, maxLines = 1) }
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            periods.forEach { period ->
                FilterChip(
                    selected = selectedPeriod.label == period.label,
                    onClick = { selectedPeriod = period },
                    label = { Text(period.label, maxLines = 1) }
                )
            }
        }
        when {
            loading && candles.isEmpty() -> Box(Modifier.fillMaxWidth().height(270.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            candles.size >= 2 -> {
                IndexHistoryChart(candles, mode, Modifier.fillMaxWidth().height(280.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(candles.first().begin.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(candles.last().begin.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> Text(error ?: "История индекса пока недоступна.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading && candles.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Обновляю график…", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            "Источник: Московская биржа (MOEX ISS). Для коротких периодов используются внутридневные свечи, для длинных — дневные. Последняя свеча может быть незавершённой во время торгов.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Линейный график показывает цены закрытия. Свечной график показывает открытие, максимум, минимум и закрытие.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        androidx.compose.material3.TextButton(onClick = { reload++ }, enabled = !loading) {
            Text("Обновить график")
        }
    }
}

@Composable
private fun IndexHistoryChart(
    candles: List<MoexIndexCandle>,
    mode: IndexChartMode,
    modifier: Modifier = Modifier
) {
    val up = Color(0xFF28B77A)
    val down = Color(0xFFE05B64)
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)
    val lineColor = MaterialTheme.colorScheme.primary
    val minValue = candles.minOf { it.low }
    val maxValue = candles.maxOf { it.high }
    val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val axisTextSize = 11.sp
    Canvas(modifier) {
        val left = 8.dp.toPx()
        // Keep a dedicated right-side gutter for the numeric value of each grid line.
        val labelGutter = 58.dp.toPx()
        val plotRight = size.width - labelGutter
        val top = 10.dp.toPx()
        val bottom = size.height - 10.dp.toPx()
        fun y(value: Double): Float =
            bottom - ((value - minValue) / range).toFloat() * (bottom - top)
        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = axisTextColor
            textSize = axisTextSize.toPx()
            textAlign = android.graphics.Paint.Align.LEFT
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        val formatter = java.text.DecimalFormat(
            "#,##0",
            java.text.DecimalFormatSymbols(Locale("ru", "RU")).apply {
                groupingSeparator = ' '
                decimalSeparator = ','
            }
        )
        for (index in 0..4) {
            val fraction = index / 4f
            val gy = top + (bottom - top) * fraction
            val value = maxValue - range * fraction
            drawLine(grid, Offset(left, gy), Offset(plotRight, gy), 1.dp.toPx())
            val metrics = labelPaint.fontMetrics
            val baseline = gy - (metrics.ascent + metrics.descent) / 2f
            drawContext.canvas.nativeCanvas.drawText(
                formatter.format(value),
                plotRight + 6.dp.toPx(),
                baseline,
                labelPaint
            )
        }
        if (mode == IndexChartMode.LINE) {
            val path = Path()
            candles.forEachIndexed { index, candle ->
                val x = left + (plotRight - left) * index / (candles.lastIndex).coerceAtLeast(1)
                val pointY = y(candle.close)
                if (index == 0) path.moveTo(x, pointY) else path.lineTo(x, pointY)
            }
            drawPath(path, lineColor, style = Stroke(width = 2.6.dp.toPx()))
            val last = candles.last()
            drawCircle(lineColor, 4.dp.toPx(), Offset(plotRight, y(last.close)))
        } else {
            val slot = (plotRight - left) / candles.size.coerceAtLeast(1)
            val bodyWidth = (slot * .62f).coerceIn(2.dp.toPx(), 12.dp.toPx())
            candles.forEachIndexed { index, candle ->
                val x = left + slot * (index + .5f)
                val color = if (candle.close >= candle.open) up else down
                drawLine(color, Offset(x, y(candle.high)), Offset(x, y(candle.low)), 1.2.dp.toPx())
                val bodyTop = y(maxOf(candle.open, candle.close))
                val bodyBottom = y(minOf(candle.open, candle.close))
                val height = (bodyBottom - bodyTop).coerceAtLeast(1.5.dp.toPx())
                drawRect(
                    color,
                    topLeft = Offset(x - bodyWidth / 2f, bodyTop),
                    size = androidx.compose.ui.geometry.Size(bodyWidth, height)
                )
            }
        }
    }
}
