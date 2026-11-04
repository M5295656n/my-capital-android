package ru.mycapital.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import ru.mycapital.app.data.isFullyExecutedBrokerTrade
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ru.mycapital.app.data.QuoteRefreshWorker
import ru.mycapital.app.data.CorporateEventsWorker
import ru.mycapital.app.data.PortfolioSnapshotWorker
import java.util.concurrent.TimeUnit
import java.time.YearMonth
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.calculatePortfolioAllocation
import ru.mycapital.app.data.PortfolioDatabase
import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.PayoutEntity
import ru.mycapital.app.data.PortfolioRepository
import ru.mycapital.app.data.PortfolioSnapshotEntity
import ru.mycapital.app.data.BrokerAccountEntity
import ru.mycapital.app.data.BrokerAccountCashEntity
import ru.mycapital.app.data.BrokerAccountAssetEntity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyCapitalApp() }
    }
}

private enum class Screen(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Overview("Обзор", Icons.Default.Home),
    Assets("Портфель", Icons.Default.AccountBalanceWallet),
    Payouts("Выплаты", Icons.Default.Payments),
    Analytics("Аналитика", Icons.Default.ShowChart),
    More("Ещё", Icons.Default.MoreHoriz),
    Accounts("Счета", Icons.Default.AccountBalance),
    Operations("Операции", Icons.Default.SwapHoriz)
}

