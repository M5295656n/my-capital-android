package ru.mycapital.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.BrokerAccountAssetEntity
import ru.mycapital.app.data.BrokerAccountCashEntity
import ru.mycapital.app.data.BrokerAccountEntity
import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.PayoutEntity
import ru.mycapital.app.data.PortfolioCompositionSnapshotEntity
import ru.mycapital.app.data.PortfolioDao
import ru.mycapital.app.data.PortfolioSnapshotEntity
import ru.mycapital.app.data.calculateReceivedPayoutTotals

@Composable
internal fun AnalyticsScreen(
    assets: List<AssetEntity>,
    marketValue: Double,
    cashBalance: Double,
    operations: List<OperationEntity>,
    payouts: List<PayoutEntity>,
    snapshots: List<PortfolioSnapshotEntity>,
    compositionSnapshots: List<ru.mycapital.app.data.PortfolioCompositionSnapshotEntity>,
    accounts: List<BrokerAccountEntity>,
    accountAssets: List<BrokerAccountAssetEntity>,
    accountCash: List<BrokerAccountCashEntity>,
    dao: ru.mycapital.app.data.PortfolioDao,
    modifier: Modifier = Modifier
) {
    var selectedAccountId by remember { mutableStateOf(ALL_HISTORY_ACCOUNTS) }
    val assetsByTicker = remember(assets) { assets.associateBy { it.ticker.trim().uppercase() } }
    val analyticsAssets = remember(assets, accountAssets, selectedAccountId) {
        if (selectedAccountId == ALL_HISTORY_ACCOUNTS) assets
        else accountAssets.asSequence()
            .filter { it.accountId == selectedAccountId && it.quantity > 0.0 }
            .mapNotNull { position -> assetsByTicker[position.ticker.trim().uppercase()]?.copy(quantity = position.quantity) }
            .sortedBy { it.assetType + it.ticker }
            .toList()
    }
    val analyticsCash = if (selectedAccountId == ALL_HISTORY_ACCOUNTS) cashBalance
        else accountCash.firstOrNull { it.accountId == selectedAccountId }?.balance ?: 0.0
    val analyticsOperations = remember(operations, selectedAccountId) {
        if (selectedAccountId == ALL_HISTORY_ACCOUNTS) operations
        else operations.filter { historyAccountId(it) == selectedAccountId }
    }
    val analyticsHistoryAssets = remember(assets, analyticsAssets, analyticsOperations, selectedAccountId) {
        val currentByTicker = analyticsAssets.associateBy { it.ticker.trim().uppercase() }
        val allByTicker = assets.associateBy { it.ticker.trim().uppercase() }
        val historicalTickers = analyticsOperations.asSequence()
            .filter { it.operationType == "Покупка" || it.operationType == "Продажа" }
            .mapNotNull { it.ticker?.trim()?.uppercase()?.takeIf(String::isNotBlank) }
            .toSet()
        val tickers = currentByTicker.keys + historicalTickers
        tickers.mapNotNull { ticker ->
            currentByTicker[ticker] ?: allByTicker[ticker]?.copy(quantity = 0.0)
        }.sortedBy { it.ticker }
    }
    var analyticsMarketHistory by remember { mutableStateOf(PortfolioMarketHistoryResult(emptyList(), null, emptyList())) }
    var analyticsHistoryLoading by remember { mutableStateOf(false) }
    var analyticsHistoryRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(analyticsAssets, analyticsOperations, analyticsCash, selectedAccountId, analyticsHistoryRefresh) {
        analyticsHistoryLoading = true
        try {
            analyticsMarketHistory = withContext(Dispatchers.IO) {
                buildPortfolioMarketHistory(
                    assets = analyticsHistoryAssets,
                    operations = analyticsOperations,
                    currentCash = analyticsCash,
                    range = PortfolioHistoryRange.ALL,
                    includeInternalTransfers = selectedAccountId != ALL_HISTORY_ACCOUNTS,
                    includedBrokerAccountIds = accounts.map { it.accountId }.toSet()
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            analyticsMarketHistory = PortfolioMarketHistoryResult(emptyList(), null, emptyList())
        } finally {
            analyticsHistoryLoading = false
        }
    }
    val analyticsMarketValue = analyticsAssets.sumOf { (it.quantity * it.currentPrice).coerceAtLeast(0.0) }
    val selectedTickers = remember(accountAssets, selectedAccountId) {
        accountAssets.filter { it.accountId == selectedAccountId && it.quantity > 0.0 }.map { it.ticker.uppercase() }.toSet()
    }
    val analyticsPayouts = remember(payouts, selectedAccountId, selectedTickers) {
        if (selectedAccountId == ALL_HISTORY_ACCOUNTS) payouts
        else payouts.filter { payout ->
            val payoutAccountId = historyAccountId(payout.sourceKey)
            if (payoutAccountId != null) payoutAccountId == selectedAccountId
            else false
        }
    }
    val invested = analyticsAssets.sumOf { it.quantity * it.averagePrice }
    val unrealized = analyticsMarketValue - invested
    val realizedResult = calculateRealizedTradingResult(analyticsOperations)
    val receivedPayoutTotals = calculateReceivedPayoutTotals(analyticsPayouts)
    val receivedDividends = receivedPayoutTotals.dividends
    val receivedCoupons = receivedPayoutTotals.coupons
    val receivedPrincipalReturns = receivedPayoutTotals.returnedPrincipal
    val redeemedCostBasis = calculateRedeemedPositionCostBasis(
        analyticsHistoryAssets,
        analyticsOperations,
        analyticsPayouts
    )
    val totalInvestmentResult =
        unrealized + realizedResult.profit + receivedPayoutTotals.totalNetAmount - redeemedCostBasis
    val totalValue = analyticsMarketValue + analyticsCash
    val returnPercent = if (invested > 0.0) unrealized / invested * 100.0 else 0.0
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            PortfolioAnalyticsSection(
                assets = analyticsAssets,
                cashBalance = analyticsCash,
                accounts = accounts,
                selectedAccountId = selectedAccountId,
                onAccountSelected = { selectedAccountId = it },
            )
        }
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Стоимость капитала", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${money(totalValue)} ₽", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Рыночная стоимость активов", modifier = Modifier.weight(1.05f), maxLines = 2)
                        Text(
                            "${money(analyticsMarketValue)} ₽",
                            modifier = Modifier.weight(0.95f),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            fontSize = 14.sp
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Свободные деньги")
                        Text("${money(analyticsCash)} ₽", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (selectedAccountId == ALL_HISTORY_ACCOUNTS) {
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Нереализованный результат", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        (if (unrealized > 0) "+" else "") + "${money(unrealized)} ₽",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (unrealized >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text("К себестоимости остатков: ${String.format(java.util.Locale("ru", "RU"), "%.2f%%", returnPercent)}")
                    Text("Показатель относится только к текущим остаткам бумаг и не включает выплаты или результат закрытых позиций.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Реализованный результат сделок", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        (if (realizedResult.profit > 0.0) "+" else "") + "${money(realizedResult.profit)} ₽",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (realizedResult.profit >= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    if (realizedResult.unmatchedQuantity > 0.0000001) {
                        Text("Истории покупок не хватает для расчёта по ${String.format(java.util.Locale("ru", "RU"), "%.4f", realizedResult.unmatchedQuantity)} шт. продаж. Это частичный результат по FIFO, а не полный итог.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("Расчёт по FIFO на основании записанных покупок и продаж, с учётом комиссий. Внесённые вручную начальные остатки без истории покупок не позволяют восстановить прошлую себестоимость.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Полученные дивиденды и купоны", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Дивиденды")
                        Text("${money(receivedDividends)} ₽", fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Купоны")
                        Text("${money(receivedCoupons)} ₽", fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Возврат номинала облигаций")
                        Text("${money(receivedPrincipalReturns)} ₽", fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider()
                    Text("Общий результат с учётом продаж и выплат", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        (if (totalInvestmentResult > 0) "+" else "") + "${money(totalInvestmentResult)} ₽",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (totalInvestmentResult >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text("Сумма нереализованного результата по текущим остаткам, реализованного результата по FIFO, полученных выплат и возвращённого номинала облигаций за вычетом себестоимости полностью погашенных позиций. Погашение и амортизация не считаются доходом, но учитываются в общем денежном результате. Если история покупок неполная, итог по сделкам также неполный.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        } else {
            item {
                Text(
                    "Доходность по отдельному счёту пока не рассчитывается: себестоимость позиций хранится на уровне совокупного портфеля, а не каждого брокерского договора.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item { IndexBenchmarkSection(analyticsMarketHistory.points, dao) }
    }
}
