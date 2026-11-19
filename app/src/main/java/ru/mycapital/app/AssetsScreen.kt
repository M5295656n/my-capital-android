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
import ru.mycapital.app.data.isIncomePayoutType
import ru.mycapital.app.data.PortfolioRepository
import ru.mycapital.app.data.PortfolioSnapshotEntity
import ru.mycapital.app.data.BrokerAccountEntity
import ru.mycapital.app.data.BrokerAccountCashEntity
import ru.mycapital.app.data.BrokerAccountAssetEntity

@Composable
internal fun AssetsScreen(
    assets: List<AssetEntity>,
    operations: List<OperationEntity>,
    payouts: List<PayoutEntity>,
    accounts: List<BrokerAccountEntity>,
    accountAssets: List<BrokerAccountAssetEntity>,
    cashBalance: Double,
    accountCash: List<BrokerAccountCashEntity>,
    onOpenAsset: (AssetEntity) -> Unit,
    onEditAsset: (AssetEntity) -> Unit,
    onDeleteAsset: (AssetEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedAccountId by remember { mutableStateOf(ALL_HISTORY_ACCOUNTS) }
    var selectedSoldYear by remember { mutableStateOf<Int?>(java.time.LocalDate.now().year) }
    val assetsByTicker = remember(assets) { assets.associateBy { it.ticker.uppercase() } }
    val displayedAssets = remember(assets, accountAssets, selectedAccountId) {
        if (selectedAccountId == ALL_HISTORY_ACCOUNTS) {
            assets.filter { it.quantity > 0.0000001 }
        } else {
            accountAssets.asSequence()
                .filter { it.accountId == selectedAccountId && it.quantity > 0.0 }
                .mapNotNull { position ->
                    assetsByTicker[position.ticker.uppercase()]?.copy(quantity = position.quantity)
                }
                .sortedBy { it.assetType + it.ticker }
                .toList()
        }
    }
    val saleYears = remember(operations) {
        (operations.filter { it.operationType == "Продажа" }
            .map { java.time.Instant.ofEpochMilli(it.operationDate).atZone(java.time.ZoneId.systemDefault()).year } +
            java.time.LocalDate.now().year)
            .distinct()
            .sortedDescending()
    }
    val saleResultsByOperationId = remember(operations) {
        calculateSaleLotResults(operations).associateBy { it.operationId }
    }
    val soldAssets = remember(operations, selectedAccountId, selectedSoldYear, assets, saleResultsByOperationId) {
        operations.asSequence()
            .filter { it.operationType == "Продажа" }
            .filter { operation ->
                val accountMatches = selectedAccountId == ALL_HISTORY_ACCOUNTS ||
                    historyAccountId(operation) == selectedAccountId
                val operationDate = java.time.Instant.ofEpochMilli(operation.operationDate)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                accountMatches && (selectedSoldYear == null || operationDate.year == selectedSoldYear)
            }
            .mapNotNull { operation ->
                val ticker = operation.ticker?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                    ?: assets.firstOrNull { it.id == operation.assetId }?.ticker?.trim()?.uppercase()
                ticker?.let { it to operation }
            }
            .groupBy({ it.first }, { it.second })
            .map { (ticker, sales) ->
                val results = sales.mapNotNull { saleResultsByOperationId[it.id] }
                val missingResultQuantity = sales
                    .filterNot { saleResultsByOperationId.containsKey(it.id) }
                    .sumOf { it.quantity.coerceAtLeast(0.0) }
                val unmatched = results.sumOf { it.unmatchedQuantity } + missingResultQuantity
                SoldAssetSummary(
                    ticker = ticker,
                    name = assets.firstOrNull { it.ticker.equals(ticker, true) }?.name ?: ticker,
                    quantity = sales.sumOf { it.quantity.coerceAtLeast(0.0) },
                    saleCount = sales.size,
                    realizedProfit = results.sumOf { it.profit },
                    matchedQuantity = results.sumOf { it.matchedQuantity },
                    unmatchedQuantity = unmatched,
                    hasCompleteCostBasis = results.size == sales.size && unmatched <= 0.0000001
                )
            }
            .sortedByDescending { kotlin.math.abs(it.realizedProfit) }
    }
    val totalRealizedProfit = soldAssets.sumOf { it.realizedProfit }
    val displayedCash = if (selectedAccountId == ALL_HISTORY_ACCOUNTS) cashBalance
        else accountCash.firstOrNull { it.accountId == selectedAccountId }?.balance ?: 0.0

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Портфель", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (accounts.isNotEmpty()) {
            AccountHistoryFilter(accounts, selectedAccountId, includeUnassigned = false) { selectedAccountId = it }
            if (selectedAccountId != ALL_HISTORY_ACCOUNTS) {
                Text(
                    "Показаны только позиции из последнего импортированного снимка выбранного договора.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 90.dp)
        ) {
            if (displayedCash > 0.0) {
                item(key = "portfolio-cash") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Canvas(Modifier.size(width = 58.dp, height = 48.dp)) {
                                val w = size.width
                                val h = size.height
                                drawRoundRect(
                                    color = Color(0xFFB58A32),
                                    topLeft = Offset(w * 0.18f, h * 0.25f),
                                    size = androidx.compose.ui.geometry.Size(w * 0.72f, h * 0.62f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
                                )
                                drawRoundRect(
                                    color = Color(0xFF2E8B63),
                                    topLeft = Offset(w * 0.08f, h * 0.12f),
                                    size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.66f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
                                )
                                drawRoundRect(
                                    color = Color(0xFF49A879),
                                    topLeft = Offset(w * 0.08f, h * 0.12f),
                                    size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.66f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
                                    style = Stroke(width = 1.4.dp.toPx())
                                )
                                drawCircle(Color(0xFFFFE6A3), radius = h * 0.17f, center = Offset(w * 0.46f, h * 0.45f))
                                drawCircle(Color(0xFF2E8B63), radius = h * 0.12f, center = Offset(w * 0.46f, h * 0.45f), style = Stroke(width = 1.2.dp.toPx()))
                                drawLine(Color(0xFFFFE6A3), Offset(w * 0.17f, h * 0.24f), Offset(w * 0.27f, h * 0.24f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                                drawLine(Color(0xFFFFE6A3), Offset(w * 0.65f, h * 0.66f), Offset(w * 0.76f, h * 0.66f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Кэш", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text("Денежные средства", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("${money(displayedCash)} ₽", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (displayedAssets.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().heightIn(min = 100.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (selectedAccountId == ALL_HISTORY_ACCOUNTS) "Пока нет активов. Добавьте первый актив во вкладке «Ещё»."
                            else "В последнем импортированном отчёте этого счёта нет открытых позиций.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(displayedAssets, key = { if (selectedAccountId == ALL_HISTORY_ACCOUNTS) "all:${it.id}" else "${selectedAccountId}:${it.ticker}" }) { asset ->
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpenAsset(asset) },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AssetTypeIcon(asset.assetType, asset.ticker)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(asset.ticker, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(asset.name, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                Text("${quantity(asset.quantity)} шт. · ${asset.assetType}", style = MaterialTheme.typography.bodySmall)
                                val bestDividend = payouts
                                    .filter { it.ticker.equals(asset.ticker, true) && it.payoutType == "Дивиденд" && it.status != "RECEIVED" }
                                    .sortedWith(compareBy<PayoutEntity> { dividendStatusPriority(it.status) }
                                        .thenBy { it.recordDate ?: it.expectedDate ?: Long.MAX_VALUE })
                                    .firstOrNull()
                                if (bestDividend != null && (asset.assetType.contains("акц", true) || asset.assetType.contains("stock", true))) {
                                    Text(
                                        "${money(bestDividend.amountPerUnit * asset.quantity)} ₽",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        maxLines = 1
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${money(asset.quantity * asset.currentPrice)} ₽", fontWeight = FontWeight.Bold)
                                Text("Средняя ${money(asset.averagePrice)} ₽", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Проданные активы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = selectedSoldYear == null,
                                    onClick = { selectedSoldYear = null },
                                    label = { Text("Все время") }
                                )
                            }
                            items(saleYears) { year ->
                                FilterChip(
                                    selected = selectedSoldYear == year,
                                    onClick = { selectedSoldYear = year },
                                    label = { Text(year.toString()) }
                                )
                            }
                        }
                        if (soldAssets.isEmpty()) {
                            Text("За выбранный период продаж не было.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(
                                "${if (soldAssets.any { !it.hasCompleteCostBasis }) "Результат по сопоставленным лотам" else "Финансовый результат"}: ${if (totalRealizedProfit > 0.0) "+" else ""}${money(totalRealizedProfit)} ₽",
                                fontWeight = FontWeight.Bold,
                                color = if (totalRealizedProfit >= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            if (soldAssets.any { !it.hasCompleteCostBasis }) {
                                Text(
                                    "По части продаж история покупок неполная. Прибыль показана только по сопоставленному количеству; недостающая себестоимость не оценивается.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            soldAssets.forEachIndexed { index, sold ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(sold.ticker, fontWeight = FontWeight.SemiBold)
                                        Text(sold.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                        Text(
                                            "${quantity(sold.quantity)} шт. · ${sold.saleCount} ${if (sold.saleCount == 1) "сделка" else "сделок"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (!sold.hasCompleteCostBasis) {
                                            Text(
                                                "Не сопоставлено: ${quantity(sold.unmatchedQuantity)} шт.",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        if (sold.matchedQuantity > 0.0000001) {
                                            Text(
                                                "${if (sold.realizedProfit > 0.0) "+" else ""}${money(sold.realizedProfit)} ₽",
                                                fontWeight = FontWeight.Bold,
                                                color = if (sold.realizedProfit >= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                            )
                                            Text(
                                                if (sold.hasCompleteCostBasis) "Прибыль / убыток" else "По сопоставленным лотам",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        } else {
                                            Text("Нет данных о себестоимости", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                if (index != soldAssets.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
