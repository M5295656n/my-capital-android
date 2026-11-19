package ru.mycapital.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.mycapital.app.data.SberBrokerReport
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.PortfolioDatabase
import ru.mycapital.app.data.PortfolioBackup
import ru.mycapital.app.data.PayoutEntity
import ru.mycapital.app.data.PortfolioRepository

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
    val portfolioHistory = rememberPortfolioMarketHistoryState(
        assets = assets,
        operations = operations,
        currentCash = cashBalance ?: 0.0,
        brokerAccounts = brokerAccounts
    )
    val selectedHistoryRange = portfolioHistory.selectedRange
    val historyResult = portfolioHistory.result
    val historyLoading = portfolioHistory.isLoading
    val historyLoadError = portfolioHistory.error
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
    val repository = remember { PortfolioRepository(PortfolioDatabase.getInstance(context)) }
    val brokerReportImportCoordinator = remember(repository, context) {
        BrokerReportImportCoordinator(context.contentResolver, repository)
    }
    val reportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            isParsingReport = true
            scope.launch {
                try {
                    val parsed = brokerReportImportCoordinator.parseBrokerReport(uri)
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
    // Keep market state above navigation destinations so tab changes do not cancel requests.
    val dashboardMarket = rememberDashboardMarketDataState(assets)
    val dashboardMarketData = dashboardMarket.data
    val refreshDashboardMarketData = dashboardMarket.refresh
    val marketRefresh = rememberMarketRefreshState(assets)
    val isRefreshingMarketData = marketRefresh.isRefreshing
    val refreshEvents = marketRefresh.refreshEvents
    val refreshNews = marketRefresh.refreshNews
    val newsSyncMessage = marketRefresh.newsSyncMessage
    val eventSyncMessage = marketRefresh.eventSyncMessage
    val quoteSyncMessage = marketRefresh.quoteSyncMessage
    val refreshAllMarketData = marketRefresh.refreshAll

    MyCapitalTheme {
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
                Screen.Overview -> OverviewScreen(assets, total, cashBalance ?: 0.0, payouts, operations, portfolioSnapshots, selectedHistoryRange, portfolioHistory.onRangeChange, historyResult, historyLoading, historyLoadError, portfolioHistory.refresh, dashboardMarketData, refreshDashboardMarketData, refreshAllMarketData, isRefreshingMarketData, quoteSyncMessage, onOpenSettings = { selected = Screen.More }, modifier = Modifier.padding(padding))
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
                        val result = brokerReportImportCoordinator.importAndReconcile(reportToImport)
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
    AppBackupDialogs(
        pendingBackupCreate = pendingBackupCreate,
        pendingBackupRestore = pendingBackupRestore,
        createPassword = backupCreatePassword,
        createPasswordConfirm = backupCreatePasswordConfirm,
        restorePassword = backupRestorePassword,
        isCreating = isCreatingBackup,
        isRestoring = isRestoringBackup,
        onCreatePasswordChange = { backupCreatePassword = it },
        onCreatePasswordConfirmChange = { backupCreatePasswordConfirm = it },
        onRestorePasswordChange = { backupRestorePassword = it },
        onDismissCreate = {
            if (!isCreatingBackup) {
                pendingBackupCreate = null
                backupCreatePassword = ""
                backupCreatePasswordConfirm = ""
            }
        },
        onConfirmCreate = {
            val uri = pendingBackupCreate ?: return@AppBackupDialogs
            val password = backupCreatePassword
            isCreatingBackup = true
            pendingBackupCreate = null
            backupCreatePassword = ""
            backupCreatePasswordConfirm = ""
            scope.launch {
                try {
                    val info = withContext(Dispatchers.IO) {
                        PortfolioBackup.export(context, uri, password.toCharArray())
                    }
                    backupResultMessage = "Зашифрованная резервная копия AES-256 создана. Версия базы: ${info.databaseVersion}. Размер базы: ${info.databaseBytes / 1024} КБ. Пароль не сохранён — храните его отдельно."
                } catch (error: Exception) {
                    errorMessage = error.message ?: "Не удалось создать резервную копию."
                } finally {
                    isCreatingBackup = false
                }
            }
        },
        onDismissRestore = {
            if (!isRestoringBackup) {
                pendingBackupRestore = null
                backupRestorePassword = ""
            }
        },
        onConfirmRestore = {
            val uri = pendingBackupRestore ?: return@AppBackupDialogs
            val password = backupRestorePassword
            isRestoringBackup = true
            backupRestorePassword = ""
            scope.launch {
                try {
                    val info = withContext(Dispatchers.IO) {
                        PortfolioBackup.restore(context, uri, password.toCharArray())
                    }
                    pendingBackupRestore = null
                    backupResultMessage = "База успешно расшифрована, проверена и восстановлена (версия ${info.databaseVersion}). Сейчас экран приложения будет перезапущен."
                    (context as? ComponentActivity)?.recreate()
                } catch (error: Exception) {
                    errorMessage = error.message ?: "Не удалось восстановить резервную копию. Проверьте пароль и целостность архива."
                } finally {
                    isRestoringBackup = false
                }
            }
        }
    )
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
    AppTransactionDialogs(
        assets = assets,
        payoutToConfirm = payoutToConfirm,
        showAddPayout = showAddPayout,
        showCashFlow = showCashFlow,
        showAddOperation = showAddOperation,
        onDismissPayoutConfirmation = { payoutToConfirm = null },
        onConfirmPayout = { tax ->
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
        },
        onDismissAddPayout = { showAddPayout = false },
        onSavePayout = { asset, payoutType, amountPerUnit, quantity, tax, received, expectedDate ->
            scope.launch {
                try {
                    repository.recordPayout(asset?.id, asset?.ticker ?: "", payoutType, amountPerUnit, quantity, tax, received, expectedDate)
                    showAddPayout = false
                } catch (error: Exception) {
                    errorMessage = error.message ?: "Не удалось сохранить выплату"
                }
            }
        },
        onDismissCashFlow = { showCashFlow = false },
        onSaveCashFlow = { isDeposit, amount ->
            scope.launch {
                try {
                    repository.recordCashFlow(isDeposit, amount)
                    showCashFlow = false
                } catch (error: Exception) {
                    errorMessage = error.message ?: "Не удалось сохранить движение денег"
                }
            }
        },
        onDismissAddOperation = { showAddOperation = false },
        onSaveOperation = { asset, isBuy, qty, price, fee ->
            scope.launch {
                try {
                    repository.recordTrade(asset.id, isBuy, qty, price, fee)
                    showAddOperation = false
                } catch (error: Exception) {
                    errorMessage = error.message ?: "Не удалось сохранить сделку"
                }
            }
        }
    )
    AppAssetDialogs(
        assetToDelete = assetToDelete,
        assetToEdit = assetToEdit,
        showAddAsset = showAddAsset,
        onDismissDelete = { assetToDelete = null },
        onDismissEdit = { assetToEdit = null },
        onDismissAdd = { showAddAsset = false },
        onDelete = { target ->
            scope.launch {
                try {
                    dao.deleteAsset(target)
                    assetToDelete = null
                } catch (error: Exception) {
                    errorMessage = error.message ?: "Не удалось удалить актив."
                    assetToDelete = null
                }
            }
        },
        onSaveEdit = { updated ->
            scope.launch {
                try {
                    dao.updateAsset(updated)
                    assetToEdit = null
                } catch (error: Exception) {
                    errorMessage = error.message ?: "Не удалось обновить актив."
                }
            }
        },
        onSaveAdd = { ticker, name, type, quantity, average, current ->
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
