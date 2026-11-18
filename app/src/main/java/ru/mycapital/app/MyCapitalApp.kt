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
import ru.mycapital.app.data.MarketCalendarItemEntity
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
internal fun MyCapitalApp() {
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
    val marketRefresh = rememberMarketRefreshState(assets)
    val isRefreshingMarketData = marketRefresh.isRefreshing
    val refreshQuotes = marketRefresh.refreshQuotes
    val refreshEvents = marketRefresh.refreshEvents
    val refreshNews = marketRefresh.refreshNews
    val newsSyncMessage = marketRefresh.newsSyncMessage
    val eventSyncMessage = marketRefresh.eventSyncMessage
    val refreshAllMarketData = marketRefresh.refreshAll

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
            bottomBar = { if (selectedAssetDetail == null) AppBottomNavigationBar(selected) { selected = it } }
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
    AppMessageDialogs(
        backupResultMessage = backupResultMessage,
        exportResultMessage = exportResultMessage,
        importResultMessage = importResultMessage,
        importError = importError,
        errorMessage = errorMessage,
        onDismissBackup = { backupResultMessage = null },
        onDismissExport = { exportResultMessage = null },
        onDismissImport = { importResultMessage = null },
        onDismissImportError = { importError = null },
        onDismissError = { errorMessage = null }
    )
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