@Composable
private fun MyCapitalApp() {
    val context = LocalContext.current
    val dao = remember { PortfolioDatabase.getInstance(context).portfolioDao() }
    val assets by dao.observeAssets().collectAsState(initial = emptyList())
    val brokerAccounts by dao.observeBrokerAccounts().collectAsState(initial = emptyList())
    val brokerAccountCash by dao.observeBrokerAccountCashAccounts().collectAsState(initial = emptyList())
    val brokerAccountAssets by dao.observeAllBrokerAccountAssets().collectAsState(initial = emptyList())
    val total by dao.observePortfolioValue().collectAsState(initial = 0.0)
    val cashBalance by dao.observeCashBalance().collectAsState(initial = 0.0)
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(Screen.Overview) }
    var moreDestination by remember { mutableStateOf(Screen.More) }
    var showAddAsset by remember { mutableStateOf(false) }
    var assetToEdit by remember { mutableStateOf<AssetEntity?>(null) }
    var assetToDelete by remember { mutableStateOf<AssetEntity?>(null) }
    var showAddOperation by remember { mutableStateOf(false) }
    var showCashFlow by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val operations by dao.observeOperations().collectAsState(initial = emptyList())
    val payouts by dao.observePayouts().collectAsState(initial = emptyList())
    var selectedOperationAccount by remember { mutableStateOf("ALL") }
    var selectedPayoutAccount by remember { mutableStateOf("ALL") }
    val portfolioSnapshots by dao.observePortfolioSnapshots().collectAsState(initial = emptyList())
    var showAddPayout by remember { mutableStateOf(false) }
    var payoutToConfirm by remember { mutableStateOf<PayoutEntity?>(null) }
    var importedReport by remember { mutableStateOf<SberBrokerReport?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var isParsingReport by remember { mutableStateOf(false) }
    var isImportingReport by remember { mutableStateOf(false) }
    var importResultMessage by remember { mutableStateOf<String?>(null) }
    var isReconcilingReport by remember { mutableStateOf(false) }
    var isExportingData by remember { mutableStateOf(false) }
    var exportResultMessage by remember { mutableStateOf<String?>(null) }
    val reportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            isParsingReport = true
            scope.launch {
                try {
                    val parsed = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            SberBrokerReportParser.parse(input)
                        } ?: error("Не удалось открыть выбранный файл.")
                    }
                    importedReport = parsed
                    importError = null
                } catch (error: Exception) {
                    importError = error.message ?: "Не удалось прочитать брокерский отчёт."
                } finally {
                    isParsingReport = false
                }
            }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) {
            isExportingData = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error("Не удалось создать файл архива.")
                        output.use {
                            writePortfolioExportZip(
                                it, assets, operations, payouts, brokerAccounts,
                                brokerAccountCash, brokerAccountAssets, cashBalance ?: 0.0
                            )
                        }
                    }
                    exportResultMessage = "Архив ZIP сохранён. Внутри CSV-файлы с активами, счетами, позициями по договорам, операциями и выплатами. Полные номера договоров не экспортируются."
                } catch (error: Exception) {
                    errorMessage = error.message ?: "Не удалось экспортировать данные."
                } finally {
                    isExportingData = false
                }
            }
        }
    }
    val repository = remember { PortfolioRepository(PortfolioDatabase.getInstance(context)) }
    val quoteWorkInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow("moex_quote_refresh_manual")
        .collectAsState(initial = emptyList())
    val eventWorkInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow("moex_events_refresh_manual")
        .collectAsState(initial = emptyList())
    val isRefreshingMarketData = quoteWorkInfos.any { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.BLOCKED } ||
        eventWorkInfos.any { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.BLOCKED }
    val quoteRefreshStatus = quoteWorkInfos.firstOrNull()?.let { info ->
        when (info.state) {
            androidx.work.WorkInfo.State.RUNNING -> "Обновляю котировки MOEX…"
            androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED -> "Обновление котировок ожидает запуска…"
            androidx.work.WorkInfo.State.SUCCEEDED -> info.outputData.getString("summary") ?: "Обновление котировок завершено."
            androidx.work.WorkInfo.State.FAILED -> "Не удалось обновить котировки. Проверьте подключение к интернету."
            androidx.work.WorkInfo.State.CANCELLED -> "Обновление котировок отменено."
        }
    }

    LaunchedEffect(Unit) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequestBuilder<QuoteRefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "moex_quote_refresh", ExistingPeriodicWorkPolicy.UPDATE, periodic
        )
        val eventsPeriodic = PeriodicWorkRequestBuilder<CorporateEventsWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "moex_corporate_events", ExistingPeriodicWorkPolicy.UPDATE, eventsPeriodic
        )
        val snapshotPeriodic = PeriodicWorkRequestBuilder<PortfolioSnapshotWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "portfolio_daily_snapshots", ExistingPeriodicWorkPolicy.UPDATE, snapshotPeriodic
        )
        val firstSnapshot = OneTimeWorkRequestBuilder<PortfolioSnapshotWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "portfolio_initial_snapshot", ExistingWorkPolicy.KEEP, firstSnapshot
        )
    }

    val refreshQuotes: () -> Unit = {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<QuoteRefreshWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "moex_quote_refresh_manual", ExistingWorkPolicy.REPLACE, request
        )
    }
    val refreshEvents: () -> Unit = {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<CorporateEventsWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "moex_events_refresh_manual", ExistingWorkPolicy.REPLACE, request
        )
    }
    val refreshAllMarketData: () -> Unit = {
        refreshQuotes()
        refreshEvents()
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF2588FF),
        secondary = androidx.compose.ui.graphics.Color(0xFF55C99A),
        background = androidx.compose.ui.graphics.Color(0xFF171922),
        surface = androidx.compose.ui.graphics.Color(0xFF202331),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2B2E3A),
        onBackground = androidx.compose.ui.graphics.Color(0xFFF4F5F8),
        onSurface = androidx.compose.ui.graphics.Color(0xFFF4F5F8),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFB3B7C4)
    )) {
        Scaffold(
            floatingActionButton = {
                if (selected == Screen.Assets || selected == Screen.Operations || selected == Screen.Payouts) {
                    FloatingActionButton(onClick = { when (selected) { Screen.Assets -> showAddAsset = true; Screen.Operations -> showAddOperation = true; Screen.Payouts -> showAddPayout = true; else -> Unit } }) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить актив")
                    }
                }
            },
            bottomBar = {
                NavigationBar {
                    listOf(Screen.Overview, Screen.Assets, Screen.Payouts, Screen.Analytics, Screen.More).forEach { screen ->
                        NavigationBarItem(
                            selected = selected == screen || (screen == Screen.More && (selected == Screen.Accounts || selected == Screen.Operations)),
                            onClick = { selected = screen },
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title, fontSize = 10.sp) }
                        )
                    }
                }
            }
        ) { padding ->
            when (selected) {
                Screen.Overview -> OverviewScreen(assets, total, cashBalance ?: 0.0, payouts, operations, portfolioSnapshots, refreshAllMarketData, isRefreshingMarketData, quoteRefreshStatus, onOpenSettings = { selected = Screen.More }, modifier = Modifier.padding(padding))
                Screen.Assets -> AssetsScreen(assets, operations, brokerAccounts, onEditAsset = { assetToEdit = it }, onDeleteAsset = { assetToDelete = it }, modifier = Modifier.padding(padding))
                Screen.Accounts -> AccountsScreen(brokerAccounts, brokerAccountCash, brokerAccountAssets, assets, operations, payouts, { account -> scope.launch(Dispatchers.IO) { dao.upsertBrokerAccount(account) } }, Modifier.padding(padding))
                Screen.Operations -> OperationsScreen(operations, brokerAccounts, selectedOperationAccount, { selectedOperationAccount = it }, onAddCashFlow = { showCashFlow = true }, modifier = Modifier.padding(padding))
                Screen.Payouts -> PayoutsScreen(payouts, brokerAccounts, selectedPayoutAccount, { selectedPayoutAccount = it }, onConfirmReceipt = { payoutToConfirm = it }, onRefreshEvents = refreshEvents, modifier = Modifier.padding(padding))
                Screen.Analytics -> AnalyticsScreen(assets, total, cashBalance ?: 0.0, operations, payouts, portfolioSnapshots, dao, Modifier.padding(padding))
                Screen.More -> MoreScreen(
                    isParsingReport = isParsingReport,
                    isExportingData = isExportingData,
                    onImportReport = { reportPicker.launch(arrayOf("text/html", "application/xhtml+xml", "application/octet-stream")) },
                    onExportData = { exportPicker.launch("my-capital-export.zip") },
                    onOpenAccounts = { selected = Screen.Accounts },
                    onOpenOperations = { selected = Screen.Operations },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    if (importedReport != null) {
        BrokerReportPreviewDialog(
            report = importedReport!!,
            currentAssets = assets,
            currentCash = cashBalance ?: 0.0,
            isImporting = isImportingReport,
            isReconciling = isReconcilingReport,
            onDismiss = { if (!isImportingReport && !isReconcilingReport) importedReport = null },
            onImportAndReconcile = {
                val reportToImport = importedReport!!
                isReconcilingReport = true
                isImportingReport = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            repository.importAndReconcileBrokerReport(reportToImport)
                        }
                        val snapshot = result.reconciliation
                        val history = result.history
                        importResultMessage =
                            "Отчёт ${reportToImport.accountId} обработан полностью. " +
                            "Сверено позиций: ${snapshot.matchedAssets}; не сопоставлено: ${snapshot.unmatchedReportAssets}. " +
                            "Общий денежный остаток: ${money(snapshot.resultingCash)} ₽. " +
                            "Добавлено операций: ${history.imported}; выплат: ${history.payoutsImported}; " +
                            "дубликатов пропущено: ${history.duplicates}; неисполненных сделок пропущено: ${history.unsettledSkipped}."
                        importedReport = null
                    } catch (error: Exception) {
                        importError = error.message ?: "Не удалось импортировать и сверить брокерский отчёт."
                    } finally {
                        isReconcilingReport = false
                        isImportingReport = false
                    }
                }
            }
        )
    }
    if (exportResultMessage != null) {
        AlertDialog(
            onDismissRequest = { exportResultMessage = null },
            title = { Text("Экспорт завершён") },
            text = { Text(exportResultMessage.orEmpty()) },
            confirmButton = { TextButton(onClick = { exportResultMessage = null }) { Text("Готово") } }
        )
    }
    if (importResultMessage != null) {
        AlertDialog(
            onDismissRequest = { importResultMessage = null },
            title = { Text("Импорт завершён") },
            text = { Text(importResultMessage.orEmpty()) },
            confirmButton = { TextButton(onClick = { importResultMessage = null }) { Text("Готово") } }
        )
    }
    if (importError != null) {
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Не удалось прочитать отчёт") },
            text = { Text(importError.orEmpty()) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("Понятно") } }
        )
    }
    if (showAddPayout) {
        AddPayoutDialog(
            assets = assets,
            onDismiss = { showAddPayout = false },
            onSave = { asset, payoutType, amountPerUnit, quantity, tax, received, expectedDate ->
                scope.launch {
                    try {
                        repository.recordPayout(asset?.id, asset?.ticker ?: "", payoutType, amountPerUnit, quantity, tax, received, expectedDate)
                        showAddPayout = false
                    } catch (error: Exception) {
                        errorMessage = error.message ?: "Не удалось сохранить выплату"
                    }
                }
            }
        )
    }
    if (payoutToConfirm != null) {
        ReceivePayoutDialog(
            payout = payoutToConfirm!!,
            onDismiss = { payoutToConfirm = null },
            onConfirm = { tax ->
                val payout = payoutToConfirm!!
                scope.launch {
                    try {
                        val credited = withContext(Dispatchers.IO) {
                            repository.confirmPayoutReceived(payout.id, tax)
                        }
                        if (!credited) errorMessage = "Эта выплата уже была подтверждена ранее."
                        payoutToConfirm = null
                    } catch (error: Exception) {
                        errorMessage = error.message ?: "Не удалось подтвердить выплату"
                    }
                }
            }
        )
    }
    if (showCashFlow) {
        CashFlowDialog(
            onDismiss = { showCashFlow = false },
            onSave = { isDeposit, amount ->
                scope.launch {
                    try {
                        repository.recordCashFlow(isDeposit, amount)
                        showCashFlow = false
                    } catch (error: Exception) {
                        errorMessage = error.message ?: "Не удалось сохранить движение денег"
                    }
                }
            }
        )
    }
    if (showAddOperation) {
        AddOperationDialog(assets, { showAddOperation = false }) { asset, isBuy, qty, price, fee ->
            scope.launch {
                try {
                    repository.recordTrade(asset.id, isBuy, qty, price, fee)
                    showAddOperation = false
                } catch (error: Exception) {
                    errorMessage = error.message ?: "Не удалось сохранить сделку"
                }
            }
        }
    }
    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Операция не выполнена") },
            text = { Text(errorMessage.orEmpty()) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("Понятно") } }
        )
    }
    if (assetToDelete != null) {
        val target = assetToDelete!!
        AlertDialog(
            onDismissRequest = { assetToDelete = null },
            title = { Text("Удалить ${target.ticker}?") },
            text = { Text("Будет удалена только карточка актива и её текущая позиция. История операций, дивиденды и купоны не удаляются. Если актив импортируется повторно из брокерского отчёта, он может появиться снова.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            dao.deleteAsset(target)
                            assetToDelete = null
                        } catch (error: Exception) {
                            errorMessage = error.message ?: "Не удалось удалить актив."
                            assetToDelete = null
                        }
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { assetToDelete = null }) { Text("Отмена") } }
        )
    }
    if (assetToEdit != null) {
        EditAssetDialog(
            asset = assetToEdit!!,
            onDismiss = { assetToEdit = null },
            onSave = { updated ->
                scope.launch {
                    try {
                        dao.updateAsset(updated)
                        assetToEdit = null
                    } catch (error: Exception) {
                        errorMessage = error.message ?: "Не удалось обновить актив."
                    }
                }
            }
        )
    }
    if (showAddAsset) {
        AddAssetDialog(
            onDismiss = { showAddAsset = false },
            onSave = { ticker, name, type, quantity, average, current ->
                scope.launch {
                    dao.insertAsset(
                        AssetEntity(
                            ticker = ticker.trim().uppercase(),
                            name = name.trim(),
                            assetType = type.trim().ifBlank { "Акция" },
                            quantity = quantity,
                            averagePrice = average,
                            currentPrice = current
                        )
                    )
                    showAddAsset = false
                }
            }
        )
    }
}

