package ru.mycapital.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.PayoutEntity
import ru.mycapital.app.data.PortfolioSnapshotEntity
import ru.mycapital.app.data.calculateReceivedPayoutTotals

@Composable
internal fun OverviewScreen(
    assets: List<AssetEntity>,
    total: Double,
    cashBalance: Double,
    payouts: List<PayoutEntity>,
    operations: List<OperationEntity>,
    snapshots: List<PortfolioSnapshotEntity>,
    selectedRange: PortfolioHistoryRange,
    onSelectedRangeChange: (PortfolioHistoryRange) -> Unit,
    historyResult: PortfolioMarketHistoryResult,
    historyLoading: Boolean,
    historyLoadError: String?,
    onRefreshHistory: () -> Unit,
    marketData: MarketOverviewData,
    onRefreshMarketData: () -> Unit,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
    quoteSyncMessage: String?,
    onOpenSettings: () -> Unit,
    onOpenIndexChart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val portfolioTotal = total + cashBalance
    val orderedSnapshots = remember(snapshots) { snapshots.sortedBy { it.dateKey } }
    val latest = orderedSnapshots.lastOrNull()
    val previous = orderedSnapshots.dropLast(1).lastOrNull()
    val dailyChange = if (latest != null && previous != null) {
        latest.totalValue - previous.totalValue - latest.externalFlow
    } else null
    val netContributions = operations.sumOf {
        when (it.operationType) {
            "Пополнение" -> it.price
            "Вывод" -> -it.price
            else -> 0.0
        }
    }
    // Redemption and amortization return invested principal; they are not income.
    // For lifetime P/L without a deposit ledger, include both income and returned bond principal.
    val receivedPayouts = calculateReceivedPayoutTotals(payouts).totalNetAmount
    val redeemedCostBasis = calculateRedeemedPositionCostBasis(assets, operations, payouts)
    val realized = calculateRealizedTradingResult(operations).profit
    val openCost = assets.sumOf { it.quantity * it.averagePrice }
    val unrealized = total - openCost
    val resultWithoutExternalFlowLedger = unrealized + realized + receivedPayouts - redeemedCostBasis
    val totalProfit = if (operations.any { it.operationType == "Пополнение" || it.operationType == "Вывод" }) {
        portfolioTotal - netContributions
    } else {
        // Without a recorded funding history, avoid presenting cost-basis-only
        // unrealized P/L as lifetime profit.
        resultWithoutExternalFlowLedger
    }
    val profitPercent = when {
        netContributions > 0.0 -> totalProfit / netContributions * 100.0
        openCost + redeemedCostBasis > 0.0 ->
            resultWithoutExternalFlowLedger / (openCost + redeemedCostBasis) * 100.0
        else -> null
    }
    val history = historyResult.points
val annualizedReturn = remember(history) {
        if (history.size < 2) null else {
            val firstDate = java.time.LocalDate.parse(history.first().dateKey)
            val lastDate = java.time.LocalDate.parse(history.last().dateKey)
            val days = java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate)
            val finalNav = history.last().nav / 100.0
            if (days >= 30 && finalNav > 0.0) {
                (Math.pow(finalNav, 365.0 / days.toDouble()) - 1.0) * 100.0
            } else null
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    var pullDistance by remember { mutableFloatStateOf(0f) }
    val refreshConnection = remember(isRefreshing, onRefresh) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: NestedScrollSource
            ): androidx.compose.ui.geometry.Offset {
                if (available.y > 0f && !isRefreshing) {
                    pullDistance = (pullDistance + available.y).coerceAtMost(with(density) { 120.dp.toPx() })
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (pullDistance >= with(density) { 72.dp.toPx() } && !isRefreshing) {
                    onRefresh()
                    onRefreshMarketData()
                    onRefreshHistory()
                }
                pullDistance = 0f
                return Velocity.Zero
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().nestedScroll(refreshConnection),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MyCapital", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Настройки")
                }
            }
            if (isRefreshing || pullDistance > 0f) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        CircularProgressIndicator(
                            progress = { (pullDistance / with(density) { 72.dp.toPx() }).coerceIn(0f, 1f) },
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
            quoteSyncMessage?.let { message ->
                Text(
                    message,
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(
                    "Общая стоимость портфеля",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "${money(portfolioTotal)} ₽",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    (if (totalProfit >= 0) "▲ +" else "▼ −") + "${money(kotlin.math.abs(totalProfit))} ₽" +
                        (profitPercent?.let { " (${if (it >= 0) "+" else ""}${String.format(java.util.Locale("ru", "RU"), "%.2f", it)}%)" } ?: ""),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (totalProfit >= 0) Color(0xFF25D879) else Color(0xFFFF5964)
                )
                if (history.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().height(190.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (historyLoading) "Загружаю историю портфеля…"
                            else historyLoadError ?: if (operations.none { it.operationType == "Покупка" && it.quantity > 0.0 }) {
                                "Нет операций покупки. Добавь или импортируй сделки — тогда построю историю портфеля."
                            } else {
                                "История пока недоступна: нет дневных снимков и котировок MOEX. Проверь подключение и повтори обновление."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val portfolioValues = history.map { it.totalValue }
                    val investedValues = history.map { it.investedCapital.coerceAtLeast(0.0) }
                    val maxValue = (portfolioValues + investedValues).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                    val span = maxValue
                    val portfolioColor = Color(0xFF168BFF)
                    val investedColor = Color(0xFFAEB7C4)
                    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
                    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
                    val chartDates = listOf(0, history.lastIndex / 3, history.lastIndex * 2 / 3, history.lastIndex).distinct()
                    Canvas(Modifier.fillMaxWidth().height(174.dp)) {
                        val left = 0f
                        val right = size.width
                        val top = 7f
                        val bottom = size.height - 7f
                        for (i in 0..4) {
                            val y = top + (bottom - top) * i / 4f
                            drawLine(
                                gridColor,
                                Offset(left, y),
                                Offset(right, y),
                                1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx()))
                            )
                        }
                        fun makePath(series: List<Double>): Path {
                            val points = series.mapIndexed { index, value ->
                                Offset(
                                    left + (right - left) * index / (series.size - 1).coerceAtLeast(1).toFloat(),
                                    bottom - (value / span).toFloat() * (bottom - top)
                                )
                            }
                            return Path().apply {
                                if (points.isEmpty()) return@apply
                                moveTo(points.first().x, points.first().y)
                                for (index in 0 until points.lastIndex) {
                                    val previous = points.getOrElse(index - 1) { points[index] }
                                    val current = points[index]
                                    val next = points[index + 1]
                                    val following = points.getOrElse(index + 2) { next }
                                    val dx = next.x - current.x
                                    cubicTo(
                                        current.x + (next.x - previous.x) / 6f,
                                        current.y + (next.y - previous.y) / 6f,
                                        next.x - (following.x - current.x) / 6f,
                                        next.y - (following.y - current.y) / 6f,
                                        next.x,
                                        next.y
                                    )
                                }
                            }
                        }
                        if (portfolioValues.size >= 2) {
                            val area = makePath(portfolioValues).apply {
                                lineTo(right, bottom)
                                lineTo(left, bottom)
                                close()
                            }
                            drawPath(
                                area,
                                brush = Brush.verticalGradient(
                                    colors = listOf(portfolioColor.copy(alpha = 0.22f), portfolioColor.copy(alpha = 0.01f)),
                                    startY = top,
                                    endY = bottom
                                )
                            )
                        }
                        fun drawSeries(series: List<Double>, color: Color, dashed: Boolean) {
                            if (series.size < 2) return
                            drawPath(
                                makePath(series),
                                color = color,
                                style = Stroke(
                                    width = if (dashed) 2.2.dp.toPx() else 3.6.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())) else null
                                )
                            )
                        }
                        drawSeries(investedValues, investedColor, true)
                        drawSeries(portfolioValues, portfolioColor, false)
                        val portfolioY = bottom - (portfolioValues.last() / span).toFloat() * (bottom - top)
                        drawCircle(portfolioColor, 4.5.dp.toPx(), Offset(right - 1.dp.toPx(), portfolioY))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        chartDates.forEach { index ->
                            Text(
                                java.time.LocalDate.parse(history[index].dateKey)
                                    .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM")),
                                fontSize = 9.sp,
                                color = axisColor
                            )
                        }
                    }
                }
                if (historyResult.unavailableTickers.isNotEmpty()) {
                    Text(
                        "Нет исторических котировок для: ${historyResult.unavailableTickers.joinToString()}. Эти позиции не включены в историческую оценку.",
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF2698FF)))
                        Text("Портфель", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(8.dp))
                        Canvas(Modifier.width(20.dp).height(7.dp)) {
                            drawLine(
                                Color(0xFF8798B5),
                                Offset(0f, size.height / 2),
                                Offset(size.width, size.height / 2),
                                2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))
                            )
                        }
                        Text("Вложено", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    PortfolioHistoryRange.entries.forEach { range ->
                        val active = selectedRange == range
                        Text(
                            range.label,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                                .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable { onSelectedRangeChange(range) }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        item {
            MarketQuotesSection(marketData = marketData, onOpenIndexChart = onOpenIndexChart)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    MarketMoversSection(title = "Топ роста за день", rows = marketData.gainers, positive = true)
                }
                Box(Modifier.weight(1f)) {
                    MarketMoversSection(title = "Топ падений за день", rows = marketData.losers, positive = false)
                }
            }
        }
        item {
            DividendHistorySection(payouts = payouts)
        }

    }
}
