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
    TargetPortfolio("План", Icons.Default.PieChart),
    Events("События", Icons.Default.EventNote),
    More("Ещё", Icons.Default.MoreHoriz),
    Accounts("Счета", Icons.Default.AccountBalance),
    Operations("Операции", Icons.Default.SwapHoriz)
}

@Composable
private fun AnalyticsNavigationIcon() {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val barColor = tint.copy(alpha = 0.48f)
        val barWidth = size.width * 0.13f
        val bottom = size.height * 0.94f
        val bars = listOf(
            0.12f to 0.20f,
            0.34f to 0.34f,
            0.56f to 0.48f,
            0.78f to 0.29f
        )
        bars.forEach { (xFraction, heightFraction) ->
            val barHeight = size.height * heightFraction
            drawRoundRect(
                color = barColor,
                topLeft = Offset(size.width * xFraction, bottom - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.035f)
            )
        }
        val trend = Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.43f)
            lineTo(size.width * 0.34f, size.height * 0.27f)
            lineTo(size.width * 0.59f, size.height * 0.35f)
            lineTo(size.width * 0.91f, size.height * 0.10f)
        }
        drawPath(
            path = trend,
            color = tint,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
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
    val marketCalendarItems by dao.observeMarketCalendarItems().collectAsState(initial = emptyList())
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
    LaunchedEffect(historyAssetsKey, operations, cashBalance, brokerAccounts, selectedHistoryRange, historyRefresh) {
        historyLoading = true
        historyLoadError = null
        var latestSuccessful: PortfolioMarketHistoryResult? = null
        var lastError: Throwable? = null
        for (attempt in 0..2) {
            try {
                val loaded = kotlinx.coroutines.withTimeout(90_000L) {
                    withContext(Dispatchers.IO) {
                        buildPortfolioMarketHistory(assets, operations, cashBalance ?: 0.0, selectedHistoryRange, includedBrokerAccountIds = brokerAccounts.map { it.accountId }.toSet())
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
    var isCreatingBackup by remember { mutableStateOf(false) }
    var isRestoringBackup by remember { mutableStateOf(false) }
    var pendingBackupRestore by remember { mutableStateOf<Uri?>(null) }
    var pendingBackupCreate by remember { mutableStateOf<Uri?>(null) }
    var backupCreatePassword by remember { mutableStateOf("") }
    var backupCreatePasswordConfirm by remember { mutableStateOf("") }
    var backupRestorePassword by remember { mutableStateOf("") }
    var backupResultMessage by remember { mutableStateOf<String?>(null) }
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
    val backupCreatePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) {
            pendingBackupCreate = uri
            backupCreatePassword = ""
            backupCreatePasswordConfirm = ""
        }
    }
    val backupRestorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) pendingBackupRestore = uri
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
    val newsWorkInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow("market_news_refresh_manual")
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
        val newsPeriodic = PeriodicWorkRequestBuilder<MarketNewsWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "market_news_refresh", ExistingPeriodicWorkPolicy.UPDATE, newsPeriodic
        )
        val newsRequest = OneTimeWorkRequestBuilder<MarketNewsWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "market_news_refresh_manual", ExistingWorkPolicy.KEEP, newsRequest
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
    val refreshNews: () -> Unit = {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<MarketNewsWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "market_news_refresh_manual", ExistingWorkPolicy.REPLACE, request
        )
    }
    val newsSyncMessage = (
        newsWorkInfos.firstOrNull { it.state == androidx.work.WorkInfo.State.RUNNING }
            ?: newsWorkInfos.firstOrNull { it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.BLOCKED }
            ?: newsWorkInfos.firstOrNull { it.state.isFinished }
        )?.let { info ->
            info.outputData.getString("summary") ?: info.progress.getString("summary")
                ?: when (info.state) {
                    androidx.work.WorkInfo.State.RUNNING -> "Обновление новостей выполняется…"
                    androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED -> "Обновление новостей ожидает подключения к сети…"
                    androidx.work.WorkInfo.State.FAILED -> "Не удалось обновить новости."
                    else -> null
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
                    listOf(Screen.Overview, Screen.Assets, Screen.Payouts, Screen.Analytics, Screen.TargetPortfolio, Screen.Operations).forEach { screen ->
                        NavigationBarItem(
                            selected = selected == screen || (screen == Screen.Operations && selected == Screen.Operations),
                            onClick = { selected = screen },
                            icon = {
                                if (screen == Screen.Analytics) AnalyticsNavigationIcon()
                                else Icon(screen.icon, contentDescription = screen.title)
                            },
                            label = {
                                Text(
                                    screen.title,
                                    fontSize = if (screen == Screen.Analytics) 8.5.sp else 9.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
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
                Screen.Assets -> AssetsScreen(assets, operations, payouts, brokerAccounts, brokerAccountAssets, cashBalance ?: 0.0, brokerAccountCash, onOpenAsset = { selectedAssetDetail = it }, onEditAsset = { assetToEdit = it }, onDeleteAsset = { assetToDelete = it }, modifier = Modifier.padding(padding))
                Screen.Accounts -> AccountsScreen(brokerAccounts, brokerAccountCash, brokerAccountAssets, assets, operations, payouts, { account -> scope.launch(Dispatchers.IO) { dao.upsertBrokerAccount(account) } }, Modifier.padding(padding))
                Screen.Operations -> OperationsScreen(operations, brokerAccounts, selectedOperationAccount, { selectedOperationAccount = it }, onAddCashFlow = { showCashFlow = true }, modifier = Modifier.padding(padding))
                Screen.Payouts -> PayoutsScreen(operations, payouts, assets, brokerAccounts, brokerAccountAssets, selectedPayoutAccount, { selectedPayoutAccount = it }, onConfirmReceipt = { payoutToConfirm = it }, onRefreshEvents = refreshEvents, eventSyncMessage = eventSyncMessage, modifier = Modifier.padding(padding))
                Screen.Analytics -> AnalyticsScreen(assets, total, cashBalance ?: 0.0, operations, payouts, portfolioSnapshots, portfolioCompositionSnapshots, brokerAccounts, brokerAccountAssets, brokerAccountCash, dao, Modifier.padding(padding))
                Screen.TargetPortfolio -> TargetPortfolioPlanSection(assets = assets, modifier = Modifier.padding(padding))
                Screen.Events -> EventsScreen(assets, cashBalance ?: 0.0, payouts, marketCalendarItems, brokerAccounts, brokerAccountAssets, brokerAccountCash, refreshNews, newsSyncMessage, Modifier.padding(padding))
                Screen.More -> MoreScreen(
                    onAddAsset = { showAddAsset = true },
                    onAddPayout = { showAddPayout = true },
                    hasAssets = assets.isNotEmpty(),
                    isParsingReport = isParsingReport,
                    isExportingData = isExportingData,
                    isCreatingBackup = isCreatingBackup,
                    isRestoringBackup = isRestoringBackup,
                    onCreateBackup = { backupCreatePicker.launch("MyCapital-backup.zip") },
                    onRestoreBackup = { backupRestorePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onImportReport = { reportPicker.launch(arrayOf("text/html", "application/xhtml+xml", "application/octet-stream")) },
                    onExportData = { exportPicker.launch("my-capital-export.zip") },
                    onOpenAccounts = { selected = Screen.Accounts },
                    onOpenOperations = { selected = Screen.Operations },
                    onOpenEvents = { selected = Screen.Events },
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
    if (pendingBackupCreate != null) {
        AlertDialog(
            onDismissRequest = { if (!isCreatingBackup) { pendingBackupCreate = null; backupCreatePassword = ""; backupCreatePasswordConfirm = "" } },
            title = { Text("Защитить резервную копию") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Архив будет зашифрован AES-256. Придумайте пароль длиной не менее 9 символов и сохраните его отдельно. Без пароля восстановить данные будет невозможно. Приложение пароль не сохраняет.")
                    OutlinedTextField(
                        value = backupCreatePassword,
                        onValueChange = { backupCreatePassword = it },
                        label = { Text("Пароль") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !isCreatingBackup
                    )
                    OutlinedTextField(
                        value = backupCreatePasswordConfirm,
                        onValueChange = { backupCreatePasswordConfirm = it },
                        label = { Text("Повторите пароль") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !isCreatingBackup
                    )
                    if (backupCreatePasswordConfirm.isNotEmpty() && backupCreatePassword != backupCreatePasswordConfirm) {
                        Text("Пароли не совпадают.", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isCreatingBackup && backupCreatePassword.length >= 9 && backupCreatePassword == backupCreatePasswordConfirm,
                    onClick = {
                        val uri = pendingBackupCreate ?: return@TextButton
                        val password = backupCreatePassword
                        isCreatingBackup = true
                        pendingBackupCreate = null
                        backupCreatePassword = ""
                        backupCreatePasswordConfirm = ""
                        scope.launch {
                            try {
                                val info = withContext(Dispatchers.IO) { PortfolioBackup.export(context, uri, password.toCharArray()) }
                                backupResultMessage = "Зашифрованная резервная копия AES-256 создана. Версия базы: ${info.databaseVersion}. Размер базы: ${info.databaseBytes / 1024} КБ. Пароль не сохранён — храните его отдельно."
                            } catch (error: Exception) {
                                errorMessage = error.message ?: "Не удалось создать резервную копию."
                            } finally {
                                isCreatingBackup = false
                            }
                        }
                    }
                ) { Text(if (isCreatingBackup) "Создаю…" else "Создать архив") }
            },
            dismissButton = { TextButton(enabled = !isCreatingBackup, onClick = { pendingBackupCreate = null; backupCreatePassword = ""; backupCreatePasswordConfirm = "" }) { Text("Отмена") } }
        )
    }
    if (pendingBackupRestore != null) {
        AlertDialog(
            onDismissRequest = { if (!isRestoringBackup) { pendingBackupRestore = null; backupRestorePassword = "" } },
            title = { Text("Восстановить резервную копию?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Введите пароль AES-256. Сначала архив будет расшифрован и проверен; только после успешной проверки текущая база будет заменена. Перед продолжением рекомендуется сохранить свежую копию текущих данных.")
                    OutlinedTextField(
                        value = backupRestorePassword,
                        onValueChange = { backupRestorePassword = it },
                        label = { Text("Пароль архива") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !isRestoringBackup
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !isRestoringBackup && backupRestorePassword.isNotBlank(), onClick = {
                    val uri = pendingBackupRestore ?: return@TextButton
                    val password = backupRestorePassword
                    isRestoringBackup = true
                    backupRestorePassword = ""
                    scope.launch {
                        try {
                            val info = withContext(Dispatchers.IO) { PortfolioBackup.restore(context, uri, password.toCharArray()) }
                            pendingBackupRestore = null
                            backupResultMessage = "База успешно расшифрована, проверена и восстановлена (версия ${info.databaseVersion}). Сейчас экран приложения будет перезапущен."
                            (context as? ComponentActivity)?.recreate()
                        } catch (error: Exception) {
                            errorMessage = error.message ?: "Не удалось восстановить резервную копию. Проверьте пароль и целостность архива."
                        } finally {
                            isRestoringBackup = false
                        }
                    }
                }) { Text(if (isRestoringBackup) "Восстанавливаю…" else "Восстановить") }
            },
            dismissButton = { TextButton(enabled = !isRestoringBackup, onClick = { pendingBackupRestore = null; backupRestorePassword = "" }) { Text("Отмена") } }
        )
    }
    if (backupResultMessage != null) {
        AlertDialog(
            onDismissRequest = { backupResultMessage = null },
            title = { Text("Резервная копия") },
            text = { Text(backupResultMessage.orEmpty()) },
            confirmButton = { TextButton(onClick = { backupResultMessage = null }) { Text("Готово") } }
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

private fun sectorForTicker(ticker: String): String = when (ticker.uppercase()) {
    "SBER", "SBERP", "T", "VTBR", "MOEX", "BSPB", "CBOM" -> "Финансы"
    "LKOH", "ROSN", "TATN", "GAZP", "NVTK", "SNGS", "SNGSP", "BANE", "TRNFP" -> "Нефть и газ"
    "OZON", "MVID", "FIXP", "X5", "MGNT", "LENT" -> "Потребительский сектор"
    "YDEX" -> "Информационные технологии"
    "MDMG", "GEMC" -> "Здравоохранение"
    "OZPH" -> "Фармацевтика"
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
internal fun DoubleHeadedEagleIcon() {
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
internal fun AssetTypeIcon(
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

internal data class SoldAssetSummary(
    val ticker: String,
    val name: String,
    val quantity: Double,
    val saleCount: Int,
    val realizedProfit: Double,
    val matchedQuantity: Double,
    val unmatchedQuantity: Double,
    val hasCompleteCostBasis: Boolean
)

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


internal fun money(value: Double): String = String.format(java.util.Locale("ru", "RU"), "%,.2f", value)

internal fun quantity(value: Double): String = String.format(java.util.Locale("ru", "RU"), "%.6f", value).trimEnd('0').trimEnd(',')


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