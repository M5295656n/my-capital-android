package ru.mycapital.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.mycapital.app.data.SberBrokerReport
import ru.mycapital.app.data.SberBrokerReportParser
import ru.mycapital.app.data.isImportableBrokerTrade
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ru.mycapital.app.data.QuoteRefreshWorker
import ru.mycapital.app.data.CorporateEventsWorker
import ru.mycapital.app.data.MarketNewsWorker
import ru.mycapital.app.data.MarketCalendarItemEntity
import ru.mycapital.app.data.PortfolioSnapshotWorker
import java.util.concurrent.TimeUnit
import java.time.YearMonth
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.MoexSecurityHistoryClient
import ru.mycapital.app.data.MoexDailyClose
import ru.mycapital.app.calculatePortfolioAllocation
import ru.mycapital.app.data.PortfolioDatabase
import ru.mycapital.app.data.PortfolioBackup
import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.PayoutEntity
import ru.mycapital.app.data.calculateReceivedPayoutTotals
import ru.mycapital.app.data.PortfolioRepository
import ru.mycapital.app.data.PortfolioSnapshotEntity
import ru.mycapital.app.data.BrokerAccountEntity
import ru.mycapital.app.data.BrokerAccountCashEntity
import ru.mycapital.app.data.BrokerAccountAssetEntity

