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
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import ru.mycapital.app.data.BrokerAccountAssetEntity
import ru.mycapital.app.data.BrokerAccountCashEntity
import ru.mycapital.app.data.BrokerAccountEntity
import java.util.Locale

private const val ANALYTICS_ALL_ACCOUNTS = "ALL"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortfolioAnalyticsSection(
    assets: List<AssetEntity>,
    totalCash: Double,
    accounts: List<BrokerAccountEntity>,
    accountAssets: List<BrokerAccountAssetEntity>,
    accountCash: List<BrokerAccountCashEntity>,
    modifier: Modifier = Modifier
) {
    var selectedAccountId by remember { mutableStateOf(ANALYTICS_ALL_ACCOUNTS) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    val assetsByTicker = remember(assets) { assets.associateBy { it.ticker.trim().uppercase() } }
    val visibleAssets = remember(assets, accountAssets, selectedAccountId) {
        if (selectedAccountId == ANALYTICS_ALL_ACCOUNTS) assets
        else accountAssets.asSequence()
            .filter { it.accountId == selectedAccountId && it.quantity > 0.0 }
            .mapNotNull { position -> assetsByTicker[position.ticker.trim().uppercase()]?.copy(quantity = position.quantity) }
            .sortedBy { it.assetType + it.ticker }
            .toList()
    }
    val visibleCash = if (selectedAccountId == ANALYTICS_ALL_ACCOUNTS) totalCash
        else accountCash.firstOrNull { it.accountId == selectedAccountId }?.balance ?: 0.0
    val portfolioValue = visibleAssets.sumOf { (it.quantity * it.currentPrice).coerceAtLeast(0.0) } + visibleCash.coerceAtLeast(0.0)
    val classSlices = remember(visibleAssets, visibleCash) { calculatePortfolioClassSlices(visibleAssets, visibleCash) }
    val sectorSlices = remember(visibleAssets, portfolioValue) { calculatePortfolioSectorSlices(visibleAssets, portfolioValue) }
    val concentration = remember(visibleAssets, portfolioValue) { calculatePortfolioConcentration(visibleAssets, portfolioValue) }
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
                        selectedAccountId = ANALYTICS_ALL_ACCOUNTS
                        accountMenuExpanded = false
                    })
                    accounts.forEach { account ->
                        DropdownMenuItem(text = { Text(account.displayName) }, onClick = {
                            selectedAccountId = account.accountId
                            accountMenuExpanded = false
                        })
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(18.dp)) {
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
                    classSlices.filter { it.assetClass.targetShare != null }.forEach { slice ->
                        val target = slice.assetClass.targetShare!!
                        val delta = (slice.share - target) * 100.0
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(slice.assetClass.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text("${percentAnalytics(slice.share)} / ${percentAnalytics(target)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Box(Modifier.fillMaxWidth(slice.share.toFloat().coerceIn(0f, 1f)).height(7.dp).clip(CircleShape).background(classColor(slice.assetClass)))
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

        Card(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Структура по секторам", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Доля от всего портфеля. Фонды без раскрытого состава не распределяются по отраслям.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sectorSlices.isEmpty()) {
                    Text("Пока нет акций с определённой отраслью.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    sectorSlices.forEach { sector ->
                        var expanded by remember(sector.sector) { mutableStateOf(false) }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(sector.sector, fontWeight = FontWeight.Medium)
                                    Text("${moneyAnalytics(sector.value)} ₽", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(percentAnalytics(sector.shareOfPortfolio), fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(6.dp))
                                Text(if (expanded) "−" else "+", color = MaterialTheme.colorScheme.primary)
                            }
                            Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Box(Modifier.fillMaxWidth(sector.shareOfPortfolio.toFloat().coerceIn(0f, 1f)).height(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
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

        Card(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Концентрация портфеля", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Крупнейшие позиции по доле от всего портфеля", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Text("Внимание: крупнейшая позиция занимает не менее 25% портфеля. Проверьте риск концентрации.",
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

        Card(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Историческая структура", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Периоды: 1, 3, 6 и 12 месяцев", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Сейчас дневная история сохраняет общую стоимость активов и кэша, но не состав портфеля по классам и отраслям. Чтобы не показывать вымышленные данные, историческое распределение появится после добавления снимков состава портфеля.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PortfolioAllocationDonut(slices: List<PortfolioClassSlice>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.16f
        var startAngle = -90f
        slices.forEach { slice ->
            val sweep = (slice.share * 360.0).toFloat()
            drawArc(
                color = classColor(slice.assetClass), startAngle = startAngle, sweepAngle = sweep,
                useCenter = false, topLeft = Offset(stroke / 2f, stroke / 2f),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Butt)
            )
            startAngle += sweep
        }
    }
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
