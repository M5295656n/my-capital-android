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
import ru.mycapital.app.data.PortfolioSnapshotWorker
import java.util.concurrent.TimeUnit
import java.time.YearMonth
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.MoexSecurityHistoryClient
import ru.mycapital.app.data.MoexDailyClose
import ru.mycapital.app.calculatePortfolioAllocation
import ru.mycapital.app.data.PortfolioDatabase
import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.PayoutEntity
import ru.mycapital.app.data.isIncomePayoutType
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
    var selectedAssetDetail by remember { mutableStateOf<AssetEntity?>(null) }
    BackHandler(enabled = selectedAssetDetail != null) { selectedAssetDetail = null }
    var moreDestination by remember { mutableStateOf(Screen.More) }
    var showAddAsset by remember { mutableStateOf(false) }
    var showAddPayout by remember { mutableStateOf(false) }
    var assetToEdit by remember { mutableStateOf<AssetEntity?>(null) }
    var assetToDelete by remember { mutableStateOf<AssetEntity?>(null) }
    var showAddOperation by remember { mutableStateOf(false) }
    var showCashFlow by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val operations by dao.observeOperations().collectAsState(initial = emptyList())
    val payouts by dao.observePayouts().collectAsState(initial = emptyList())
    // Portfolio history lives above navigation destinations so tab switches
    // cannot cancel its network requests or reset the chart state.
    var selectedHistoryRange by remember { mutableStateOf(PortfolioHistoryRange.ALL) }
    var historyResult by remember { mutableStateOf(PortfolioMarketHistoryResult(emptyList(), null, emptyList())) }
    var historyLoading by remember { mutableStateOf(false) }
    var historyRefresh by remember { mutableIntStateOf(0) }
    var historyLoadError by remember { mutableStateOf<String?>(null) }
    val historyAssetsKey = remember(assets) {
        assets.map { Triple(it.ticker.uppercase(), it.quantity, it.assetType) }
    }
    LaunchedEffect(historyAssetsKey, operations, cashBalance, selectedHistoryRange, historyRefresh) {
        historyLoading = true
        historyLoadError = null
        var latestSuccessful: PortfolioMarketHistoryResult? = null
        var lastError: Throwable? = null
        for (attempt in 0..2) {
            try {
                val loaded = kotlinx.coroutines.withTimeout(90_000L) {
                    withContext(Dispatchers.IO) {
                        buildPortfolioMarketHistory(assets, operations, cashBalance ?: 0.0, selectedHistoryRange)
                    }
                }
                latestSuccessful = loaded
                if (loaded.points.isNotEmpty() && loaded.unavailableTickers.isEmpty()) break
                if (attempt < 2) kotlinx.coroutines.delay(1_000L * (attempt + 1))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                if (attempt < 2) kotlinx.coroutines.delay(1_000L * (attempt + 1))
            }
        }
        val loaded = latestSuccessful
        if (loaded != null && loaded.points.isNotEmpty()) {
            historyResult = loaded
            historyLoadError = if (loaded.unavailableTickers.isNotEmpty()) {
                "Нет истории для: ${loaded.unavailableTickers.joinToString()}"
            } else null
        } else if (historyResult.points.isEmpty()) {
            historyLoadError = lastError?.message
                ?: "Исторические котировки пока не получены. Повторите обновление."
        }
        historyLoading = false
    }

    var selectedOperationAccount by remember { mutableStateOf("ALL") }
    var selectedPayoutAccount by remember { mutableStateOf("ALL") }
    val portfolioSnapshots by dao.observePortfolioSnapshots().collectAsState(initial = emptyList())
    val portfolioCompositionSnapshots by dao.observePortfolioCompositionSnapshots().collectAsState(initial = emptyList())
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
    // Keep dashboard data at the app level: OverviewScreen is removed from composition
    // when another tab is opened. A screen-scoped coroutine used to be cancelled on tab
    // changes, leaving quotes and movers intermittently blank until the next recomposition.
    val dashboardHeldTickers = remember(assets) {
        assets.filter { it.quantity > 0.0 }.map { it.ticker.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
    }
    var dashboardMarketData by remember { mutableStateOf(MarketOverviewData()) }
    var dashboardMarketRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(dashboardHeldTickers, dashboardMarketRefresh) {
        val result = runCatching {
            kotlinx.coroutines.withTimeout(35_000L) {
                withContext(Dispatchers.IO) { MarketOverviewClient().load(dashboardHeldTickers) }
            }
        }
        dashboardMarketData = result.getOrElse { error ->
            dashboardMarketData.copy(
                error = error.message ?: "Не удалось обновить рыночные данные"
            )
        }
    }
    val quoteWorkInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow("moex_quote_refresh_manual")
        .collectAsState(initial = emptyList())
    val eventWorkInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow("moex_events_refresh_manual")
        .collectAsState(initial = emptyList())
    // Show activity only while a worker is actually executing. ENQUEUED/BLOCKED can
    // persist while Android waits for network constraints and must not look like a stuck spinner.
    val isRefreshingMarketData = quoteWorkInfos.any { it.state == androidx.work.WorkInfo.State.RUNNING } ||
        eventWorkInfos.any { it.state == androidx.work.WorkInfo.State.RUNNING }
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
        val todayKey = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow")).toString()
        val todaySnapshot = OneTimeWorkRequestBuilder<PortfolioSnapshotWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "portfolio_snapshot_$todayKey", ExistingWorkPolicy.KEEP, todaySnapshot
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
    // Refresh dividend data immediately after the portfolio becomes available or changes.
    // The old periodic-only schedule could leave a freshly installed/imported portfolio empty for 24 hours.
    val eventRefreshSignature = assets
        .filter { it.quantity > 0.0 }
        .map { "${it.ticker}:${it.quantity}:${it.assetType}" }
        .sorted()
    LaunchedEffect(eventRefreshSignature) {
        if (eventRefreshSignature.isNotEmpty()) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<CorporateEventsWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "moex_events_refresh_manual", ExistingWorkPolicy.KEEP, request
            )
        }
    }
    val eventSyncMessage = (
        eventWorkInfos.firstOrNull { it.state == androidx.work.WorkInfo.State.RUNNING }
            ?: eventWorkInfos.firstOrNull { it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.BLOCKED }
            ?: eventWorkInfos.firstOrNull { it.state.isFinished }
        )?.let { info ->
            info.outputData.getString("summary")
                ?: info.progress.getString("summary")
                ?: when (info.state) {
                    androidx.work.WorkInfo.State.RUNNING -> "Синхронизация выплат выполняется…"
                    androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED -> "Синхронизация выплат ожидает запуска Android…"
                    androidx.work.WorkInfo.State.FAILED -> "Синхронизация выплат завершилась ошибкой без подробностей."
                    androidx.work.WorkInfo.State.CANCELLED -> "Синхронизация выплат отменена."
                    else -> "Синхронизация выплат завершена без диагностического сообщения."
                }
        } ?: "Синхронизация выплат ещё не запускалась. Нажми кнопку обновления на этом экране."
    val refreshAllMarketData: () -> Unit = {
        refreshQuotes()
        refreshEvents()
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF2588FF),
        secondary = androidx.compose.ui.graphics.Color(0xFF55C99A),
        background = androidx.compose.ui.graphics.Color(0xFF080F1B),
        surface = androidx.compose.ui.graphics.Color(0xFF101C30),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF14243B),
        secondaryContainer = androidx.compose.ui.graphics.Color(0xFF1B3555),
        onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFB8D8FF),
        tertiary = androidx.compose.ui.graphics.Color(0xFF4B9BFF),
        tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF1B3555),
        onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFB8D8FF),
        onBackground = androidx.compose.ui.graphics.Color(0xFFF4F5F8),
        onSurface = androidx.compose.ui.graphics.Color(0xFFF4F5F8),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFB3B7C4)
    )) {
        Scaffold(
            floatingActionButton = {
                if (selectedAssetDetail == null && selected == Screen.Operations) {
                    FloatingActionButton(onClick = { showAddOperation = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить")
                    }
                }
            },
            bottomBar = { if (selectedAssetDetail == null) {
                NavigationBar(
                    containerColor = androidx.compose.ui.graphics.Color(0xFF0B1422),
                    contentColor = androidx.compose.ui.graphics.Color(0xFFAAB9D0),
                    tonalElevation = 0.dp
                ) {
                    listOf(Screen.Overview, Screen.Assets, Screen.Payouts, Screen.Analytics, Screen.More).forEach { screen ->
                        NavigationBarItem(
                            selected = selected == screen || (screen == Screen.More && (selected == Screen.Accounts || selected == Screen.Operations)),
                            onClick = { selected = screen },
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = androidx.compose.ui.graphics.Color(0xFF4B9BFF),
                                selectedTextColor = androidx.compose.ui.graphics.Color(0xFF69AFFF),
                                indicatorColor = androidx.compose.ui.graphics.Color(0xFF1B3555),
                                unselectedIconColor = androidx.compose.ui.graphics.Color(0xFF8797AD),
                                unselectedTextColor = androidx.compose.ui.graphics.Color(0xFF8797AD)
                            )
                        )
                    }
                }
            } }
        ) { padding ->
            if (selectedAssetDetail != null) {
                val detail = assets.firstOrNull { it.id == selectedAssetDetail!!.id } ?: selectedAssetDetail!!
                AssetDetailScreen(detail, assets, operations, payouts, { selectedAssetDetail = null }, Modifier.padding(padding))
            } else when (selected) {
                Screen.Overview -> OverviewScreen(assets, total, cashBalance ?: 0.0, payouts, operations, portfolioSnapshots, selectedHistoryRange, { selectedHistoryRange = it }, historyResult, historyLoading, historyLoadError, { historyRefresh++ }, dashboardMarketData, { dashboardMarketRefresh++ }, refreshAllMarketData, isRefreshingMarketData, onOpenSettings = { selected = Screen.More }, modifier = Modifier.padding(padding))
                Screen.Assets -> AssetsScreen(assets, operations, payouts, brokerAccounts, brokerAccountAssets, onOpenAsset = { selectedAssetDetail = it }, onEditAsset = { assetToEdit = it }, onDeleteAsset = { assetToDelete = it }, modifier = Modifier.padding(padding))
                Screen.Accounts -> AccountsScreen(brokerAccounts, brokerAccountCash, brokerAccountAssets, assets, operations, payouts, { account -> scope.launch(Dispatchers.IO) { dao.upsertBrokerAccount(account) } }, Modifier.padding(padding))
                Screen.Operations -> OperationsScreen(operations, brokerAccounts, selectedOperationAccount, { selectedOperationAccount = it }, onAddCashFlow = { showCashFlow = true }, modifier = Modifier.padding(padding))
                Screen.Payouts -> PayoutsScreen(payouts, assets, brokerAccounts, brokerAccountAssets, selectedPayoutAccount, { selectedPayoutAccount = it }, onConfirmReceipt = { payoutToConfirm = it }, onRefreshEvents = refreshEvents, eventSyncMessage = eventSyncMessage, modifier = Modifier.padding(padding))
                Screen.Analytics -> AnalyticsScreen(assets, total, cashBalance ?: 0.0, operations, payouts, portfolioSnapshots, portfolioCompositionSnapshots, brokerAccounts, brokerAccountAssets, brokerAccountCash, dao, Modifier.padding(padding))
                Screen.More -> MoreScreen(
                    onAddAsset = { showAddAsset = true },
                    onAddPayout = { showAddPayout = true },
                    hasAssets = assets.isNotEmpty(),
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
    onOpenSettings: () -> Unit,
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.TrendingUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                    Text("Мой капитал", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
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
            // Detailed MOEX diagnostics stay out of the dashboard; refresh feedback is the compact spinner above.
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().border(
                    1.dp, androidx.compose.ui.graphics.Color(0xFF223653), RoundedCornerShape(24.dp)
                ),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF101D32))
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Общая стоимость портфеля",
                            modifier = Modifier.weight(1f),
                            color = androidx.compose.ui.graphics.Color(0xFFB8C9E8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false
                        )
                        Row(
                            Modifier.clip(RoundedCornerShape(20.dp))
                                .background(androidx.compose.ui.graphics.Color(0xFF1A2942))
                                .padding(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            PortfolioHistoryRange.entries.forEach { range ->
                                val active = selectedRange == range
                                Text(
                                    range.label,
                                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                        .background(if (active) androidx.compose.ui.graphics.Color(0xFF1688F8) else androidx.compose.ui.graphics.Color.Transparent)
                                        .clickable { onSelectedRangeChange(range) }
                                        .padding(horizontal = 5.dp, vertical = 6.dp),
                                    color = if (active) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color(0xFFB8C9E8),
                                    fontSize = 10.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                    Text(
                        "${money(portfolioTotal)} ₽",
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = androidx.compose.ui.graphics.Color(0xFFF5F7FC)
                    )
                    Text(
                        (if (totalProfit >= 0) "▲ +" else "▼ −") + "${money(kotlin.math.abs(totalProfit))} ₽" +
                            (profitPercent?.let { " (${if (it >= 0) "+" else ""}${String.format(java.util.Locale("ru", "RU"), "%.2f", it)}%)" } ?: ""),
                        modifier = Modifier.padding(top = 1.dp, bottom = 5.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (totalProfit >= 0) androidx.compose.ui.graphics.Color(0xFF25D879) else androidx.compose.ui.graphics.Color(0xFFFF5964)
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
                                color = androidx.compose.ui.graphics.Color(0xFFB8C9E8)
                            )
                        }
                    } else {
                        val portfolioValues = history.map { it.totalValue }
                        // Net cash flows may be negative in an incomplete early ledger.
                        // Keep the chart's monetary axis anchored at zero.
                        val investedValues = history.map { it.investedCapital.coerceAtLeast(0.0) }
                        val minValue = 0.0
                        val maxValue = (portfolioValues + investedValues).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                        val span = maxValue
                        val axisValues = (0..3).map { maxValue - span * it / 3.0 }
                        val portfolioColor = androidx.compose.ui.graphics.Color(0xFF2698FF)
                        val investedColor = androidx.compose.ui.graphics.Color(0xFF8798B5)
                        val gridColor = androidx.compose.ui.graphics.Color(0xFF293A55)
                        val axisColor = androidx.compose.ui.graphics.Color(0xFFB8C9E8)
                        val chartDates = listOf(0, (history.lastIndex / 3), (history.lastIndex * 2 / 3), history.lastIndex)
                            .distinct()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier.width(40.dp).height(115.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                axisValues.forEach { value ->
                                    Text(
                                        money(value).substringBefore(','),
                                        fontSize = 9.sp,
                                        color = axisColor,
                                        maxLines = 1
                                    )
                                }
                            }
                            Canvas(Modifier.weight(1f).height(115.dp)) {
                                val left = 2f
                                val right = size.width - 2f
                                val top = 5f
                                val bottom = size.height - 5f
                                for (i in 0..4) {
                                    val y = top + (bottom - top) * i / 4f
                                    drawLine(gridColor, Offset(left, y), Offset(right, y), 1.dp.toPx())
                                }
                                for (i in 0..5) {
                                    val x = left + (right - left) * i / 5f
                                    drawLine(gridColor.copy(alpha = 0.65f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
                                }
                                fun drawSeries(series: List<Double>, color: androidx.compose.ui.graphics.Color, dashed: Boolean) {
                                    if (series.size < 2) return
                                    val path = Path()
                                    series.forEachIndexed { index, value ->
                                        val x = left + (right - left) * index / (series.size - 1).toFloat()
                                        val y = bottom - ((value - minValue) / span).toFloat() * (bottom - top)
                                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                    }
                                    drawPath(
                                        path, color,
                                        style = Stroke(
                                            width = if (dashed) 2.5.dp.toPx() else 3.dp.toPx(),
                                            cap = StrokeCap.Round,
                                            pathEffect = if (dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())) else null
                                        )
                                    )
                                }
                                drawSeries(portfolioValues, portfolioColor, false)
                                drawSeries(investedValues, investedColor, true)
                                val portfolioY = bottom - ((portfolioValues.last() - minValue) / span).toFloat() * (bottom - top)
                                val investedY = bottom - ((investedValues.last() - minValue) / span).toFloat() * (bottom - top)
                                drawCircle(portfolioColor, 4.dp.toPx(), Offset(right, portfolioY))
                                drawCircle(investedColor, 3.dp.toPx(), Offset(right, investedY))
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(start = 40.dp, top = 2.dp),
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
                            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                            fontSize = 9.sp,
                            color = androidx.compose.ui.graphics.Color(0xFFFFC66D)
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 1.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFF2698FF)))
                            Text("Портфель", color = androidx.compose.ui.graphics.Color(0xFFB8C9E8), style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.width(22.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Canvas(Modifier.width(25.dp).height(8.dp)) {
                                drawLine(
                                    androidx.compose.ui.graphics.Color(0xFF8798B5), Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                                    2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                                )
                            }
                            Text("Вложенные средства", color = androidx.compose.ui.graphics.Color(0xFFB8C9E8), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        item {
            MarketQuotesSection(marketData = marketData)
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
    onAddAsset: () -> Unit,
    onAddPayout: () -> Unit,
    hasAssets: Boolean,
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
        Text("Ручное добавление", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth().clickable(onClick = onAddAsset)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AddCircleOutline, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Добавить актив", fontWeight = FontWeight.SemiBold)
                    Text("Вручную внести бумагу и её текущую позицию", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }
        Card(
            Modifier.fillMaxWidth().then(if (hasAssets) Modifier.clickable(onClick = onAddPayout) else Modifier),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Payments, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Добавить выплату", fontWeight = FontWeight.SemiBold)
                    Text(if (hasAssets) "Вручную внести дивиденд или купон" else "Сначала добавьте актив в портфель", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = if (hasAssets) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
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
    payouts: List<PayoutEntity>,
    accounts: List<BrokerAccountEntity>,
    accountAssets: List<BrokerAccountAssetEntity>,
    onOpenAsset: (AssetEntity) -> Unit,
    onEditAsset: (AssetEntity) -> Unit,
    onDeleteAsset: (AssetEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedAccountId by remember { mutableStateOf(ALL_HISTORY_ACCOUNTS) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    val assetsByTicker = remember(assets) { assets.associateBy { it.ticker.uppercase() } }
    val displayedAssets = remember(assets, accountAssets, selectedAccountId) {
        if (selectedAccountId == ALL_HISTORY_ACCOUNTS) {
            assets
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
    val selectedAccountName = accounts.firstOrNull { it.accountId == selectedAccountId }?.displayName
        ?: "Все счета"

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
        if (displayedAssets.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (selectedAccountId == ALL_HISTORY_ACCOUNTS) "Пока нет активов. Добавьте первый актив во вкладке «Ещё»."
                    else "В последнем импортированном отчёте этого счёта нет открытых позиций.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 90.dp)
            ) {
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
        }
    }
}

@Composable
private fun AssetDetailScreen(
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
    val price = candles.lastOrNull()?.close ?: asset.currentPrice
    val isDividendAsset = !(asset.assetType.contains("облиг", true) || asset.assetType.contains("офз", true) || asset.assetType.contains("bond", true))
    var dividendForecast by remember(asset.ticker) {
        mutableStateOf<List<ru.mycapital.app.data.DividendCalendarEvent>>(emptyList())
    }

    LaunchedEffect(asset.ticker, isDividendAsset) {
        dividendForecast = if (isDividendAsset) {
            withContext(Dispatchers.IO) {
                runCatching {
                        val today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow"))
                        ru.mycapital.app.data.mergeOfficialDividendAnnouncements(
                            ru.mycapital.app.data.LongShortDividendClient().getUpcomingDividends(today),
                            today
                        )
                    }
                    .getOrDefault(emptyList())
                    .filter { it.ticker.equals(asset.ticker, true) }
            }
        } else emptyList()
    }
    val forwardDividendYieldPercent = remember(dividendForecast, price, asset.ticker) {
        ru.mycapital.app.data.calculateForwardDividendYieldPercent(
            dividendForecast,
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
    val received = actual.sumOf { it.reportedNetAmount ?: (it.totalAmount - it.taxAmount) }
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
                    AssetDetailMetric(dividendStatusLabel(payout.status), "${money(payout.totalAmount)} ₽")
                    AssetDetailMetric("На одну акцию", "${money(payout.amountPerUnit)} ₽")
                    payout.recordDate?.let { date ->
                        AssetDetailMetric("Дата отсечки", java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru", "RU")).format(java.util.Date(date)))
                    }
                    payout.expectedDate?.let { date ->
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

@Composable
private fun AssetDetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun AssetDetailMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(10.dp))
        Text(value, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

internal fun buildAssetReturnCashFlows(
    operations: List<OperationEntity>,
    payouts: List<PayoutEntity>,
    currentValue: Double,
    valuationDate: Long
): List<Pair<Long, Double>> {
    val flows = mutableListOf<Pair<Long, Double>>()
    operations.forEach { operation ->
        val gross = operation.quantity * operation.price
        val amount = when (operation.operationType) {
            "Покупка" -> -(gross + operation.commission)
            "Продажа" -> gross - operation.commission
            // Explicit asset-level costs are supported when present in the ledger.
            // Coupon/dividend/redemption receipts are taken from PayoutEntity below,
            // so their matching operation rows must not be counted twice.
            "Комиссия", "Налог", "Расход", "Списание" -> -(gross + operation.commission)
            else -> null
        }
        if (amount != null && amount.isFinite() && amount != 0.0) {
            flows += operation.operationDate to amount
        }
    }
    payouts.filter { it.actualDate != null || it.status.equals("RECEIVED", ignoreCase = true) }
        .forEach { payout ->
            val date = payout.actualDate ?: payout.expectedDate ?: return@forEach
            val amount = payout.reportedNetAmount ?: (payout.totalAmount - payout.taxAmount)
            if (amount.isFinite() && amount != 0.0) flows += date to amount
        }
    if (currentValue.isFinite() && currentValue > 0.0) {
        flows += valuationDate to currentValue
    }
    return flows.sortedBy { it.first }
}

internal fun annualizedReturnPercent(flows: List<Pair<Long, Double>>): Double? {
    val items = flows.filter { it.second.isFinite() && it.second != 0.0 }.sortedBy { it.first }
    if (items.size < 2 || items.none { it.second < 0.0 } || items.none { it.second > 0.0 }) return null
    val start = items.first().first
    // Excel XIRR (ЧИСТВНДОХ), used by Snowball, annualizes on a 365-day basis.
    val millisPerYear = 365.0 * 24.0 * 60.0 * 60.0 * 1000.0
    if ((items.last().first - start) / millisPerYear <= 0.0) return null
    fun npv(rate: Double) = items.sumOf { (date, amount) -> amount / Math.pow(1.0 + rate, (date - start) / millisPerYear) }
    var low = -0.9999
    var high = 10.0
    if (npv(low) * npv(high) > 0.0) return null
    repeat(120) {
        val mid = (low + high) / 2.0
        if (npv(low) * npv(mid) <= 0.0) high = mid else low = mid
    }
    val result = (low + high) / 2.0
    return if (result.isFinite()) result * 100.0 else null
}

@Composable
private fun DoubleHeadedEagleIcon() {
    val context = LocalContext.current
    AsyncImage(
        model = "file:///android_asset/logos/russian_coat_of_arms_white.svg",
        contentDescription = "Белый герб для ОФЗ",
        modifier = Modifier.size(40.dp),
        imageLoader = AssetLogoCatalog.imageLoader(context),
        contentScale = ContentScale.Fit
    )
}


@Composable
internal fun GoldBullionLogo(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun ingot(left: Float, top: Float, width: Float, height: Float, shade: Color) {
            val path = Path().apply {
                moveTo(left + width * .18f, top)
                lineTo(left + width * .82f, top)
                lineTo(left + width, top + height)
                lineTo(left, top + height)
                close()
            }
            drawPath(path, shade)
            drawLine(
                Color(0xFFFFE9A6),
                Offset(left + width * .2f, top + height * .28f),
                Offset(left + width * .8f, top + height * .28f),
                strokeWidth = size.minDimension * .035f
            )
        }
        ingot(w * .03f, h * .12f, w * .43f, h * .35f, Color(0xFFD99B24))
        ingot(w * .54f, h * .12f, w * .43f, h * .35f, Color(0xFFF0C65A))
        ingot(w * .27f, h * .52f, w * .46f, h * .38f, Color(0xFFFFD76A))
    }
}

@Composable
private fun AssetTypeIcon(
    assetType: String,
    ticker: String
) {
    val type = assetType.lowercase()
    val normalizedTicker = ticker.trim().uppercase()
    val isBond = type.contains("облигац") || type.contains("офз") || normalizedTicker.startsWith("SU")
    val isGoldFund = normalizedTicker == "GOLD"
    val logoPath = if (isBond || isGoldFund) null else AssetLogoCatalog.assetPathFor(normalizedTicker)
    val context = LocalContext.current

    if (isGoldFund) {
        GoldBullionLogo(Modifier.size(42.dp))
    } else if (isBond) {
        // Match the supplied reference: plain dark medallion with a large
        // white double-headed eagle. No light outline or frame.
        Surface(
            shape = CircleShape,
            color = Color(0xFF29354F),
            modifier = Modifier.size(46.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                DoubleHeadedEagleIcon()
            }
        }
    } else {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(46.dp)) {
            when {
                logoPath != null -> AsyncImage(
                    model = "file:///android_asset/$logoPath",
                    contentDescription = "Логотип $ticker",
                    modifier = Modifier.size(40.dp),
                    imageLoader = AssetLogoCatalog.imageLoader(context),
                    contentScale = ContentScale.Fit
                )
                else -> Text(
                    normalizedTicker.take(4).ifBlank { "?" },
                    color = androidx.compose.ui.graphics.Color(0xFF334155),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1
                )
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
    includeUnassigned: Boolean = true,
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
            if (includeUnassigned) {
                DropdownMenuItem(
                    text = { Text("Без привязки к счёту") },
                    onClick = { onSelect(UNASSIGNED_HISTORY_ACCOUNT); expanded = false }
                )
            }
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
        AccountHistoryFilter(accounts, selectedAccountId, onSelect = onAccountSelected)
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
    assets: List<AssetEntity>,
    accounts: List<ru.mycapital.app.data.BrokerAccountEntity>,
    accountAssets: List<ru.mycapital.app.data.BrokerAccountAssetEntity>,
    selectedAccountId: String,
    onAccountSelected: (String) -> Unit,
    onConfirmReceipt: (PayoutEntity) -> Unit,
    onRefreshEvents: () -> Unit,
    eventSyncMessage: String?,
    modifier: Modifier = Modifier
) {
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val currentYear = today.year
    // Keep years in chronological order: older years are to the left of the current year,
    // newer years are to the right. The initial viewport is anchored on the current year.
    val periods = remember(currentYear) {
        buildList {
            (1..5).reversed().forEach { offset ->
                if (currentYear - offset >= 2000) add("year:${currentYear - offset}")
            }
            add("year:$currentYear")
            (1..6).forEach { add("year:${currentYear + it}") }
        }
    }
    val periodListState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = periods.indexOf("year:$currentYear").coerceAtLeast(0)
    )
    var selectedPeriod by remember(currentYear) { mutableStateOf("year:$currentYear") }
    var statusFilter by remember { mutableStateOf("Все") }
    var typeFilter by remember { mutableStateOf("Все") }
    var expandedPayoutId by remember { mutableStateOf<Long?>(null) }
    val visiblePayouts = remember(payouts, selectedAccountId, accountAssets) {
        val selectedTickers = if (selectedAccountId == ALL_HISTORY_ACCOUNTS || selectedAccountId == UNASSIGNED_HISTORY_ACCOUNT) {
            emptySet()
        } else {
            accountAssets.asSequence()
                .filter { it.accountId == selectedAccountId && it.quantity > 0.0 }
                .map { it.ticker.uppercase() }
                .toSet()
        }
        payouts.filter { payout ->
            when (selectedAccountId) {
                ALL_HISTORY_ACCOUNTS -> true
                UNASSIGNED_HISTORY_ACCOUNT -> historyAccountId(payout.sourceKey) == null
                else -> {
                    val payoutAccountId = historyAccountId(payout.sourceKey)
                    if (payoutAccountId != null) {
                        payoutAccountId == selectedAccountId
                    } else {
                        // Portfolio-level forecasts/manual events are shown only when their
                        // ticker is actually held on the selected broker account.
                        payout.ticker.uppercase() in selectedTickers
                    }
                }
            }
        }
    }
    val periodDates = remember(selectedPeriod, today) {
        val year = selectedPeriod.substringAfter("year:").toIntOrNull() ?: currentYear
        java.time.LocalDate.of(year, 1, 1) to java.time.LocalDate.of(year, 12, 31)
    }
    val filtered = remember(visiblePayouts, periodDates, statusFilter, typeFilter) {
        visiblePayouts.filter { payout ->
            val millis = payout.actualDate ?: payout.expectedDate ?: return@filter false
            val date = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            val inPeriod = !date.isBefore(periodDates.first) && !date.isAfter(periodDates.second)
            val statusOk = statusFilter == "Все" || when (statusFilter) {
                "Объявлены" -> payout.status == "ANNOUNCED"
                "Рекомендованы" -> payout.status == "RECOMMENDED"
                "Прогноз" -> payout.status == "FORECAST" || payout.status == "EXPECTED"
                "Получены" -> payout.status == "RECEIVED"
                else -> true
            }
            val typeOk = typeFilter == "Все" ||
                (typeFilter == "Дивиденды" && payout.payoutType.contains("Дивиденд", true)) ||
                (typeFilter == "Купоны" && payout.payoutType.contains("Купон", true)) ||
                (typeFilter == "Погашения" && (payout.payoutType.contains("Погаш", true) || payout.payoutType.contains("Амортизац", true)))
            inPeriod && statusOk && typeOk
        }.sortedWith(compareBy<PayoutEntity> { it.actualDate ?: it.expectedDate ?: Long.MAX_VALUE }.thenBy { it.ticker })
    }
    fun payoutAmount(p: PayoutEntity): Double =
        if (p.status == "RECEIVED") (p.reportedNetAmount ?: (p.totalAmount - p.taxAmount)).coerceAtLeast(0.0)
        else p.totalAmount.coerceAtLeast(0.0)
    // Principal returned at bond amortization/maturity is capital, not income.
    // Keep redemption rows visible below, but exclude them from income metrics.
    val totalAmount = filtered.filter { isIncomePayoutType(it.payoutType) }.sumOf(::payoutAmount)
    val monthly = filtered.groupBy { payout ->
        java.time.YearMonth.from(java.time.Instant.ofEpochMilli(payout.actualDate ?: payout.expectedDate!!).atZone(zone).toLocalDate())
    }.toSortedMap()
    val monthlyTotals = monthly.mapValues { (_, items) -> items.sumOf(::payoutAmount) }
    val monthAverage = if (monthly.isNotEmpty()) totalAmount / monthly.size else 0.0
    val dayAverage = if (periodDates.second.toEpochDay() >= periodDates.first.toEpochDay()) {
        totalAmount / (periodDates.second.toEpochDay() - periodDates.first.toEpochDay() + 1)
    } else 0.0
    val portfolioValue = assets.sumOf { it.quantity.coerceAtLeast(0.0) * it.currentPrice.coerceAtLeast(0.0) }
    val payoutYield = if (portfolioValue > 0.0) totalAmount / portfolioValue * 100.0 else 0.0
    // Calendar chart follows the selected calendar year: January through December.
    val chartMonths = remember(selectedPeriod) {
        val year = selectedPeriod.substringAfter("year:").toIntOrNull() ?: currentYear
        val first = java.time.YearMonth.of(year, 1)
        (0 until 12).map { first.plusMonths(it.toLong()) }
    }
    val chartPayouts = visiblePayouts.filter { payout ->
        val dateMillis = payout.actualDate ?: payout.expectedDate ?: return@filter false
        val date = java.time.Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
        val month = java.time.YearMonth.from(date)
        val inChartPeriod = !month.isBefore(chartMonths.first()) && !month.isAfter(chartMonths.last())
        val typeOk = typeFilter == "Все" ||
            (typeFilter == "Дивиденды" && payout.payoutType.contains("Дивиденд", true)) ||
            (typeFilter == "Купоны" && payout.payoutType.contains("Купон", true)) ||
            (typeFilter == "Погашения" && (payout.payoutType.contains("Погаш", true) || payout.payoutType.contains("Амортизац", true)))
        inChartPeriod && typeOk && isIncomePayoutType(payout.payoutType)
    }
    val chartMonthly = chartPayouts.groupBy { payout ->
        java.time.YearMonth.from(java.time.Instant.ofEpochMilli(payout.actualDate ?: payout.expectedDate!!).atZone(zone).toLocalDate())
    }
    val receivedByMonth = chartMonths.associateWith { month ->
        chartMonthly[month].orEmpty().filter { it.status == "RECEIVED" }.sumOf(::payoutAmount)
    }
    val announcedByMonth = chartMonths.associateWith { month ->
        chartMonthly[month].orEmpty().filter { it.status == "ANNOUNCED" }.sumOf(::payoutAmount)
    }
    // Forecasts are meaningful only from the current month onward. Do not
    // project stale recommendations/estimates into months that have already passed.
    val currentMonth = java.time.YearMonth.from(today)
    val forecastByMonth = chartMonths.associateWith { month ->
        if (month.isBefore(currentMonth)) 0.0
        else chartMonthly[month].orEmpty()
            .filter { it.status == "RECOMMENDED" || it.status == "FORECAST" || it.status == "EXPECTED" }
            .sumOf(::payoutAmount)
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Выплаты", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                AccountHistoryFilter(accounts, selectedAccountId, onSelect = onAccountSelected)
            }
            item {
                androidx.compose.foundation.lazy.LazyRow(
                    state = periodListState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(periods) { period ->
                        val label = when (period) {
                            else -> period.substringAfter("year:")
                        }
                        FilterChip(
                            selected = selectedPeriod == period,
                            onClick = { selectedPeriod = period },
                            label = { Text(label, maxLines = 1) }
                        )
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Доход за ${selectedPeriod.substringAfter("year:")}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${money(totalAmount)} ₽", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("В месяц", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${money(monthAverage)} ₽", fontWeight = FontWeight.SemiBold)
                        }
                        Column {
                            Text("В день", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${money(dayAverage)} ₽", fontWeight = FontWeight.SemiBold)
                        }
                        Column {
                            Text("Доходность", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${String.format(java.util.Locale("ru", "RU"), "%.2f", payoutYield)}%", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item {
                PayoutCalendarChart(
                    months = chartMonths,
                    received = receivedByMonth,
                    announced = announcedByMonth,
                    forecast = forecastByMonth
                )
            }
            item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("Все", "Объявлены", "Рекомендованы", "Прогноз", "Получены")) { filter ->
                        FilterChip(selected = statusFilter == filter, onClick = { statusFilter = filter }, label = { Text(filter) })
                    }
                }
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("Все", "Дивиденды", "Купоны", "Погашения")) { filter ->
                        FilterChip(selected = typeFilter == filter, onClick = { typeFilter = filter }, label = { Text(filter) })
                    }
                }
            }
            if (monthly.isEmpty()) {
                item {
                    Text("Нет выплат по выбранным фильтрам.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp))
                }
            } else {
                monthly.forEach { (month, itemsForMonth) ->
                    item(key = "month-$month") {
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                month.format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", java.util.Locale("ru", "RU")))
                                    .replaceFirstChar { it.titlecase(java.util.Locale("ru", "RU")) },
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text("+${money(itemsForMonth.filter { isIncomePayoutType(it.payoutType) }.sumOf(::payoutAmount))} ₽",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    color = Color(0xFF55C99A), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    val byDate = itemsForMonth.groupBy { java.time.Instant.ofEpochMilli(it.actualDate ?: it.expectedDate!!).atZone(zone).toLocalDate() }.toSortedMap()
                    byDate.forEach { (date, dayItems) ->
                        item(key = "date-$date") {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CalendarToday, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    Text(
                                        date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale("ru", "RU"))) +
                                            ", " + date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale("ru", "RU")),
                                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                dayItems.forEach { payout ->
                                    val asset = assets.firstOrNull { it.id == payout.assetId }
                                        ?: assets.firstOrNull { it.ticker.equals(payout.ticker, true) }
                                    val expanded = expandedPayoutId == payout.id
                                    Card(
                                        modifier = Modifier.fillMaxWidth().clickable { expandedPayoutId = if (expanded) null else payout.id },
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                AssetTypeIcon(
                                                    asset?.assetType ?: if (payout.payoutType.contains("купон", true)) "Облигация" else "Акция",
                                                    payout.ticker
                                                )
                                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                                    Text("${payout.ticker}  ${asset?.name ?: payout.payoutType}",
                                                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                                                }
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text("+${money(payoutAmount(payout))} ₽", color = Color(0xFF55C99A), fontWeight = FontWeight.Bold)
                                                    val yield = if (asset != null && asset.currentPrice > 0.0 && payout.amountPerUnit > 0.0)
                                                        payout.amountPerUnit / asset.currentPrice * 100.0 else null
                                                    if (yield != null) Text(String.format(java.util.Locale("ru", "RU"), "%.2f%%", yield),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                                    Text(
                                                        when (payout.status) {
                                                            "RECEIVED" -> "Получено"
                                                            "ANNOUNCED" -> "Объявлено"
                                                            "RECOMMENDED", "FORECAST", "EXPECTED" -> "Прогноз"
                                                            else -> "Прогноз"
                                                        },
                                                        color = Color(0xFF69AFFF),
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                            }
                                            Text(
                                                (if (payout.payoutType.contains("Купон", true)) "Купон" else payout.payoutType) +
                                                    " · " + (if (payout.quantity > 0.0) "${money(payout.amountPerUnit)} ₽ на бумагу" else "сумма выплаты"),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            if (expanded) {
                                                HorizontalDivider()
                                                payout.recordDate?.let { Text("Дата отсечки: " + java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))) }
                                                payout.expectedDate?.let { Text("Ожидаемая дата выплаты: " + java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))) }
                                                payout.actualDate?.let { Text("Получено: " + java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))) }
                                                if (payout.status != "RECEIVED" && (isUpcomingDividendStatus(payout.status) || payout.status == "EXPECTED")) {
                                                    TextButton(onClick = { onConfirmReceipt(payout) }) { Text("Подтвердить получение") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PayoutCalendarChart(
    months: List<java.time.YearMonth>,
    received: Map<java.time.YearMonth, Double>,
    announced: Map<java.time.YearMonth, Double>,
    forecast: Map<java.time.YearMonth, Double>
) {
    if (months.isEmpty()) return
    val maxValue = months.maxOfOrNull {
        (received[it] ?: 0.0) + (announced[it] ?: 0.0) + (forecast[it] ?: 0.0)
    }?.coerceAtLeast(1.0) ?: 1.0
    val receivedColor = Color(0xFF55C99A)
    val announcedColor = Color(0xFF48A9E8)
    val forecastColor = Color(0xFF087BAA).copy(alpha = 0.28f)
    val locale = java.util.Locale("ru", "RU")
    fun compactAmount(value: Double): String = when {
        value >= 1_000_000.0 -> String.format(locale, "%.1fм", value / 1_000_000.0)
        value >= 10_000.0 -> String.format(locale, "%.0fк", value / 1_000.0)
        value >= 1_000.0 -> String.format(locale, "%.1fк", value / 1_000.0)
        else -> String.format(locale, "%.0f", value)
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            months.forEach { month ->
                val receivedValue = received[month] ?: 0.0
                val announcedValue = announced[month] ?: 0.0
                val forecastValue = forecast[month] ?: 0.0
                val total = receivedValue + announcedValue + forecastValue
                val barHeightDp = (total / maxValue * 112.0).coerceAtLeast(0.0)
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(Modifier.fillMaxWidth().height(132.dp)) {
                        Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(112.dp).padding(horizontal = 1.dp)) {
                            val plotHeight = size.height - 2.dp.toPx()
                            val receivedHeight = (receivedValue / maxValue * plotHeight).toFloat()
                            val announcedHeight = (announcedValue / maxValue * plotHeight).toFloat()
                            val forecastHeight = (forecastValue / maxValue * plotHeight).toFloat()
                            val barWidth = size.width * 0.88f
                            val left = (size.width - barWidth) / 2f
                            val bottom = size.height
                            if (receivedHeight > 0.5f) {
                                drawRoundRect(
                                    receivedColor,
                                    Offset(left, bottom - receivedHeight),
                                    androidx.compose.ui.geometry.Size(barWidth, receivedHeight),
                                    androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                                )
                            }
                            if (announcedHeight > 0.5f) {
                                drawRoundRect(
                                    announcedColor,
                                    Offset(left, bottom - receivedHeight - announcedHeight),
                                    androidx.compose.ui.geometry.Size(barWidth, announcedHeight),
                                    androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                                )
                            }
                            if (forecastHeight > 0.5f) {
                                drawRoundRect(
                                    forecastColor,
                                    Offset(left, bottom - receivedHeight - announcedHeight - forecastHeight),
                                    androidx.compose.ui.geometry.Size(barWidth, forecastHeight),
                                    androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                                )
                            }
                        }
                        Text(
                            if (total > 0.0) compactAmount(total) else "—",
                            modifier = Modifier.align(Alignment.BottomCenter).offset(y = -(barHeightDp.dp + 2.dp)),
                            fontSize = 8.sp,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        month.month.getDisplayName(java.time.format.TextStyle.SHORT, locale)
                            .replace(".", "").take(3).lowercase(locale),
                        fontSize = 9.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 5.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(receivedColor))
                Text("Получено", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(announcedColor))
                Text("Объявлены", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(forecastColor))
                Text("Прогноз", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val analyticsMarketValue = analyticsAssets.sumOf { (it.quantity * it.currentPrice).coerceAtLeast(0.0) }
    val selectedTickers = remember(accountAssets, selectedAccountId) {
        accountAssets.filter { it.accountId == selectedAccountId && it.quantity > 0.0 }.map { it.ticker.uppercase() }.toSet()
    }
    val analyticsOperations = remember(operations, selectedAccountId) {
        if (selectedAccountId == ALL_HISTORY_ACCOUNTS) operations
        else operations.filter { historyAccountId(it.sourceKey) == selectedAccountId }
    }
    val analyticsPayouts = remember(payouts, selectedAccountId, selectedTickers) {
        if (selectedAccountId == ALL_HISTORY_ACCOUNTS) payouts
        else payouts.filter { payout ->
            val payoutAccountId = historyAccountId(payout.sourceKey)
            if (payoutAccountId != null) payoutAccountId == selectedAccountId
            else payout.ticker.uppercase() in selectedTickers
        }
    }
    val invested = analyticsAssets.sumOf { it.quantity * it.averagePrice }
    val unrealized = analyticsMarketValue - invested
    val realizedResult = calculateRealizedTradingResult(analyticsOperations)
    val receivedPayouts = analyticsPayouts.filter { it.status == "RECEIVED" }
    val receivedDividends = receivedPayouts.filter { it.payoutType == "Дивиденд" }.sumOf { it.reportedNetAmount ?: (it.totalAmount - it.taxAmount) }
    val receivedCoupons = receivedPayouts.filter { it.payoutType == "Купон" }.sumOf { it.reportedNetAmount ?: (it.totalAmount - it.taxAmount) }
    val payoutIncome = receivedDividends + receivedCoupons
    val totalInvestmentResult = unrealized + realizedResult.profit + payoutIncome
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
                compositionSnapshots = compositionSnapshots
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

        } else {
            item {
                Text(
                    "Доходность по отдельному счёту пока не рассчитывается: себестоимость позиций хранится на уровне совокупного портфеля, а не каждого брокерского договора.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            if (selectedAccountId == ALL_HISTORY_ACCOUNTS) {
                Text("Динамика капитала", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (snapshots.size >= 2) {
                PortfolioHistoryChart(snapshots)
                Text("График показывает условную доходность (база 100) с исключением пополнений и выводов. Денежные потоки учитываются на конец дневного интервала; для точного TWR потребуется точное время каждого потока.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("История только начинается. Приложение сохраняет ежедневную стоимость капитала; график появится после второго дневного снимка.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("Историческая динамика по отдельному счёту пока недоступна: дневные снимки сохраняются для портфеля целиком.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selectedAccountId == ALL_HISTORY_ACCOUNTS) {
            item { IndexBenchmarkSection(snapshots, dao) }
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
        if (points.isNotEmpty()) {
            Text(
                "Доступная история: ${points.first().dateKey} — ${points.last().dateKey}. Если данных пока меньше выбранного периода, график закономерно будет одинаковым.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "Ежедневные снимки портфеля доступны с ${snapshots.minOfOrNull { it.dateKey } ?: "—"}. Более ранние значения в базе не сохранялись.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    val pendingTrades = report.trades.filterNot { isImportableBrokerTrade(it.status) }
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
                Text("На конец: ${money((report.plannedClosingCash ?: report.closingCash))} ₽")
                HorizontalDivider()
                val knownTickers = report.assets.count { row -> row.ticker.isNotBlank() }
                Text("Позиций в отчёте: ${report.assets.size} (с распознанными тикерами: $knownTickers)")
                Text("Сделок: ${report.trades.size} (импортируется, статусы «И/ЗИ/ЗО»: $completedTrades)")
                Text("Комиссии по всем сделкам: ${money(report.trades.sumOf { it.totalFee })} ₽")
                if (pendingTrades.isNotEmpty()) {
                    Text("Сделки, не включаемые в историю как исполненные: ${pendingTrades.size}", color = MaterialTheme.colorScheme.error)
                    pendingTrades.take(4).forEach { trade ->
                        Text("${trade.ticker}: ${trade.side}, ${trade.quantity} шт. · статус «${trade.status}»", fontSize = 12.sp)
                    }
                }
                HorizontalDivider()
                Text("Сверка позиций", fontWeight = FontWeight.SemiBold)
                Text("Плановые исходящие остатки (если есть) будут использованы для сверки, чтобы учесть сделки, заключённые, но ещё не рассчитанные. Остатки существующих бумаг будут обновлены, а новые позиции из справочника брокера добавлены в портфель. Затем приложение загрузит сделки, пополнения, дивиденды и купоны из этого же отчёта.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                knownRows.take(20).forEach { (row, current) ->
                    Text("${row.ticker}: по этому договору ${quantity(row.effectiveQuantity)} шт.; всего в приложении сейчас ${quantity(current!!.quantity)} шт.", fontSize = 12.sp)
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
                "Приложение обновит остатки договора ${report.accountId}, пересчитает общий портфель с учётом остальных договоров и импортирует историю операций и выплат из этого же отчёта. Повторно загруженные операции не будут продублированы. Сделки со статусами «И», «ЗИ» и «ЗО» попадут в историю; неизвестные или неподдерживаемые статусы будут пропущены."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Подтвердить сверку") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Назад") } }
    )
}