@Composable
private fun OverviewScreen(
    assets: List<AssetEntity>,
    total: Double,
    cashBalance: Double,
    payouts: List<PayoutEntity>,
    operations: List<OperationEntity>,
    snapshots: List<PortfolioSnapshotEntity>,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
    quoteRefreshStatus: String?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val portfolioTotal = total + cashBalance
    var marketData by remember { mutableStateOf(MarketOverviewData()) }
    var marketLoading by remember { mutableStateOf(false) }
    val marketScope = rememberCoroutineScope()
    val loadMarketData: () -> Unit = {
        marketScope.launch {
            marketLoading = true
            marketData = runCatching { withContext(kotlinx.coroutines.Dispatchers.IO) { MarketOverviewClient().load(assets.filter { it.quantity > 0.0 }.map { it.ticker.uppercase() }.toSet()) } }
                .getOrElse { marketData.copy(error = it.message ?: "Не удалось загрузить рыночные данные") }
            marketLoading = false
        }
    }
    LaunchedEffect(Unit) { loadMarketData() }
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
    val receivedPayouts = payouts.filter { it.status == "RECEIVED" }
        .sumOf { (it.reportedNetAmount ?: (it.totalAmount - it.taxAmount)).coerceAtLeast(0.0) }
    val realized = calculateRealizedTradingResult(operations).profit
    val openCost = assets.sumOf { it.quantity * it.averagePrice }
    val unrealized = total - openCost
    val totalProfit = if (operations.any { it.operationType == "Пополнение" || it.operationType == "Вывод" }) {
        portfolioTotal - netContributions
    } else {
        // Without a recorded funding history, avoid presenting cost-basis-only
        // unrealized P/L as lifetime profit.
        unrealized + realized + receivedPayouts
    }
    val profitPercent = when {
        netContributions > 0.0 -> totalProfit / netContributions * 100.0
        openCost > 0.0 -> (unrealized + realized + receivedPayouts) / openCost * 100.0
        else -> null
    }
    var selectedRange by remember { mutableStateOf(PortfolioHistoryRange.ONE_YEAR) }
    val history = remember(snapshots, selectedRange) {
        calculatePortfolioHistoryPoints(snapshots, selectedRange)
    }
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
                if (pullDistance >= with(density) { 72.dp.toPx() } && !isRefreshing) { onRefresh(); loadMarketData() }
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.TrendingUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                    Text("Мой капитал", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Настройки")
                }
            }
            if (isRefreshing || marketLoading || pullDistance > 0f) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (isRefreshing || marketLoading) {
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
            // Detailed MOEX diagnostics stay out of the dashboard; refresh feedback is the compact spinner above.
        }
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PortfolioHistoryRange.entries.forEach { range ->
                    val selected = selectedRange == range
                    Text(
                        range.label,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { selectedRange = range }
                            .padding(vertical = 10.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Общая стоимость портфеля", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                        Icon(Icons.Default.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${money(portfolioTotal)} ₽", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${if (totalProfit >= 0) "+" else ""}${money(totalProfit)} ₽" +
                            (profitPercent?.let { "  ·  ${if (it >= 0) "+" else ""}${String.format(java.util.Locale("ru", "RU"), "%.1f", it)}%" } ?: ""),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (totalProfit >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text(
                        if (netContributions > 0.0) "Вложено собственных средств: ${money(netContributions)} ₽"
                        else "История пополнений пока неполная — сумма собственных вложений не определена",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("● Стоимость портфеля", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("┄ Вложенные средства", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (history.size < 2) {
                        Text(
                            "График появится после накопления минимум двух дневных снимков.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val values = history.map { it.totalValue }
                        val moscowZone = java.time.ZoneId.of("Europe/Moscow")
                        val investedLine = history.map { point ->
                            val endOfDay = java.time.LocalDate.parse(point.dateKey).plusDays(1)
                                .atStartOfDay(moscowZone).toInstant().toEpochMilli()
                            operations.asSequence()
                                .filter { it.operationDate < endOfDay && (it.operationType == "Пополнение" || it.operationType == "Вывод") }
                                .sumOf { if (it.operationType == "Пополнение") it.price else -it.price }
                        }
                        val minValue = (values + investedLine).minOrNull() ?: 0.0
                        val maxValue = (values + investedLine).maxOrNull() ?: 1.0
                        val span = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
                        val portfolioColor = MaterialTheme.colorScheme.primary
                        val investedColor = MaterialTheme.colorScheme.onSurfaceVariant
                        val gridColor = MaterialTheme.colorScheme.outlineVariant
                        Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                            val left = 8f
                            val right = size.width - 8f
                            val top = 8f
                            val bottom = size.height - 8f
                            for (i in 0..3) {
                                val y = top + (bottom - top) * i / 3f
                                drawLine(gridColor, Offset(left, y), Offset(right, y), 1f)
                            }
                            fun drawSeries(series: List<Double>, color: androidx.compose.ui.graphics.Color, dashed: Boolean) {
                                val path = Path()
                                series.forEachIndexed { index, value ->
                                    val x = left + (right - left) * index / (series.size - 1).toFloat()
                                    val y = bottom - ((value - minValue) / span).toFloat() * (bottom - top)
                                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(
                                    path, color,
                                    style = Stroke(width = if (dashed) 3f else 4f, cap = StrokeCap.Round,
                                        pathEffect = if (dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(9f, 7f)) else null)
                                )
                            }
                            drawSeries(values, portfolioColor, false)
                            drawSeries(investedLine, investedColor, true)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverviewMetricCard(
                    title = "Прибыль общая",
                    value = "${if (totalProfit >= 0) "+" else ""}${money(totalProfit)} ₽",
                    subtitle = profitPercent?.let { "${if (it >= 0) "+" else ""}${String.format(java.util.Locale("ru", "RU"), "%.1f", it)}%" } ?: "Нет полной истории",
                    modifier = Modifier.weight(1f),
                    valueColor = if (totalProfit >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                OverviewMetricCard(
                    title = "За день",
                    value = dailyChange?.let { "${if (it >= 0) "+" else ""}${money(it)} ₽" } ?: "—",
                    subtitle = if (latest != null && previous != null && previous.totalValue > 0.0) {
                        "${if (dailyChange!! >= 0) "+" else ""}${String.format(java.util.Locale("ru", "RU"), "%.2f", dailyChange / previous.totalValue * 100.0)}%"
                    } else "Нет данных",
                    modifier = Modifier.weight(1f),
                    valueColor = if ((dailyChange ?: 0.0) >= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                OverviewMetricCard(
                    title = "Доходность",
                    value = annualizedReturn?.let { "${String.format(java.util.Locale("ru", "RU"), "%.1f", it)}%" } ?: "—",
                    subtitle = "годовых",
                    modifier = Modifier.weight(1f),
                    valueColor = if ((annualizedReturn ?: 0.0) >= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }

        item {
            MarketQuotesSection(marketData = marketData)
        }
        item {
            MarketMoversSection(title = "Топ роста за день", rows = marketData.gainers, positive = true)
        }
        item {
            MarketMoversSection(title = "Топ падений за день", rows = marketData.losers, positive = false)
        }
        item {
            DividendHistorySection(payouts = payouts)
        }

    }
}


private fun sectorForTicker(ticker: String): String = when (ticker.uppercase()) {
    "SBER", "SBERP", "T", "VTBR", "MOEX", "BSPB", "CBOM" -> "Финансы"
    "LKOH", "ROSN", "TATN", "GAZP", "NVTK", "SNGS", "SNGSP", "BANE", "TRNFP" -> "Нефть и газ"
    "YDEX", "OZON", "MVID", "FIXP", "X5", "MGNT", "LENT" -> "Потребительский сектор"
    "GMKN", "NLMK", "CHMF", "MAGN", "RUAL", "ALRS" -> "Металлы и добыча"
    "MTSS", "RTKM", "VEON" -> "Телеком"
    "AFLT", "FLOT" -> "Транспорт"
    "PIKK", "SMLT", "LSRG" -> "Недвижимость"
    "IRAO", "HYDR", "FEES", "UPRO" -> "Энергетика"
    else -> "Прочие акции"
}

@Composable
private fun OverviewMetricCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 1)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 2)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MoreScreen(
    isParsingReport: Boolean,
    isExportingData: Boolean,
    onImportReport: () -> Unit,
    onExportData: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenOperations: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ещё", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Дополнительные инструменты и управление данными", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenAccounts)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountBalance, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Брокерские счета", fontWeight = FontWeight.SemiBold)
                    Text("Договоры и остатки по счетам", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenOperations)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("История операций", fontWeight = FontWeight.SemiBold)
                    Text("Покупки, продажи, пополнения и выводы", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }
        Text("Файлы и резервные копии", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onImportReport, enabled = !isParsingReport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FileOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isParsingReport) "Читаю отчёт…" else "Импорт отчёта Сбербанка (HTML)")
        }
        OutlinedButton(onClick = onExportData, enabled = !isExportingData, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FileDownload, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isExportingData) "Экспортирую данные…" else "Экспортировать данные (ZIP)")
        }
    }
}

@Composable
private fun AssetsScreen(
    assets: List<AssetEntity>,
    operations: List<OperationEntity>,
    accounts: List<BrokerAccountEntity>,
    onEditAsset: (AssetEntity) -> Unit,
    onDeleteAsset: (AssetEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var logoCatalog by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        logoCatalog = withContext(Dispatchers.IO) { AssetLogoCatalog.load(context) }
    }
    var selectedAssetForHistory by remember { mutableStateOf<AssetEntity?>(null) }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Активы", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (assets.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Пока нет активов. Нажмите +, чтобы добавить первый.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 90.dp)
            ) {
                items(assets, key = { it.id }) { asset ->
                    Card(
                        Modifier.fillMaxWidth().clickable { selectedAssetForHistory = asset },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AssetTypeIcon(asset.assetType, asset.ticker, logoCatalog)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(asset.ticker, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(asset.name, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                Text("${quantity(asset.quantity)} шт. · ${asset.assetType}", style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${money(asset.quantity * asset.currentPrice)} ₽", fontWeight = FontWeight.Bold)
                                Text("Средняя ${money(asset.averagePrice)} ₽", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    selectedAssetForHistory?.let { asset ->
        AssetPurchaseHistoryDialog(
            asset = asset,
            operations = operations,
            accounts = accounts,
            onDismiss = { selectedAssetForHistory = null }
        )
    }
}

@Composable
private fun DoubleHeadedEagleIcon(tint: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(31.dp)) {
        val sx = size.width / 100f
        val sy = size.height / 100f
        val eagle = Path().apply {
            // Two outward-facing heads and their necks.
            moveTo(47f, 39f)
            cubicTo(39f, 29f, 34f, 20f, 25f, 18f)
            cubicTo(20f, 17f, 15f, 19f, 11f, 24f)
            lineTo(3f, 20f)
            lineTo(8f, 31f)
            lineTo(2f, 35f)
            lineTo(15f, 37f)
            cubicTo(20f, 34f, 24f, 30f, 29f, 30f)
            cubicTo(35f, 31f, 39f, 41f, 44f, 49f)
            lineTo(44f, 60f)
            cubicTo(34f, 56f, 24f, 50f, 13f, 45f)
            lineTo(7f, 51f)
            cubicTo(20f, 67f, 32f, 72f, 45f, 69f)
            lineTo(50f, 83f)
            lineTo(55f, 69f)
            cubicTo(68f, 72f, 80f, 67f, 93f, 51f)
            lineTo(87f, 45f)
            cubicTo(76f, 50f, 66f, 56f, 56f, 60f)
            lineTo(56f, 49f)
            cubicTo(61f, 41f, 65f, 31f, 71f, 30f)
            cubicTo(76f, 30f, 80f, 34f, 85f, 37f)
            lineTo(98f, 35f)
            lineTo(92f, 31f)
            lineTo(97f, 20f)
            lineTo(89f, 24f)
            cubicTo(85f, 19f, 80f, 17f, 75f, 18f)
            cubicTo(66f, 20f, 61f, 29f, 53f, 39f)
            lineTo(50f, 43f)
            close()
        }
        val crown = Path().apply {
            moveTo(17f, 13f); lineTo(13f, 5f); lineTo(21f, 9f); lineTo(25f, 2f)
            lineTo(29f, 10f); lineTo(34f, 6f); lineTo(31f, 16f); close()
            moveTo(83f, 13f); lineTo(87f, 5f); lineTo(79f, 9f); lineTo(75f, 2f)
            lineTo(71f, 10f); lineTo(66f, 6f); lineTo(69f, 16f); close()
        }
        val legs = Path().apply {
            moveTo(43f, 72f); lineTo(39f, 88f); lineTo(31f, 92f); lineTo(33f, 96f)
            lineTo(46f, 93f); lineTo(50f, 79f); lineTo(54f, 79f); lineTo(58f, 93f)
            lineTo(71f, 96f); lineTo(73f, 92f); lineTo(65f, 88f); lineTo(57f, 72f); close()
        }
        drawContext.transform.scale(sx, sy, pivot = androidx.compose.ui.geometry.Offset.Zero)
        drawPath(eagle, tint)
        drawPath(crown, tint)
        drawPath(legs, tint)
        drawLine(tint, androidx.compose.ui.geometry.Offset(46f, 48f), androidx.compose.ui.geometry.Offset(54f, 48f), strokeWidth = 5f)
    }
}

@Composable
private fun AssetTypeIcon(
    assetType: String,
    ticker: String,
    logoCatalog: Map<String, String>
) {
    val type = assetType.lowercase()
    val normalizedTicker = ticker.trim().uppercase()
    val isBond = type.contains("облигац") || type.contains("офз") || normalizedTicker.startsWith("SU")
    val isFund = type.contains("фонд") || type.contains("бпиф") || type.contains("etf")
    val domain = if (isBond) null else AssetLogoCatalog.domainFor(normalizedTicker, logoCatalog)
    val blue = androidx.compose.ui.graphics.Color(0xFF173F78)
    val fallbackTint = if (isFund) androidx.compose.ui.graphics.Color(0xFF67469A)
        else androidx.compose.ui.graphics.Color(0xFF176B55)

    Surface(
        shape = CircleShape,
        color = if (isBond) blue else androidx.compose.ui.graphics.Color(0xFFF0F2F5),
        modifier = Modifier.size(46.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isBond) {
                DoubleHeadedEagleIcon(androidx.compose.ui.graphics.Color.White)
            } else {
                // The monogram remains visible if the network image is unavailable.
                Text(
                    normalizedTicker.take(2).ifBlank { "?" },
                    color = fallbackTint,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                if (domain != null) {
                    AsyncImage(
                        model = AssetLogoCatalog.faviconUrl(domain),
                        contentDescription = "Логотип $ticker",
                        modifier = Modifier.fillMaxSize().padding(5.dp).clip(CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetPurchaseHistoryDialog(
    asset: AssetEntity,
    operations: List<OperationEntity>,
    accounts: List<BrokerAccountEntity>,
    onDismiss: () -> Unit
) {
    val purchases = remember(asset.id, asset.ticker, operations) {
        operations.filter { operation ->
            operation.operationType == "Покупка" &&
                operation.quantity > 0.0 &&
                (operation.assetId == asset.id ||
                    operation.ticker?.equals(asset.ticker, ignoreCase = true) == true)
        }.sortedBy { it.operationDate }
    }
    val purchasedQuantity = purchases.sumOf { it.quantity }
    val totalCost = purchases.sumOf { it.quantity * it.price + it.commission }
    val assetTradeOperations = remember(asset.id, asset.ticker, operations) {
        operations.filter { operation ->
            operation.assetId == asset.id ||
                operation.ticker?.equals(asset.ticker, ignoreCase = true) == true
        }
    }
    val lotBalances = remember(asset.id, assetTradeOperations) {
        calculateRemainingPurchaseLots(assetTradeOperations)
    }
    val remainingQuantity = purchases.sumOf { purchase ->
        lotBalances.remainingByOperationId[purchase.id] ?: 0.0
    }
    val sales = assetTradeOperations.filter { it.operationType == "Продажа" }
        .sortedByDescending { it.operationDate }
    val saleResults = remember(asset.id, assetTradeOperations) {
        calculateSaleLotResults(assetTradeOperations).associateBy { it.operationId }
    }
    val knownRealizedProfit = sales.sumOf { saleResults[it.id]?.profit ?: 0.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("История покупок · ${asset.ticker}")
                Text(asset.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (purchases.isEmpty()) {
                    Text("Покупки этого актива не найдены. История появится после ручного добавления сделок или импорта брокерского отчёта.")
                } else {
                    Text("Покупок: ${purchases.size} · Куплено всего: ${quantity(purchasedQuantity)} шт.")
                    Text("Осталось по FIFO: ${quantity(remainingQuantity)} шт.", fontWeight = FontWeight.SemiBold)
                    Text("Сумма покупок с комиссиями: ${money(totalCost)} ₽", fontWeight = FontWeight.SemiBold)
                    HorizontalDivider()
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(purchases, key = { it.id }) { purchase ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru", "RU"))
                                        .format(java.util.Date(purchase.operationDate)),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text("${quantity(purchase.quantity)} шт. × ${money(purchase.price)} ₽")
                                val accountId = historyAccountId(purchase.sourceKey)
                                val accountLabel = accounts.firstOrNull { it.accountId == accountId }?.displayName
                                    ?: if (accountId == null) "Счёт не указан" else "Счёт не найден в списке"
                                Text(
                                    accountLabel,
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                val total = purchase.quantity * purchase.price + purchase.commission
                                val remainingFromPurchase = lotBalances.remainingByOperationId[purchase.id] ?: 0.0
                                Text(
                                    "Осталось по этой покупке: ${quantity(remainingFromPurchase)} шт.",
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (remainingFromPurchase > 0.0000001) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Комиссия: ${money(purchase.commission)} ₽ · Итого: ${money(total)} ₽",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                    Text(
                        "Остаток рассчитан по FIFO только на основании зарегистрированных покупок и продаж в каждом брокерском счёте. При неполной истории или начальных остатках расчёт может отличаться от фактической позиции.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (lotBalances.unmatchedSaleQuantity > 0.0000001) {
                        Text(
                            "Есть продажи без соответствующих покупок в истории: ${quantity(lotBalances.unmatchedSaleQuantity)} шт. Учёт лотов неполный.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                HorizontalDivider()
                Text("История продаж", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                if (sales.isEmpty()) {
                    Text("Продаж этого актива пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        "Продаж: ${sales.size} · Известный реализованный результат: ${if (knownRealizedProfit > 0.0) "+" else ""}${money(knownRealizedProfit)} ₽",
                        fontWeight = FontWeight.SemiBold,
                        color = if (knownRealizedProfit >= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sales, key = { it.id }) { sale ->
                            val result = saleResults[sale.id]
                            val accountId = historyAccountId(sale.sourceKey)
                            val accountLabel = accounts.firstOrNull { it.accountId == accountId }?.displayName
                                ?: if (accountId == null) "Счёт не указан" else "Счёт не найден в списке"
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru", "RU"))
                                        .format(java.util.Date(sale.operationDate)),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text("${quantity(sale.quantity)} шт. × ${money(sale.price)} ₽")
                                Text(accountLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                if (result != null && result.matchedQuantity > 0.0000001) {
                                    Text(
                                        "Сопоставлено: ${quantity(result.matchedQuantity)} шт. · Себестоимость: ${money(result.costBasis)} ₽",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "Выручка после комиссии: ${money(result.netProceeds)} ₽",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "Результат: ${if (result.profit > 0.0) "+" else ""}${money(result.profit)} ₽",
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (result.profit >= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                }
                                if (result != null && result.unmatchedQuantity > 0.0000001) {
                                    Text(
                                        "Без подтверждённой себестоимости: ${quantity(result.unmatchedQuantity)} шт. Прибыль для этой части не рассчитывается.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Text(
                                    "Комиссия продажи: ${money(sale.commission)} ₽",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

private const val ALL_HISTORY_ACCOUNTS = "ALL"
private const val UNASSIGNED_HISTORY_ACCOUNT = "UNASSIGNED"

private fun historyAccountId(sourceKey: String?): String? {
    if (sourceKey.isNullOrBlank() || !sourceKey.startsWith("SBER:")) return null
    val parts = sourceKey.split(':', limit = 3)
    if (parts.size < 3 || parts[1] == "CASH") return null
    return parts[1].takeIf { it.isNotBlank() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountHistoryFilter(
    accounts: List<ru.mycapital.app.data.BrokerAccountEntity>,
    selectedAccountId: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = when (selectedAccountId) {
        ALL_HISTORY_ACCOUNTS -> "Все счета"
        UNASSIGNED_HISTORY_ACCOUNT -> "Без привязки к счёту"
        else -> accounts.firstOrNull { it.accountId == selectedAccountId }?.displayName ?: "Все счета"
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Брокерский счёт") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Все счета") },
                onClick = { onSelect(ALL_HISTORY_ACCOUNTS); expanded = false }
            )
            DropdownMenuItem(
                text = { Text("Без привязки к счёту") },
                onClick = { onSelect(UNASSIGNED_HISTORY_ACCOUNT); expanded = false }
            )
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.displayName) },
                    onClick = { onSelect(account.accountId); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun OperationsScreen(
    operations: List<OperationEntity>,
    accounts: List<ru.mycapital.app.data.BrokerAccountEntity>,
    selectedAccountId: String,
    onAccountSelected: (String) -> Unit,
    onAddCashFlow: () -> Unit,
    modifier: Modifier = Modifier
) {
    var operationTypeFilter by remember { mutableStateOf("Все") }
    var searchQuery by remember { mutableStateOf("") }
    val visibleOperations = operations.filter { operation ->
        val matchesAccount = when (selectedAccountId) {
            ALL_HISTORY_ACCOUNTS -> true
            UNASSIGNED_HISTORY_ACCOUNT -> historyAccountId(operation.sourceKey) == null
            else -> historyAccountId(operation.sourceKey) == selectedAccountId
        }
        val matchesType = when (operationTypeFilter) {
            "Сделки" -> operation.operationType == "Покупка" || operation.operationType == "Продажа"
            "Деньги" -> operation.operationType == "Пополнение" || operation.operationType == "Вывод"
            "Выплаты" -> operation.operationType == "Дивиденд" || operation.operationType == "Купон"
            else -> true
        }
        val query = searchQuery.trim()
        val matchesQuery = query.isBlank() ||
            operation.ticker.orEmpty().contains(query, ignoreCase = true) ||
            operation.operationType.contains(query, ignoreCase = true) ||
            operation.note.contains(query, ignoreCase = true)
        matchesAccount && matchesType && matchesQuery
    }
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Text("Операции", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("История сделок и движения денег", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        AccountHistoryFilter(accounts, selectedAccountId, onAccountSelected)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Поиск по тикеру, типу или примечанию") },
            singleLine = true,
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Очистить поиск")
                    }
                }
            }
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Все", "Сделки").forEach { filter ->
                FilterChip(
                    selected = operationTypeFilter == filter,
                    onClick = { operationTypeFilter = filter },
                    label = { Text(filter) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Деньги", "Выплаты").forEach { filter ->
                FilterChip(
                    selected = operationTypeFilter == filter,
                    onClick = { operationTypeFilter = filter },
                    label = { Text(filter) }
                )
            }
        }
        TextButton(onClick = onAddCashFlow) { Text("Пополнить / вывести деньги") }
        Text(
            "Найдено операций: ${visibleOperations.size}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(8.dp))
        if (visibleOperations.isEmpty()) {
            Text(
                if (operations.isEmpty()) "Пока нет операций. Нажмите +, чтобы добавить сделку."
                else "По выбранным фильтрам операций нет.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleOperations, key = { it.id }) { op ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(op.operationType + " · " + (op.ticker ?: "—"), fontWeight = FontWeight.SemiBold)
                            Text(op.quantity.toString() + " × " + money(op.price) + " ₽", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru", "RU")).format(java.util.Date(op.operationDate)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall
                            )
                            if (op.commission > 0.0) {
                                Text("Комиссия: ${money(op.commission)} ₽", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        val amount = op.quantity * op.price
                        val displayed = when (op.operationType) {
                            "Покупка" -> amount + op.commission
                            "Продажа" -> amount - op.commission
                            "Пополнение", "Вывод" -> amount
                            else -> amount
                        }
                        Text(money(displayed) + " ₽", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


@Composable
private fun PayoutsScreen(
    payouts: List<PayoutEntity>,
    accounts: List<ru.mycapital.app.data.BrokerAccountEntity>,
    selectedAccountId: String,
    onAccountSelected: (String) -> Unit,
    onConfirmReceipt: (PayoutEntity) -> Unit,
    onRefreshEvents: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visiblePayouts = payouts.filter { payout ->
        when (selectedAccountId) {
            ALL_HISTORY_ACCOUNTS -> true
            UNASSIGNED_HISTORY_ACCOUNT -> historyAccountId(payout.sourceKey) == null
            else -> historyAccountId(payout.sourceKey) == selectedAccountId
        }
    }
    val received = visiblePayouts.filter { it.status == "RECEIVED" }.sumOf {
        it.reportedNetAmount ?: (it.totalAmount - it.taxAmount)
    }
    val expected = visiblePayouts.filter { it.status == "EXPECTED" }.sumOf { it.totalAmount }
    // Keep the history compact: hide only expected payments dated more than 90 days ahead.
    // Received payments and overdue/undated expected payments remain visible.
    val historyCutoff = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(90)
    val historyPayouts = visiblePayouts.filter { payout ->
        payout.status != "EXPECTED" || payout.expectedDate == null ||
            !java.time.Instant.ofEpochMilli(payout.expectedDate)
                .atZone(java.time.ZoneOffset.UTC).toLocalDate().isAfter(historyCutoff)
    }

    // One page-level scroll: header, forecast, and payout history move together.
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Выплаты", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Дивиденды и купоны: ожидаемые отдельно от полученных", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRefreshEvents) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить календарь выплат")
            }
        }
        AccountHistoryFilter(accounts, selectedAccountId, onAccountSelected)
        Card(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                Text("Всего получено после налога", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(received) + " ₽", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            }
        }
        Card(shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ожидается", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(money(expected) + " ₽", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Text("До налога", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        PayoutForecastCard(visiblePayouts)
        if (historyPayouts.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(if (selectedAccountId == ALL_HISTORY_ACCOUNTS) "Пока нет выплат в ближайшие 90 дней. Более поздние ожидаемые выплаты временно скрыты." else "Для выбранного счёта нет выплат в ближайшие 90 дней.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text("История выплат", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            historyPayouts.forEach { payout ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(payout.ticker + " · " + payout.payoutType, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(if (payout.status == "RECEIVED") "Получено" else "Ожидается",
                                color = if (payout.status == "RECEIVED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (payout.reportedNetAmount != null) {
                            Text("Зачислено по брокерскому отчёту: ${money(payout.reportedNetAmount)} ₽")
                            Text("Сумма до налога и удержанный налог в отчёте отдельно не раскрыты",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("Начислено: ${money(payout.totalAmount)} ₽ · Налог: ${money(payout.taxAmount)} ₽")
                            Text(if (payout.status == "RECEIVED") "На счёт: ${money(payout.totalAmount - payout.taxAmount)} ₽" else "Ожидаемая сумма, ещё не включена в свободные деньги")
                        }
                        payout.expectedDate?.let {
                            Text("Дата выплаты: " + java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru", "RU")).format(java.util.Date(it)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        payout.actualDate?.let {
                            Text("Получено: " + java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru", "RU")).format(java.util.Date(it)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (payout.status == "EXPECTED") {
                            TextButton(onClick = { onConfirmReceipt(payout) }) {
                                Text("Подтвердить получение")
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(72.dp))
    }
}

@Composable
private fun PayoutForecastCard(payouts: List<PayoutEntity>) {
    val forecast = remember(payouts) { calculatePayoutForecast(payouts) }
    var shownMonth by remember { mutableStateOf(YearMonth.now()) }
    val monthForecast = forecast.monthly.firstOrNull { it.month == shownMonth }
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Календарь ожидаемых выплат", fontWeight = FontWeight.SemiBold)
            Text(
                "Прогноз по известным датам, суммы до налога",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("30 дней", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${money(forecast.next30Days)} ₽", fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f)) {
                    Text("90 дней", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${money(forecast.next90Days)} ₽", fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { shownMonth = shownMonth.minusMonths(1) }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Предыдущий месяц")
                }
                Text(
                    shownMonth.format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", java.util.Locale("ru", "RU")))
                        .replaceFirstChar { it.titlecase(java.util.Locale("ru", "RU")) },
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = { shownMonth = shownMonth.plusMonths(1) }) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Следующий месяц")
                }
            }
            if (monthForecast == null) {
                Text("На этот месяц ожидаемых выплат с известной датой нет.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${monthForecast.paymentCount} ${if (monthForecast.paymentCount == 1) "выплата" else if (monthForecast.paymentCount in 2..4) "выплаты" else "выплат"}")
                    Text("${money(monthForecast.amount)} ₽", fontWeight = FontWeight.SemiBold)
                }
            }
            if (forecast.monthly.isEmpty()) {
                Text(
                    "Пока нет ожидаемых выплат с будущей датой. Обновите корпоративные события или добавьте выплату вручную.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReceivePayoutDialog(
    payout: PayoutEntity,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var taxText by remember(payout.id) { mutableStateOf(money(payout.taxAmount)) }
    val tax = parseDecimalInput(taxText) ?: -1.0
    val valid = tax.isFinite() && tax >= 0.0 && tax <= payout.totalAmount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подтвердить получение") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${payout.ticker} · ${payout.payoutType}")
                Text("Начислено: ${money(payout.totalAmount)} ₽")
                OutlinedTextField(
                    value = taxText,
                    onValueChange = { taxText = it },
                    label = { Text("Фактически удержанный налог, ₽") },
                    singleLine = true
                )
                if (valid) Text("Будет зачислено: ${money(payout.totalAmount - tax)} ₽")
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(tax) }) { Text("Подтвердить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddPayoutDialog(
    assets: List<AssetEntity>,
    onDismiss: () -> Unit,
    onSave: (AssetEntity?, String, Double, Double, Double, Boolean, Long?) -> Unit
) {
    var assetId by remember { mutableStateOf(assets.firstOrNull()?.id ?: 0L) }
    var payoutType by remember { mutableStateOf("Дивиденд") }
    var amountText by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("1") }
    var taxText by remember { mutableStateOf("0") }
    var expectedDateText by remember { mutableStateOf("") }
    var received by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val asset = assets.firstOrNull { it.id == assetId }
    val amount = parseDecimalInput(amountText) ?: 0.0
    val quantity = parseDecimalInput(quantityText) ?: 0.0
    val tax = parseDecimalInput(taxText) ?: -1.0
    val gross = amount * quantity
    val parsedExpectedDate = runCatching { java.time.LocalDate.parse(expectedDateText, java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu")) }.getOrNull()
    val expectedDate = parsedExpectedDate?.atStartOfDay(java.time.ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    val valid = asset != null && amount > 0.0 && quantity > 0.0 && tax >= 0.0 && tax <= gross && (received || expectedDate != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить выплату") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(value = asset?.ticker ?: "Нет активов", onValueChange = {}, readOnly = true,
                        label = { Text("Бумага") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        assets.forEach { item ->
                            DropdownMenuItem(text = { Text(item.ticker) }, onClick = { assetId = item.id; expanded = false })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = payoutType == "Дивиденд", onClick = { payoutType = "Дивиденд" }, label = { Text("Дивиденд") })
                    FilterChip(selected = payoutType == "Купон", onClick = { payoutType = "Купон" }, label = { Text("Купон") })
                }
                OutlinedTextField(amountText, { amountText = it }, label = { Text("Сумма на акцию / облигацию, ₽") }, singleLine = true)
                OutlinedTextField(quantityText, { quantityText = it }, label = { Text("Количество бумаг") }, singleLine = true)
                OutlinedTextField(taxText, { taxText = it }, label = { Text("Налог, ₽") }, singleLine = true)
                if (!received) {
                    OutlinedTextField(expectedDateText, { expectedDateText = it }, label = { Text("Дата выплаты, ДД.ММ.ГГГГ") }, placeholder = { Text("20.01.2027") }, singleLine = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = received, onCheckedChange = { received = it })
                    Text("Уже фактически получено")
                }
                Text("Начислено: ${money(gross)} ₽ · К получению: ${money((gross - tax).coerceAtLeast(0.0))} ₽",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(asset, payoutType, amount, quantity, tax, received, expectedDate) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun CashFlowDialog(onDismiss: () -> Unit, onSave: (Boolean, Double) -> Unit) {
    var isDeposit by remember { mutableStateOf(true) }
    var amountText by remember { mutableStateOf("") }
    val amount = parseDecimalInput(amountText) ?: 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Движение денег") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = isDeposit, onClick = { isDeposit = true }, label = { Text("Пополнение") })
                    FilterChip(selected = !isDeposit, onClick = { isDeposit = false }, label = { Text("Вывод") })
                }
                OutlinedTextField(amountText, { amountText = it }, label = { Text("Сумма, ₽") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = amount > 0.0, onClick = { onSave(isDeposit, amount) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddOperationDialog(assets: List<AssetEntity>, onDismiss: () -> Unit, onSave: (AssetEntity, Boolean, Double, Double, Double) -> Unit) {
    var assetId by remember { mutableStateOf(assets.firstOrNull()?.id ?: 0L) }
    var isBuy by remember { mutableStateOf(true) }
    var qtyText by remember { mutableStateOf("1") }
    var priceText by remember { mutableStateOf("") }
    var feeText by remember { mutableStateOf("0") }
    val asset = assets.firstOrNull { it.id == assetId }
    val qty = parseDecimalInput(qtyText) ?: 0.0
    val price = parseDecimalInput(priceText) ?: -1.0
    val fee = parseDecimalInput(feeText) ?: -1.0
    val valid = asset != null && qty > 0 && price >= 0 && fee >= 0 && (isBuy || qty <= asset.quantity)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая сделка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(value = asset?.ticker ?: "Нет активов", onValueChange = {}, readOnly = true, label = { Text("Бумага") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        assets.forEach { item -> DropdownMenuItem(text = { Text(item.ticker) }, onClick = { assetId = item.id; expanded = false }) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = isBuy, onClick = { isBuy = true }, label = { Text("Покупка") })
                    FilterChip(selected = !isBuy, onClick = { isBuy = false }, label = { Text("Продажа") })
                }
                OutlinedTextField(qtyText, { qtyText = it }, label = { Text("Количество") }, singleLine = true)
                OutlinedTextField(priceText, { priceText = it }, label = { Text("Цена за единицу") }, singleLine = true)
                OutlinedTextField(feeText, { feeText = it }, label = { Text("Комиссия") }, singleLine = true)
                if (!isBuy && asset != null) Text("Доступно: " + asset.quantity)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { asset?.let { onSave(it, isBuy, qty, price, fee) } }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
@Composable
private fun EditAssetDialog(
    asset: AssetEntity,
    onDismiss: () -> Unit,
    onSave: (AssetEntity) -> Unit
) {
    var name by remember(asset.id) { mutableStateOf(asset.name) }
    var type by remember(asset.id) { mutableStateOf(asset.assetType) }
    var quantityText by remember(asset.id) { mutableStateOf(quantity(asset.quantity)) }
    var averageText by remember(asset.id) { mutableStateOf(asset.averagePrice.toString()) }
    var currentText by remember(asset.id) { mutableStateOf(asset.currentPrice.toString()) }

    val parsedQuantity = parseDecimalInput(quantityText)
    val parsedAverage = parseDecimalInput(averageText)
    val parsedCurrent = parseDecimalInput(currentText)
    val valid = name.isNotBlank() && type.isNotBlank() &&
        parsedQuantity != null && parsedQuantity > 0.0 &&
        parsedAverage != null && parsedAverage >= 0.0 &&
        parsedCurrent != null && parsedCurrent >= 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить актив · ${asset.ticker}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Тикер и история операций не меняются, чтобы не нарушить связь с прошлыми сделками.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(type, { type = it }, label = { Text("Тип актива") }, singleLine = true)
                OutlinedTextField(quantityText, { quantityText = it }, label = { Text("Количество") }, singleLine = true)
                OutlinedTextField(averageText, { averageText = it }, label = { Text("Средняя цена") }, singleLine = true)
                OutlinedTextField(currentText, { currentText = it }, label = { Text("Текущая цена") }, singleLine = true)
                Text("Изменение количества вручную корректирует только общую позицию. История сделок и распределение по брокерским счетам не переписываются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(asset.copy(
                    name = name.trim(),
                    assetType = type.trim(),
                    quantity = parsedQuantity!!,
                    averagePrice = parsedAverage!!,
                    currentPrice = parsedCurrent!!
                ))
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun AddAssetDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, Double, Double, Double) -> Unit
) {
    var ticker by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("Акция") }
    var quantity by remember { mutableStateOf("1") }
    var average by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    val valid = ticker.isNotBlank() && name.isNotBlank() &&
        (parseDecimalInput(quantity) ?: 0.0) > 0 &&
        (parseDecimalInput(average) ?: -1.0) >= 0 &&
        (parseDecimalInput(current) ?: -1.0) >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый актив") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ticker, { ticker = it }, label = { Text("Тикер, например SBER") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(type, { type = it }, label = { Text("Тип: акция, облигация, фонд…") }, singleLine = true)
                OutlinedTextField(quantity, { quantity = it }, label = { Text("Количество") }, singleLine = true)
                OutlinedTextField(average, { average = it }, label = { Text("Средняя цена, ₽") }, singleLine = true)
                OutlinedTextField(current, { current = it }, label = { Text("Текущая цена, ₽") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(ticker, name, type, quantity.toDouble(), average.toDouble(), current.toDouble())
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        }
    }
}

@Composable
private fun AnalyticsScreen(
    assets: List<AssetEntity>,
    marketValue: Double,
    cashBalance: Double,
    operations: List<OperationEntity>,
    payouts: List<PayoutEntity>,
    snapshots: List<PortfolioSnapshotEntity>,
    dao: ru.mycapital.app.data.PortfolioDao,
    modifier: Modifier = Modifier
) {
    val invested = assets.sumOf { it.quantity * it.averagePrice }
    val unrealized = marketValue - invested
    val realizedResult = calculateRealizedTradingResult(operations)
    val receivedPayouts = payouts.filter { it.status == "RECEIVED" }
    val receivedDividends = receivedPayouts.filter { it.payoutType == "Дивиденд" }.sumOf { it.reportedNetAmount ?: (it.totalAmount - it.taxAmount) }
    val receivedCoupons = receivedPayouts.filter { it.payoutType == "Купон" }.sumOf { it.reportedNetAmount ?: (it.totalAmount - it.taxAmount) }
    val payoutIncome = receivedDividends + receivedCoupons
    val totalInvestmentResult = unrealized + realizedResult.profit + payoutIncome
    val totalValue = marketValue + cashBalance
    val returnPercent = if (invested > 0.0) unrealized / invested * 100.0 else 0.0
    val groups = assets.groupBy { it.assetType.ifBlank { "Прочее" } }
        .mapValues { (_, items) -> items.sumOf { it.quantity * it.currentPrice } }
        .toList()
        .sortedByDescending { it.second }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Аналитика", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Структура и текущий результат портфеля", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            val todayMillis = System.currentTimeMillis()
            val horizonMillis = todayMillis + TimeUnit.DAYS.toMillis(90)
            val upcoming = payouts.filter {
                it.status == "EXPECTED" && it.expectedDate != null &&
                    it.expectedDate >= todayMillis && it.expectedDate <= horizonMillis
            }.sortedBy { it.expectedDate }
            Text("Ближайшие выплаты", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (upcoming.isEmpty()) {
                Text("В ближайшие 90 дней ожидаемых выплат с известной датой нет.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                upcoming.take(8).forEach { payout ->
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val date = java.time.Instant.ofEpochMilli(payout.expectedDate!!)
                                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                            Column(Modifier.weight(1f)) {
                                Text(assets.firstOrNull { it.ticker.equals(payout.ticker, true) }?.name ?: payout.ticker,
                                    fontWeight = FontWeight.SemiBold)
                                Text("${date.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale("ru", "RU")))} · ${payout.payoutType}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("≈ ${money(payout.totalAmount)} ₽", fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Стоимость капитала", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${money(totalValue)} ₽", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Рыночная стоимость активов")
                        Text("${money(marketValue)} ₽", fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Свободные деньги")
                        Text("${money(cashBalance)} ₽", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
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
            Card(shape = RoundedCornerShape(18.dp)) {
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
            Card(shape = RoundedCornerShape(18.dp)) {
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
                    HorizontalDivider()
                    Text("Общий результат с учётом продаж и выплат", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        (if (totalInvestmentResult > 0) "+" else "") + "${money(totalInvestmentResult)} ₽",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (totalInvestmentResult >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text("Сумма нереализованного результата по текущим остаткам, реализованного результата по FIFO и полученных выплат после налога. Если история покупок неполная, итог по сделкам также неполный. Не включает налоги и расходы, не отражённые в операциях.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("Динамика капитала", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (snapshots.size >= 2) {
                PortfolioHistoryChart(snapshots)
                Text("График показывает условную доходность (база 100) с исключением пополнений и выводов. Денежные потоки учитываются на конец дневного интервала; для точного TWR потребуется точное время каждого потока.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("История только начинается. Приложение сохраняет ежедневную стоимость капитала; график появится после второго дневного снимка.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            IndexBenchmarkSection(snapshots, dao)
        }
        item {
            Text("Распределение активов", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        if (totalValue <= 0.0) {
            item { Text("Добавьте активы или внесите свободные деньги, чтобы увидеть структуру портфеля.") }
        } else {
            items(groups) { (type, value) ->
                val share = (value / totalValue).coerceIn(0.0, 1.0)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(type, fontWeight = FontWeight.Medium)
                        Text("${money(value)} ₽ · ${String.format(java.util.Locale("ru", "RU"), "%.1f%%", share * 100)}")
                    }
                    LinearProgressIndicator(
                        progress = { share.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                val share = (cashBalance / totalValue).coerceIn(0.0, 1.0)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Денежные средства", fontWeight = FontWeight.Medium)
                        Text("${money(cashBalance)} ₽ · ${String.format(java.util.Locale("ru", "RU"), "%.1f%%", share * 100)}")
                    }
                    LinearProgressIndicator(progress = { share.toFloat() }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            Text(
                "Доходность рассчитана методом условной стоимости единицы с поправкой на внешние денежные потоки. Это дневное приближение; сравнение с IMOEX и MCFTR будет добавлено отдельно.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


@Composable
private fun PortfolioHistoryChart(snapshots: List<PortfolioSnapshotEntity>) {
    var selectedRange by remember { mutableStateOf(PortfolioHistoryRange.ALL) }
    var showReturn by remember { mutableStateOf(false) }
    val points = remember(snapshots, selectedRange) {
        calculatePortfolioHistoryPoints(snapshots, selectedRange)
    }
    val plottedValues = points.map { if (showReturn) it.nav else it.totalValue }.map { it.toFloat() }
    val minValue = plottedValues.minOrNull() ?: 0f
    val maxValue = plottedValues.maxOrNull() ?: 0f
    val range = (maxValue - minValue).takeIf { it > 0.0001f } ?: 1f
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PortfolioHistoryRange.entries.forEach { period ->
                FilterChip(
                    selected = selectedRange == period,
                    onClick = { selectedRange = period },
                    label = { Text(period.label) }
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !showReturn,
                onClick = { showReturn = false },
                label = { Text("Стоимость ₽") }
            )
            FilterChip(
                selected = showReturn,
                onClick = { showReturn = true },
                label = { Text("Доходность %") }
            )
        }
        if (points.size < 2) {
            Text(
                "За выбранный период пока недостаточно дневных снимков. Выберите более длинный период.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val first = points.first()
            val last = points.last()
            if (showReturn) {
                val totalReturn = last.nav - 100.0
                Text(
                    "Доходность за период: ${if (totalReturn >= 0) "+" else ""}${String.format(java.util.Locale("ru", "RU"), "%.2f%%", totalReturn)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (totalReturn >= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    "Индекс доходности: 100 → ${String.format(java.util.Locale("ru", "RU"), "%.2f", last.nav)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Стоимость: ${money(last.totalValue)} ₽",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Изменение за период: ${if (last.totalValue - first.totalValue >= 0) "+" else ""}${money(last.totalValue - first.totalValue)} ₽",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (showReturn) String.format(java.util.Locale("ru", "RU"), "%.2f", minValue)
                    else money(minValue.toDouble()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (showReturn) String.format(java.util.Locale("ru", "RU"), "%.2f", maxValue)
                    else money(maxValue.toDouble()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                val left = 8f
                val right = size.width - 8f
                val top = 8f
                val bottom = size.height - 8f
                for (i in 0..3) {
                    val y = top + (bottom - top) * i / 3f
                    drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                }
                val path = Path()
                plottedValues.forEachIndexed { index, value ->
                    val x = left + (right - left) * index / (plottedValues.size - 1).toFloat()
                    val y = bottom - ((value - minValue) / range) * (bottom - top)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, lineColor, style = Stroke(width = 4f, cap = StrokeCap.Round))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(first.dateKey, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(last.dateKey, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            if (showReturn)
                "Доходность рассчитана как дневное приближение TWR: пополнения и выводы исключаются из изменения стоимости. Денежные потоки считаются совершёнными в конце дня."
            else
                "Показана фактическая оценка активов и свободных денег на дату дневного снимка. Изменение стоимости включает влияние пополнений и выводов.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
private fun money(value: Double): String = String.format(java.util.Locale("ru", "RU"), "%,.2f", value)

private fun quantity(value: Double): String = String.format(java.util.Locale("ru", "RU"), "%.6f", value).trimEnd('0').trimEnd(',')


@Composable
private fun BrokerReportPreviewDialog(
    report: SberBrokerReport,
    currentAssets: List<AssetEntity>,
    currentCash: Double,
    isImporting: Boolean,
    isReconciling: Boolean,
    onDismiss: () -> Unit,
    onImportAndReconcile: () -> Unit
) {
    val pendingTrades = report.trades.filterNot { isFullyExecutedBrokerTrade(it.status) }
    val completedTrades = report.trades.size - pendingTrades.size
    val currentByTicker = currentAssets.associateBy { it.ticker.uppercase() }
    val knownRows = report.assets.mapNotNull { row ->
        row.ticker.takeIf { it.isNotBlank() }?.let { ticker ->
            currentByTicker[ticker.uppercase()]?.let { asset -> row to asset }
        }
    }
    val unknownRows = report.assets.filter {
        it.closingQuantity > 0.0 && (it.ticker.isBlank() || currentByTicker[it.ticker.uppercase()] == null)
    }
    var showReconcileConfirmation by remember(report.accountId, report.periodEnd) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Предварительный просмотр отчёта") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Период: ${report.periodStart} — ${report.periodEnd}", fontWeight = FontWeight.SemiBold)
                Text("Денежные средства")
                Text("На начало: ${money(report.openingCash)} ₽")
                Text("Пополнения: +${money(report.cashCredits)} ₽")
                Text("Списания: −${money(report.cashDebits)} ₽")
                Text("На конец: ${money(report.closingCash)} ₽")
                HorizontalDivider()
                val knownTickers = report.assets.count { row -> row.ticker.isNotBlank() }
                Text("Позиций в отчёте: ${report.assets.size} (с распознанными тикерами: $knownTickers)")
                Text("Сделок: ${report.trades.size} (исполнено, статусы «И/ЗИ»: $completedTrades)")
                Text("Комиссии по всем сделкам: ${money(report.trades.sumOf { it.totalFee })} ₽")
                if (pendingTrades.isNotEmpty()) {
                    Text("Сделки, не включаемые в историю как исполненные: ${pendingTrades.size}", color = MaterialTheme.colorScheme.error)
                    pendingTrades.take(4).forEach { trade ->
                        Text("${trade.ticker}: ${trade.side}, ${trade.quantity} шт. · статус «${trade.status}»", fontSize = 12.sp)
                    }
                }
                HorizontalDivider()
                Text("Сверка позиций", fontWeight = FontWeight.SemiBold)
                Text("Остатки существующих бумаг будут обновлены, а новые позиции из справочника брокера добавлены в портфель. Затем приложение загрузит сделки, пополнения, дивиденды и купоны из этого же отчёта.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                knownRows.take(20).forEach { (row, current) ->
                    Text("${row.ticker}: по этому договору ${quantity(row.closingQuantity)} шт.; всего в приложении сейчас ${quantity(current!!.quantity)} шт.", fontSize = 12.sp)
                }
                if (knownRows.size > 20) Text("И ещё ${knownRows.size - 20} сопоставленных позиций…", fontSize = 12.sp)
                if (unknownRows.isNotEmpty()) {
                    Text("Позиции, которые не удалось распознать по тикеру: ${unknownRows.size}.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    unknownRows.take(5).forEach { Text("• ${it.ticker.ifBlank { it.name }}", fontSize = 12.sp) }
                }
                HorizontalDivider()
                Text("Общий остаток сейчас: ${money(currentCash)} ₽. По договору ${report.accountId}: ${money(report.closingCash)} ₽. После сверки общий остаток будет пересчитан с учётом ранее сверенных договоров.", fontWeight = FontWeight.SemiBold)
                val cashCheck = report.openingCash + report.cashCredits - report.cashDebits
                Text("Контроль отчёта: начальный остаток + поступления − списания = ${money(cashCheck)} ₽. Конечный остаток: ${money(report.closingCash)} ₽.")
                if (kotlin.math.abs(cashCheck - report.closingCash) > 0.02) {
                    Text("Внимание: денежный баланс отчёта не сходится. Сверка заблокирована.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Text(
                    "История операций и остатки обрабатываются одной командой. Сначала сверяется снимок договора, затем импортируются сделки и выплаты; повторные строки пропускаются как дубликаты.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { showReconcileConfirmation = true },
                enabled = !isImporting && !isReconciling &&
                    report.closingCash >= 0.0 &&
                    report.assets.any { it.ticker.isNotBlank() } &&
                    kotlin.math.abs(report.openingCash + report.cashCredits - report.cashDebits - report.closingCash) <= 0.02
            ) {
                Text(if (isImporting || isReconciling) "Обрабатываю отчёт…" else "Импортировать и сверить портфель")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isImporting && !isReconciling) { Text("Отмена") }
        }
    )
    if (showReconcileConfirmation) {
        BrokerReportReconciliationConfirmation(
            report = report,
            onConfirm = {
                showReconcileConfirmation = false
                onImportAndReconcile()
            },
            onDismiss = { showReconcileConfirmation = false }
        )
    }
}

@Composable
private fun BrokerReportReconciliationConfirmation(
    report: SberBrokerReport,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подтвердить сверку портфеля?") },
        text = {
            Text(
                "Приложение обновит остатки договора ${report.accountId}, пересчитает общий портфель с учётом остальных договоров и импортирует историю операций и выплат из этого же отчёта. Повторно загруженные операции не будут продублированы. Сделки, которые не отмечены как полностью исполненные, в историю покупок и продаж не попадут."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Подтвердить сверку") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Назад") } }
    )
}