@Composable
internal fun AssetDetailScreen(
    asset: AssetEntity,
    allAssets: List<AssetEntity>,
    operations: List<OperationEntity>,
    payouts: List<PayoutEntity>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var period by remember { mutableStateOf("1М") }
    var candles by remember { mutableStateOf<List<MoexDailyClose>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val assetOperations = remember(asset.id, asset.ticker, operations) {
        operations.filter { it.assetId == asset.id || it.ticker.equals(asset.ticker, true) }
    }
    val trades = remember(asset.id, asset.ticker, assetOperations) {
        assetOperations.filter { it.operationType == "Покупка" || it.operationType == "Продажа" }
            .sortedBy { it.operationDate }
    }
    val assetPayouts = remember(asset.id, asset.ticker, payouts) {
        payouts.filter { it.assetId == asset.id || it.ticker.equals(asset.ticker, true) }
    }
     val firstTradeDate = trades.firstOrNull()?.operationDate?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
    LaunchedEffect(asset.ticker, asset.assetType, period, firstTradeDate) {
        loading = true
        error = null
        try {
            val today = java.time.LocalDate.now()
            val days = when (period) { "1М" -> 31L; "3М" -> 92L; "6М" -> 183L; "1Г" -> 366L; else -> 3650L }
            val fromDate = if (period == "Всё") (firstTradeDate ?: today.minusYears(10)) else today.minusDays(days)
            candles = withContext(Dispatchers.IO) {
                MoexSecurityHistoryClient().getDailyCloses(asset.ticker, asset.assetType, fromDate, today)
            }
            if (candles.isEmpty()) error = "Нет истории котировок"
        } catch (e: Exception) {
            candles = emptyList()
            error = e.message ?: "Не удалось загрузить котировки"
        } finally { loading = false }
    }
    // Portfolio valuation uses the same current quote as the asset list and dashboard.
    // Historical MOEX closes remain the fallback only when no usable current quote exists.
    val price = asset.currentPrice.takeIf { it.isFinite() && it > 0.0 }
        ?: candles.lastOrNull()?.close?.takeIf { it.isFinite() && it > 0.0 }
        ?: 0.0
    val forwardDividendYieldPercent = remember(assetPayouts, price, asset.ticker) {
        ru.mycapital.app.data.calculateForwardDividendYieldPercentFromPayouts(
            assetPayouts,
            asset.ticker,
            price,
            java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow"))
        )
    }
    val previous = candles.dropLast(1).lastOrNull()?.close
    val change = previous?.let { price - it }
    val changePct = previous?.takeIf { it != 0.0 }?.let { (price - it) / it * 100.0 }
    val fifo = remember(trades) { calculateRemainingPurchaseLots(trades) }
    val invested = trades.filter { it.operationType == "Покупка" }.sumOf { op ->
        val remaining = fifo.remainingByOperationId[op.id] ?: 0.0
        if (op.quantity > 0.0) remaining / op.quantity * (op.quantity * op.price + op.commission) else 0.0
    }
    val actual = assetPayouts.filter { it.actualDate != null || it.status.equals("RECEIVED", true) }
    val received = calculateReceivedPayoutTotals(assetPayouts).totalNetAmount
    val fees = trades.sumOf { it.commission }
    val value = asset.quantity * price
    val sales = trades.filter { it.operationType == "Продажа" }.sumOf { it.quantity * it.price - it.commission }
    val purchases = trades.filter { it.operationType == "Покупка" }.sumOf { it.quantity * it.price + it.commission }
    val tradedQuantity = trades.sumOf { if (it.operationType == "Покупка") it.quantity else -it.quantity }
    val quantityMatchesHistory = kotlin.math.abs(tradedQuantity - asset.quantity) <= maxOf(0.000001, asset.quantity * 0.00001)
    val fullyRedeemed = asset.quantity <= 0.000001 && actual.any { it.payoutType.equals("Погашение", ignoreCase = true) }
    val historyComplete = trades.isNotEmpty() && fifo.unmatchedSaleQuantity < 0.000001 && (quantityMatchesHistory || fullyRedeemed)
    val profit = if (historyComplete) value + sales + received - purchases else null
    val totalReturn = if (historyComplete && purchases > 0.0) profit!! / purchases * 100.0 else null
    val cashFlows = buildAssetReturnCashFlows(
        operations = assetOperations,
        payouts = assetPayouts,
        currentValue = value,
        valuationDate = System.currentTimeMillis()
    )
    val annualized = if (historyComplete) annualizedReturnPercent(cashFlows) else null
    val upcomingDividendPayouts = assetPayouts
        .filter { it.payoutType == "Дивиденд" && isUpcomingDividendStatus(it.status) && it.actualDate == null }
        .filter { (it.recordDate ?: it.expectedDate ?: Long.MAX_VALUE) >= System.currentTimeMillis() }
        .sortedWith(compareBy<PayoutEntity> { dividendStatusPriority(it.status) }
            .thenBy { it.recordDate ?: it.expectedDate ?: Long.MAX_VALUE })
        // Одна прогнозная запись на одну дату отсечки.
        .distinctBy { payout ->
            payout.recordDate?.let { "record:$it" }
                ?: payout.expectedDate?.let { "expected:$it" }
                ?: "id:${payout.id}"
        }
    val bond = ru.mycapital.app.data.isBondInstrument(asset.ticker, asset.name, asset.assetType)
    var bondMarketInfo by remember(asset.ticker) { mutableStateOf<ru.mycapital.app.data.BondMarketInfo?>(null) }
    var bondInfoLoading by remember(asset.ticker) { mutableStateOf(false) }
    LaunchedEffect(asset.ticker, asset.currentPrice, bond) {
        if (bond) {
            bondInfoLoading = true
            bondMarketInfo = withContext(Dispatchers.IO) {
                runCatching {
                    ru.mycapital.app.data.BondMarketInfoClient().getInfo(asset.ticker)
                }.getOrNull()
            }
            bondInfoLoading = false
        } else {
            bondMarketInfo = null
            bondInfoLoading = false
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
            Text("Актив", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssetTypeIcon(asset.assetType, asset.ticker)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(asset.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Text(asset.name, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${money(price)} ₽", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Text(if (change != null && changePct != null) "${if (change >= 0) "+" else ""}${money(change)} ₽ (${String.format(java.util.Locale("ru", "RU"), "%.2f", changePct)}%)" else "Нет данных за день",
                            color = if ((change ?: 0.0) >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Стоимость позиции", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${money(value)} ₽", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("1М", "3М", "6М", "1Г", "Всё").forEach { p ->
                        FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p) })
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (candles.size > 1) {
                    val chartValues = candles.map { it.close }
                    val rawMin = chartValues.minOrNull() ?: 0.0
                    val rawMax = chartValues.maxOrNull() ?: rawMin
                    val rawSpan = (rawMax - rawMin).takeIf { it > 0.0 } ?: maxOf(kotlin.math.abs(rawMax) * 0.02, 1.0)
                    val chartMin = rawMin - rawSpan * 0.08
                    val chartMax = rawMax + rawSpan * 0.08
                    val chartSpan = (chartMax - chartMin).takeIf { it > 0.0 } ?: 1.0
                    val chartColor = MaterialTheme.colorScheme.primary
                    val chartSurface = MaterialTheme.colorScheme.surface
                    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
                    val markerOperations = trades.mapNotNull { operation ->
                        val operationDate = java.time.Instant.ofEpochMilli(operation.operationDate)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        val index = candles.indexOfFirst { !it.date.isBefore(operationDate) }
                        if (index < 0 || operationDate.isBefore(candles.first().date)) null
                        else Triple(index, operation.operationType, operation.id)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.width(54.dp).height(154.dp).padding(end = 6.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(money(chartMax), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            Text(money((chartMax + chartMin) / 2.0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            Text(money(chartMin), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Canvas(Modifier.weight(1f).height(154.dp)) {
                            val topInset = 7.dp.toPx()
                            val plotHeight = size.height - topInset - 7.dp.toPx()
                            fun pointY(value: Double): Float = topInset + ((chartMax - value) / chartSpan).toFloat() * plotHeight
                            val coordinates = chartValues.mapIndexed { index, value ->
                                Offset(size.width * index / (chartValues.size - 1).toFloat(), pointY(value))
                            }
                            for (gridIndex in 0..2) {
                                val y = topInset + plotHeight * gridIndex / 2f
                                drawLine(axisColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                            }
                            val linePath = Path().apply {
                                moveTo(coordinates.first().x, coordinates.first().y)
                                for (index in 0 until coordinates.lastIndex) {
                                    val start = coordinates[index]
                                    val end = coordinates[index + 1]
                                    val dx = (end.x - start.x) / 3f
                                    cubicTo(start.x + dx, start.y, end.x - dx, end.y, end.x, end.y)
                                }
                            }
                            val areaPath = Path().apply {
                                addPath(linePath)
                                lineTo(coordinates.last().x, size.height)
                                lineTo(coordinates.first().x, size.height)
                                close()
                            }
                            drawPath(areaPath, brush = Brush.verticalGradient(
                                colors = listOf(chartColor.copy(alpha = 0.30f), chartColor.copy(alpha = 0.015f)),
                                startY = 0f, endY = size.height
                            ))
                            drawLine(
                                chartColor.copy(alpha = 0.72f),
                                Offset(0f, coordinates.last().y), Offset(size.width, coordinates.last().y),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                            )
                            drawPath(linePath, chartColor, style = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round))
                            markerOperations.forEach { (index, type, _) ->
                                val markerColor = if (type == "Покупка") Color(0xFF35C98A) else Color(0xFFFF6B6B)
                                drawCircle(markerColor.copy(alpha = 0.22f), radius = 7.dp.toPx(), center = coordinates[index])
                                drawCircle(markerColor, radius = 3.8.dp.toPx(), center = coordinates[index])
                            }
                            drawCircle(chartSurface, radius = 5.dp.toPx(), center = coordinates.last())
                            drawCircle(chartColor, radius = 3.2.dp.toPx(), center = coordinates.last())
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 60.dp, top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yy", java.util.Locale("ru", "RU"))
                        Text(candles.first().date.format(dateFormatter), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(candles.last().date.format(dateFormatter), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 60.dp, top = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(7.dp).background(Color(0xFF35C98A), CircleShape))
                            Text("Покупка", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(7.dp).background(Color(0xFFFF6B6B), CircleShape))
                            Text("Продажа", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("MOEX · дневные закрытия · котировки могут быть задержаны", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Text(error ?: "История котировок загружается", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        AssetDetailSection("Общее") {
            AssetDetailMetric("Количество", "${quantity(asset.quantity)} шт.")
            AssetDetailMetric("Вложено в оставшуюся позицию", if (trades.isNotEmpty() && fifo.unmatchedSaleQuantity < 0.000001) "${money(invested)} ₽" else "Недостаточно данных")
            AssetDetailMetric("Средняя цена", if (asset.quantity > 0.0 && trades.isNotEmpty()) "${money(invested / asset.quantity)} ₽" else "Не рассчитано")
            AssetDetailMetric("Стоимость 1 шт.", "${money(price)} ₽")
            val portfolioValue = allAssets.sumOf { it.quantity * it.currentPrice }
            AssetDetailMetric("Доля в портфеле", if (portfolioValue > 0.0) String.format(java.util.Locale("ru", "RU"), "%.2f%%", value / portfolioValue * 100.0) else "Не рассчитано")
        }
        AssetDetailSection("Прибыль") {
            AssetDetailMetric("Общая прибыль", profit?.let { "${if (it >= 0) "+" else ""}${money(it)} ₽" } ?: "Недостаточно данных по истории операций")
            AssetDetailMetric("Общая доходность", totalReturn?.let { String.format(java.util.Locale("ru", "RU"), "%.2f%%", it) } ?: "Не рассчитано")
            AssetDetailMetric("Прибыль / убыток за день", change?.let { "${if (it >= 0) "+" else ""}${money(it * asset.quantity)} ₽" } ?: "Нет данных")
            AssetDetailMetric("Получено начислений", "${money(received)} ₽")
            AssetDetailMetric("Уплачено комиссий", "${money(fees)} ₽")
            AssetDetailMetric("Среднегодовая доходность", annualized?.let { String.format(java.util.Locale("ru", "RU"), "%.2f%%", it) } ?: "Недостаточно данных")
        }
        AssetDetailSection(if (bond) "Купоны" else "Дивиденды") {
            if (!bond) {
            }
            if (bond) {
                val nextCoupon = assetPayouts.filter { it.payoutType == "Купон" && it.status != "RECEIVED" && it.expectedDate != null }
                    .minByOrNull { it.expectedDate ?: Long.MAX_VALUE }
                AssetDetailMetric(
                    "Купонная доходность",
                    bondMarketInfo?.couponYieldPercent?.let {
                        String.format(java.util.Locale("ru", "RU"), "%.2f%%", it)
                    } ?: if (bondInfoLoading) "Загрузка…" else "Нет данных"
                )
                AssetDetailMetric(
                    "Погашение",
                    bondMarketInfo?.maturityDate?.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy", java.util.Locale("ru", "RU")))
                        ?: if (bondInfoLoading) "Загрузка…" else "Нет данных"
                )
                if (nextCoupon == null) Text("Нет данных о будущих купонах", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    AssetDetailMetric("Следующая выплата", nextCoupon.expectedDate?.let { java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru", "RU")).format(java.util.Date(it)) } ?: "Дата неизвестна")
                    AssetDetailMetric("Купон на одну облигацию", "${money(nextCoupon.amountPerUnit)} ₽")
                    AssetDetailMetric("Сумма", "${money(nextCoupon.totalAmount)} ₽")
                }
            } else if (upcomingDividendPayouts.isEmpty()) {
                Text("Нет данных об объявленных или ожидаемых дивидендах", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                upcomingDividendPayouts.take(5).forEach { payout ->
                    AssetDetailMetric(if (payout.sourceName == "MyCapital · история MOEX") "Прогноз MyCapital по истории" else dividendStatusLabel(payout.status), "${money(payout.totalAmount)} ₽")
                    AssetDetailMetric("На одну акцию", "${money(payout.amountPerUnit)} ₽")
                    payout.recordDate?.let { date ->
                        AssetDetailMetric("Дата отсечки", java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru", "RU")).format(java.util.Date(date)))
                    }
                    payout.expectedDate?.takeIf { payout.sourceName != "MyCapital · история MOEX" }?.let { date ->
                        AssetDetailMetric("Ориентировочная дата выплаты", java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru", "RU")).format(java.util.Date(date)))
                    }
                }
            }
            if (!bond) {
                AssetDetailMetric(
                    "Дивидендная доходность",
                    if (forwardDividendYieldPercent != null) String.format(java.util.Locale("ru", "RU"), "%.2f%%", forwardDividendYieldPercent)
                    else "Нет проверяемых данных"
                )
            }
        }
        AssetDetailSection("Операции") {
            if (actual.isEmpty() && trades.isEmpty()) Text("История операций отсутствует", color = MaterialTheme.colorScheme.onSurfaceVariant)
            actual.forEach { p ->
                val d = p.actualDate?.let { java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru", "RU")).format(java.util.Date(it)) } ?: "дата не указана"
                AssetDetailMetric("${if (bond) "Купон" else "Дивиденд"} · $d", "${money(p.reportedNetAmount ?: (p.totalAmount - p.taxAmount))} ₽")
            }
            trades.forEach { op ->
                val d = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru", "RU")).format(java.util.Date(op.operationDate))
                AssetDetailMetric("$d · ${op.operationType}", "${quantity(op.quantity)} × ${money(op.price)} ₽ · комиссия ${money(op.commission)} ₽")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
