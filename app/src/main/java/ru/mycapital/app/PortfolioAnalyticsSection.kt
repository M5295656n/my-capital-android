package ru.mycapital.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.BrokerAccountEntity
import ru.mycapital.app.data.PortfolioCompositionSnapshotEntity
import java.util.Locale

private const val ANALYTICS_ALL_ACCOUNTS = "ALL"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortfolioAnalyticsSection(
    assets: List<AssetEntity>,
    cashBalance: Double,
    accounts: List<BrokerAccountEntity>,
    selectedAccountId: String,
    onAccountSelected: (String) -> Unit,
    compositionSnapshots: List<PortfolioCompositionSnapshotEntity>,
    marketHistory: PortfolioMarketHistoryResult,
    historyLoading: Boolean,
    onRefreshHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var accountMenuExpanded by remember { mutableStateOf(false) }
    val portfolioValue = assets.sumOf { (it.quantity * it.currentPrice).coerceAtLeast(0.0) } + cashBalance.coerceAtLeast(0.0)
    val classSlices = remember(assets, cashBalance) { calculatePortfolioClassSlices(assets, cashBalance) }
    val sectorSlices = remember(assets, portfolioValue) { calculatePortfolioSectorSlices(assets, portfolioValue) }
    val sectorSlicesWithRemainder = remember(sectorSlices, portfolioValue) {
        val representedValue = sectorSlices.sumOf { it.value }
        val remainder = (portfolioValue - representedValue).coerceAtLeast(0.0)
        if (portfolioValue > 0.0 && remainder > 0.01) {
            sectorSlices + PortfolioSectorSlice(
                sector = "Вне отраслевого состава",
                value = remainder,
                shareOfPortfolio = remainder / portfolioValue,
                positions = emptyList()
            )
        } else sectorSlices
    }
    val concentration = remember(assets, portfolioValue) { calculatePortfolioConcentration(assets, portfolioValue) }
    val selectedAccountLabel = if (selectedAccountId == ANALYTICS_ALL_ACCOUNTS) "Все счета"
        else accounts.firstOrNull { it.accountId == selectedAccountId }?.displayName ?: "Счёт"

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Аналитика", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (accounts.isNotEmpty()) {
            ExposedDropdownMenuBox(expanded = accountMenuExpanded, onExpandedChange = { accountMenuExpanded = it }) {
                OutlinedTextField(
                    value = selectedAccountLabel, onValueChange = {}, readOnly = true,
                    label = { Text("Брокерский счёт") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountMenuExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = accountMenuExpanded, onDismissRequest = { accountMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Все счета") }, onClick = {
                        onAccountSelected(ANALYTICS_ALL_ACCOUNTS)
                        accountMenuExpanded = false
                    })
                    accounts.forEach { account ->
                        DropdownMenuItem(text = { Text(account.displayName) }, onClick = {
                            onAccountSelected(account.accountId)
                            accountMenuExpanded = false
                        })
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Структура портфеля", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Рыночная стоимость активов и свободные деньги", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (portfolioValue <= 0.0 || classSlices.isEmpty()) {
                    Text("Добавьте активы или свободные деньги, чтобы увидеть распределение.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PortfolioAllocationDonut(classSlices, Modifier.size(132.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            classSlices.forEach { slice ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(9.dp).clip(CircleShape).background(classColor(slice.assetClass)))
                                    Spacer(Modifier.width(7.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(slice.assetClass.label, style = MaterialTheme.typography.labelMedium)
                                        Text("${moneyAnalytics(slice.value)} ₽", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(percentAnalytics(slice.share), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Фактическая доля / целевая доля", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    PortfolioAssetClass.entries.filter { it.targetShare != null }.forEach { assetClass ->
                        val actual = classSlices.firstOrNull { it.assetClass == assetClass }
                        val actualShare = actual?.share ?: 0.0
                        val target = assetClass.targetShare!!
                        val delta = (actualShare - target) * 100.0
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(assetClass.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text("${percentAnalytics(actualShare)} / ${percentAnalytics(target)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Box(Modifier.fillMaxWidth(actualShare.toFloat().coerceIn(0f, 1f)).height(7.dp).clip(CircleShape).background(classColor(assetClass)))
                            }
                            Text((if (delta > 0.05) "+" else "") + String.format(Locale("ru", "RU"), "%.1f п.п.", delta),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    classSlices.firstOrNull { it.assetClass == PortfolioAssetClass.OTHER }?.let {
                        Text("Прочие активы не входят в целевые доли.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Цели: акции и фонды роста — 65%, облигации и денежный рынок — 25%, золото — 5%, кэш — 5%. SBMM учитывается в защитной части портфеля.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Структура по секторам", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Отрасли показаны для отдельных акций. Остальная часть портфеля выделена отдельно.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sectorSlicesWithRemainder.isEmpty()) {
                    Text("Пока нет акций с определённой отраслью.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    PortfolioSectorDonut(sectorSlicesWithRemainder, Modifier.size(132.dp).align(Alignment.CenterHorizontally))
                    sectorSlicesWithRemainder.forEachIndexed { sectorIndex, sector ->
                        var expanded by remember(sector.sector) { mutableStateOf(false) }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).clip(CircleShape).background(sectorColor(sectorIndex, sector.sector == "Вне отраслевого состава")))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(sector.sector, fontWeight = FontWeight.Medium)
                                    Text("${moneyAnalytics(sector.value)} ₽", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (sector.sector == "Вне отраслевого состава") {
                                        Text("Фонды, облигации, золото, деньги и прочие активы", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text(percentAnalytics(sector.shareOfPortfolio), fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(6.dp))
                                Text(if (expanded) "−" else "+", color = MaterialTheme.colorScheme.primary)
                            }
                            Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Box(Modifier.fillMaxWidth(sector.shareOfPortfolio.toFloat().coerceIn(0f, 1f)).height(7.dp).clip(CircleShape).background(sectorColor(sectorIndex, sector.sector == "Вне отраслевого состава")))
                            }
                            if (expanded) {
                                sector.positions.forEach { asset ->
                                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(asset.ticker, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                            Text(asset.name, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                        }
                                        Text("${moneyAnalytics(asset.quantity * asset.currentPrice)} ₽", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Концентрация портфеля", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Крупнейшие эмитенты по доле от всего портфеля", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (concentration.isEmpty()) {
                    Text("Нет данных для расчёта концентрации.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    concentration.forEach { position ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(position.ticker, fontWeight = FontWeight.SemiBold)
                                    Text(position.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(percentAnalytics(position.shareOfPortfolio), fontWeight = FontWeight.SemiBold)
                            }
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Box(Modifier.fillMaxWidth(position.shareOfPortfolio.toFloat().coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                            }
                        }
                    }
                    val largest = concentration.first()
                    if (largest.shareOfPortfolio >= 0.25) {
                        Text("Внимание: крупнейший эмитент занимает не менее 25% портфеля. Проверьте риск концентрации.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    val largestSector = sectorSlices.firstOrNull()
                    if (largestSector != null && largestSector.shareOfPortfolio >= 0.35) {
                        Text("Внимание: сектор «${largestSector.sector}» занимает не менее 35% портфеля.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Историческая структура", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                PortfolioCompositionHistoryChart(marketHistory, assets, historyLoading)
            }
        }
    }
}

@Composable
private fun PortfolioReturnHistoryCard(
    history: PortfolioMarketHistoryResult,
    loading: Boolean,
    onRefresh: () -> Unit
) {
    val points = remember(history) { history.points.sortedBy { it.dateKey } }
    val first = points.firstOrNull()
    val last = points.lastOrNull()
    val hasReturn = points.size >= 2 && first != null && last != null && first.nav > 0.0
    val returnPercent = if (hasReturn) (last!!.nav / first!!.nav - 1.0) * 100.0 else 0.0
    val returnColor = if (returnPercent >= 0.0) Color(0xFF238B63) else MaterialTheme.colorScheme.error

    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Доходность портфеля", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("TWR — изменение стоимости портфеля без влияния пополнений и выводов",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hasReturn) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(String.format(Locale("ru", "RU"), "%+.2f%%", returnPercent),
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = returnColor)
                    Text("индекс 100 → " + String.format(Locale("ru", "RU"), "%.2f", last!!.nav),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PortfolioReturnLineChart(points)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(first!!.dateKey, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(last!!.dateKey, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Расчёт по дневным котировкам MOEX и журналу операций. Это оценка TWR; точность зависит от полноты истории сделок и выплат.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(if (loading) "Загружаю котировки и восстанавливаю историю портфеля…" else
                    "Для расчёта пока недостаточно исторических котировок или операций.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!loading && history.unavailableTickers.isNotEmpty()) {
                    Text("Не удалось получить историю: " + history.unavailableTickers.joinToString(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (!loading && history.firstPurchaseDate == null) {
                    Text("В журнале не найдена операция покупки с тикером. Проверьте импорт истории сделок.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(if (loading) "Обновление…" else "Обновить историю",
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !loading, onClick = onRefresh).padding(horizontal = 10.dp, vertical = 7.dp),
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PortfolioReturnLineChart(points: List<PortfolioMarketHistoryPoint>) {
    val plotted = if (points.size > 100) points.filterIndexed { index, _ -> index % ((points.size / 60).coerceAtLeast(1)) == 0 }.takeLast(100) else points
    val values = plotted.map { it.nav }
    val minValue = (values.minOrNull() ?: 100.0).let { minOf(it, 100.0) }
    val maxValue = (values.maxOrNull() ?: 100.0).let { maxOf(it, 100.0) }
    val spread = (maxValue - minValue).coerceAtLeast(0.5)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val left = 4f; val right = size.width - 4f; val top = 8f; val bottom = size.height - 8f
        for (i in 0..4) {
            val y = top + (bottom - top) * i / 4f
            drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
        }
        val baselineY = bottom - ((100.0 - minValue) / spread).toFloat() * (bottom - top)
        drawLine(Color.Gray.copy(alpha = 0.65f), Offset(left, baselineY), Offset(right, baselineY), strokeWidth = 1.5f)
        val path = androidx.compose.ui.graphics.Path()
        plotted.forEachIndexed { index, point ->
            val x = left + (right - left) * index / (plotted.size - 1).toFloat()
            val y = bottom - ((point.nav - minValue) / spread).toFloat() * (bottom - top)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }
}

@Composable
private fun PortfolioAllocationDonut(slices: List<PortfolioClassSlice>, modifier: Modifier = Modifier) {
    val colors = slices.map { classColor(it.assetClass) }
    Canvas(modifier) {
        val stroke = size.minDimension * 0.16f
        var startAngle = -90f
        slices.forEachIndexed { index, slice ->
            val sweep = (slice.share * 360.0).toFloat()
            drawArc(
                color = colors[index], startAngle = startAngle, sweepAngle = sweep,
                useCenter = false, topLeft = Offset(stroke / 2f, stroke / 2f),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Butt)
            )
            startAngle += sweep
        }
    }
}

@Composable
private fun PortfolioSectorDonut(
    sectors: List<PortfolioSectorSlice>,
    modifier: Modifier = Modifier
) {
    val colors = sectors.mapIndexed { index, sector ->
        sectorColor(index, sector.sector == "Вне отраслевого состава")
    }
    Canvas(modifier) {
        val stroke = size.minDimension * 0.18f
        var startAngle = -90f
        sectors.forEachIndexed { index, sector ->
            val sweep = (sector.shareOfPortfolio * 360.0).toFloat()
            if (sweep > 0f) {
                drawArc(
                    color = colors[index],
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                )
            }
            startAngle += sweep
        }
    }
}

private fun sectorColor(index: Int, isOutsideSectorMix: Boolean = false): Color = if (isOutsideSectorMix) Color(0xFF64748B) else when (index % 8) {
    0 -> Color(0xFF3989DB)
    1 -> Color(0xFF26A69A)
    2 -> Color(0xFFD5A344)
    3 -> Color(0xFF9B7BEA)
    4 -> Color(0xFFE27A64)
    5 -> Color(0xFF67A56B)
    6 -> Color(0xFF4FABC5)
    else -> Color(0xFFE052B5)
}

@Composable
private fun classColor(assetClass: PortfolioAssetClass): Color = when (assetClass) {
    PortfolioAssetClass.EQUITIES -> Color(0xFF3989DB)
    PortfolioAssetClass.FIXED_INCOME -> Color(0xFF26A69A)
    PortfolioAssetClass.GOLD -> Color(0xFFD5A344)
    PortfolioAssetClass.CASH -> Color(0xFF9299A5)
    PortfolioAssetClass.OTHER -> MaterialTheme.colorScheme.tertiary
}

private fun moneyAnalytics(value: Double): String =
    String.format(Locale("ru", "RU"), "%,.2f", value).replace(',', ' ')

private fun percentAnalytics(value: Double): String =
    String.format(Locale("ru", "RU"), "%.1f%%", value * 100.0)

@Composable
private fun PortfolioCompositionHistoryChart(
    history: PortfolioMarketHistoryResult,
    assets: List<AssetEntity>,
    loading: Boolean
) {
    // By default, composition history must cover the full available period,
    // starting with the first purchase just like the capital and benchmark charts.
    var selectedMonths by remember { mutableStateOf<Int?>(null) }
    val points = remember(history, assets, selectedMonths) {
        val ordered = history.points.sortedBy { it.dateKey }
        val latest = ordered.lastOrNull()?.let { java.time.LocalDate.parse(it.dateKey) }
        val cutoff = selectedMonths?.let { months -> latest?.minusMonths(months.toLong()) }
        ordered.asSequence()
            .filter { point -> cutoff == null || !java.time.LocalDate.parse(point.dateKey).isBefore(cutoff) }
            .mapNotNull { point ->
                val values = PortfolioAssetClass.entries.associateWith { 0.0 }.toMutableMap()
                assets.forEach { asset ->
                    val value = point.assetValues[asset.ticker.trim().uppercase()] ?: 0.0
                    if (value > 0.0) {
                        val assetClass = classifyPortfolioAsset(asset)
                        values[assetClass] = (values[assetClass] ?: 0.0) + value
                    }
                }
                values[PortfolioAssetClass.CASH] = (values[PortfolioAssetClass.CASH] ?: 0.0) + point.cashValue.coerceAtLeast(0.0)
                // Keep the composition timeline aligned with the capital history.
                // Any valued part not assigned to a known class remains visible as OTHER.
                val represented = values.values.sum()
                val unclassified = (point.totalValue - represented).coerceAtLeast(0.0)
                values[PortfolioAssetClass.OTHER] = unclassified
                val total = point.totalValue
                if (total <= 0.0) null else point.dateKey to values.mapValues { it.value / total }
            }.toList()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = selectedMonths == null, onClick = { selectedMonths = null },
            label = { Text("Всё время") })
        listOf(1, 3, 6, 12).forEach { months ->
            FilterChip(selected = selectedMonths == months, onClick = { selectedMonths = months },
                label = { Text(if (months == 12) "1Г" else "${months}М") })
        }
    }
    if (points.size < 2) {
        Text(
            if (loading) "Восстанавливаю исторический состав по операциям и котировкам MOEX…"
            else if (points.isEmpty()) "Исторический состав пока нельзя рассчитать: проверьте полноту истории операций и доступность котировок."
            else "Доступен один торговый день истории состава: ${points.first().first}.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        val plotted = if (points.size > 60) points.filterIndexed { index, _ -> index % ((points.size / 30).coerceAtLeast(1)) == 0 }.takeLast(60) else points
        val tracked = listOf(PortfolioAssetClass.EQUITIES, PortfolioAssetClass.FIXED_INCOME, PortfolioAssetClass.GOLD, PortfolioAssetClass.CASH, PortfolioAssetClass.OTHER)
        val colors = tracked.map { classColor(it) }
        val gridColor = MaterialTheme.colorScheme.outlineVariant
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val left = 5f; val right = size.width - 5f; val top = 8f; val bottom = size.height - 8f
            for (i in 0..4) {
                val y = top + (bottom - top) * i / 4f
                drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
            }
            tracked.forEachIndexed { seriesIndex, assetClass ->
                val path = androidx.compose.ui.graphics.Path()
                plotted.forEachIndexed { index, point ->
                    val x = left + (right - left) * index / (plotted.size - 1).toFloat()
                    val share = point.second[assetClass] ?: 0.0
                    val y = bottom - share.toFloat().coerceIn(0f, 1f) * (bottom - top)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, colors[seriesIndex], style = Stroke(width = 3f, cap = StrokeCap.Round))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.first().first, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(points.last().first, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            tracked.forEach { assetClass ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(classColor(assetClass)))
                    Text(when (assetClass) {
                        PortfolioAssetClass.EQUITIES -> "Акции"
                        PortfolioAssetClass.FIXED_INCOME -> "Облигации"
                        PortfolioAssetClass.GOLD -> "Золото"
                        PortfolioAssetClass.CASH -> "Кэш"
                        PortfolioAssetClass.OTHER -> "Прочее"
                    }, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
