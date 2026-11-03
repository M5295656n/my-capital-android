package ru.mycapital.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
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
    Assets("Активы", Icons.Default.AccountBalanceWallet),
    Accounts("Счета", Icons.Default.AccountBalance),
    Payouts("Выплаты", Icons.Default.Payments),
    Operations("Операции", Icons.Default.SwapHoriz),
    Analytics("Аналитика", Icons.Default.ShowChart)
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
    var showAddAsset by remember { mutableStateOf(false) }
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
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                            SberBrokerReportParser.parse(reader.readText())
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
            "moex_quote_refresh_manual", ExistingWorkPolicy.KEEP, request
        )
    }
    val refreshEvents: () -> Unit = {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<CorporateEventsWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "moex_events_refresh_manual", ExistingWorkPolicy.KEEP, request
        )
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF176B55))) {
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
                    Screen.entries.forEach { screen ->
                        NavigationBarItem(
                            selected = selected == screen,
                            onClick = { selected = screen },
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title, fontSize = 10.sp) }
                        )
                    }
                }
            }
        ) { padding ->
            when (selected) {
                Screen.Overview -> OverviewScreen(assets, total, cashBalance ?: 0.0, refreshQuotes, refreshEvents, { reportPicker.launch(arrayOf("text/html", "application/xhtml+xml", "application/octet-stream")) }, isParsingReport, { exportPicker.launch("my-capital-export.zip") }, isExportingData, Modifier.padding(padding))
                Screen.Assets -> AssetsScreen(assets, Modifier.padding(padding))
                Screen.Accounts -> AccountsScreen(brokerAccounts, brokerAccountCash, brokerAccountAssets, assets, operations, payouts, { account -> scope.launch(Dispatchers.IO) { dao.upsertBrokerAccount(account) } }, Modifier.padding(padding))
                Screen.Operations -> OperationsScreen(operations, brokerAccounts, selectedOperationAccount, { selectedOperationAccount = it }, onAddCashFlow = { showCashFlow = true }, modifier = Modifier.padding(padding))
                Screen.Payouts -> PayoutsScreen(payouts, brokerAccounts, selectedPayoutAccount, { selectedPayoutAccount = it }, onConfirmReceipt = { payoutToConfirm = it }, modifier = Modifier.padding(padding))
                Screen.Analytics -> AnalyticsScreen(assets, total, cashBalance ?: 0.0, operations, payouts, portfolioSnapshots, dao, Modifier.padding(padding))
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
            onReconcile = {
                val reportToReconcile = importedReport!!
                isReconcilingReport = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { repository.reconcileBrokerReportSnapshot(reportToReconcile) }
                        importResultMessage = "Сверка договора ${reportToReconcile.accountId} завершена. Обновлено позиций: ${result.matchedAssets}. Не сопоставлено позиций из отчёта: ${result.unmatchedReportAssets}. Денежный остаток по сверенным договорам: ${money(result.resultingCash)} ₽ (до сверки: ${money(result.previousCash)} ₽). Остатки ранее сверенных договоров сохранены; средняя цена не менялась."
                        importedReport = null
                    } catch (error: Exception) {
                        importError = error.message ?: "Не удалось сверить портфель."
                    } finally {
                        isReconcilingReport = false
                    }
                }
            },
            onImportHistory = {
                val reportToImport = importedReport!!
                isImportingReport = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { repository.importBrokerReportHistory(reportToImport) }
                        importResultMessage = "Добавлено операций: ${result.imported}. Выплат добавлено: ${result.payoutsImported}. Дубликатов пропущено: ${result.duplicates}. Неисполненных или непроверенных сделок пропущено: ${result.unsettledSkipped}. Денежный остаток и позиции не менялись."
                        importedReport = null
                    } catch (error: Exception) {
                        importError = error.message ?: "Не удалось импортировать операции."
                    } finally {
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
            onSave = { asset, payoutType, amountPerUnit, quantity, tax, received ->
                scope.launch {
                    try {
                        repository.recordPayout(asset?.id, asset?.ticker ?: "", payoutType, amountPerUnit, quantity, tax, received)
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
private fun OverviewScreen(assets: List<AssetEntity>, total: Double, cashBalance: Double, onRefreshQuotes: () -> Unit, onRefreshEvents: () -> Unit, onImportReport: () -> Unit, isParsingReport: Boolean, onExportData: () -> Unit, isExportingData: Boolean, modifier: Modifier = Modifier) {
    val assetCount = assets.size
    val portfolioTotal = total + cashBalance
    val invested = assets.sumOf { it.quantity * it.averagePrice }
    val unrealized = total - invested
    val returnPercent = if (invested > 0.0) unrealized / invested * 100.0 else 0.0
    val allocation = calculatePortfolioAllocation(assets, cashBalance)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Мой капитал", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Ваш инвестиционный портфель", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onRefreshQuotes) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Обновить котировки MOEX")
            }
            OutlinedButton(onClick = onRefreshEvents) {
                Icon(Icons.Default.Event, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Обновить дивиденды и купоны")
            }
            OutlinedButton(onClick = onImportReport, enabled = !isParsingReport) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isParsingReport) "Читаю отчёт…" else "Импорт отчёта Сбербанка (HTML)")
            }
            OutlinedButton(onClick = onExportData, enabled = !isExportingData) {
                Icon(Icons.Default.FileDownload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isExportingData) "Экспортирую данные…" else "Экспортировать данные (ZIP)")
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Column(Modifier.padding(22.dp)) {
                    Text("Общая стоимость портфеля", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .8f))
                    Spacer(Modifier.height(8.dp))
                    Text("${money(portfolioTotal)} ₽", color = MaterialTheme.colorScheme.onPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("Активов в портфеле: $assetCount", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .8f))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("Себестоимость остатков", money(invested) + " ₽", Modifier.weight(1f))
                MetricCard("Нереализованный результат", (if (unrealized > 0) "+" else "") + money(unrealized) + " ₽", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("Результат к себестоимости", String.format(java.util.Locale("ru", "RU"), "%.2f%%", returnPercent), Modifier.weight(1f))
                MetricCard("Свободные деньги", money(cashBalance) + " ₽", Modifier.weight(1f))
            }
        }
        item {
            Text("Структура портфеля", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (allocation.isEmpty()) {
                Text("Распределение появится после добавления активов или свободных денег.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    allocation.forEach { slice ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(slice.label, fontWeight = FontWeight.Medium)
                                Text(
                                    "${money(slice.value)} ₽ · ${String.format(java.util.Locale("ru", "RU"), "%.1f%%", slice.share * 100.0)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            LinearProgressIndicator(
                                progress = { slice.share.toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
        item {
            Text("Портфель", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (assetCount == 0) "Добавьте первый актив во вкладке «Активы»."
                else "Стоимость рассчитана по последней сохранённой цене. Котировки MOEX могут иметь задержку до 15 минут.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AssetsScreen(assets: List<AssetEntity>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Активы", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Локальный список вашего портфеля", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Добавление актива задаёт начальную позицию и не списывает деньги. Для новых покупок сначала внесите свободные деньги.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (assets.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Пока нет активов. Нажмите +, чтобы добавить первый.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                items(assets, key = { it.id }) { asset ->
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(asset.ticker, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(asset.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${asset.assetType} · ${asset.quantity} шт.")
                            }
                            Column(horizontalAlignment = Alignment.End) {
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
    val visibleOperations = operations.filter { operation ->
        when (selectedAccountId) {
            ALL_HISTORY_ACCOUNTS -> true
            UNASSIGNED_HISTORY_ACCOUNT -> historyAccountId(operation.sourceKey) == null
            else -> historyAccountId(operation.sourceKey) == selectedAccountId
        }
    }
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Text("Операции", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("История сделок и движения денег", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        AccountHistoryFilter(accounts, selectedAccountId, onAccountSelected)
        TextButton(onClick = onAddCashFlow) { Text("Пополнить / вывести деньги") }
        Spacer(Modifier.height(12.dp))
        if (visibleOperations.isEmpty()) Text(if (selectedAccountId == ALL_HISTORY_ACCOUNTS) "Пока нет операций. Нажмите +, чтобы добавить сделку." else "Для выбранного счёта операций нет.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleOperations, key = { it.id }) { op ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((op.operationType) + " · " + (op.ticker ?: "—"), fontWeight = FontWeight.SemiBold)
                            Text(op.quantity.toString() + " × " + money(op.price) + " ₽", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Text("Выплаты", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Дивиденды и купоны: ожидаемые отдельно от полученных", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        AccountHistoryFilter(accounts, selectedAccountId, onAccountSelected)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Получено после налога", money(received) + " ₽", Modifier.weight(1f))
            MetricCard("Ожидается до налога", money(expected) + " ₽", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        if (visiblePayouts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (selectedAccountId == ALL_HISTORY_ACCOUNTS) "Пока нет выплат. Нажмите +, чтобы добавить дивиденд или купон." else "Для выбранного счёта выплат нет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                items(visiblePayouts, key = { it.id }) { payout ->
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
    onSave: (AssetEntity?, String, Double, Double, Double, Boolean) -> Unit
) {
    var assetId by remember { mutableStateOf(assets.firstOrNull()?.id ?: 0L) }
    var payoutType by remember { mutableStateOf("Дивиденд") }
    var amountText by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("1") }
    var taxText by remember { mutableStateOf("0") }
    var received by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val asset = assets.firstOrNull { it.id == assetId }
    val amount = parseDecimalInput(amountText) ?: 0.0
    val quantity = parseDecimalInput(quantityText) ?: 0.0
    val tax = parseDecimalInput(taxText) ?: -1.0
    val gross = amount * quantity
    val valid = asset != null && amount > 0.0 && quantity > 0.0 && tax >= 0.0 && tax <= gross
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = received, onCheckedChange = { received = it })
                    Text("Уже фактически получено")
                }
                Text("Начислено: ${money(gross)} ₽ · К получению: ${money((gross - tax).coerceAtLeast(0.0))} ₽",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(asset, payoutType, amount, quantity, tax, received) }) { Text("Сохранить") }
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
    val ordered = snapshots.sortedBy { it.dateKey }
    // Unitized NAV excludes net external cash flows from each daily return factor.
    // Approximation: cash flows are treated as if they occurred at the end of the day.
    val navValues = mutableListOf<Double>()
    var nav = 100.0
    ordered.forEachIndexed { index, snapshot ->
        if (index > 0) {
            val previousValue = ordered[index - 1].totalValue
            val valueBeforeEndOfDayFlow = snapshot.totalValue - snapshot.externalFlow
            if (valueBeforeEndOfDayFlow > 0.0 && previousValue > 0.0) {
                nav *= valueBeforeEndOfDayFlow / previousValue
            }
        }
        navValues += nav
    }
    val values = navValues.map { it.toFloat() }
    val minValue = values.minOrNull() ?: 100f
    val maxValue = values.maxOrNull() ?: 100f
    val range = (maxValue - minValue).takeIf { it > 0.0001f } ?: 1f
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val totalReturn = (navValues.lastOrNull() ?: 100.0) - 100.0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Доходность с начала: ${if (totalReturn >= 0) "+" else ""}${String.format(java.util.Locale("ru", "RU"), "%.2f%%", totalReturn)}",
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text("База = 100", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(String.format(java.util.Locale("ru", "RU"), "%.2f", minValue), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(String.format(java.util.Locale("ru", "RU"), "%.2f", maxValue), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (values.size >= 2) {
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = left + (right - left) * index / (values.size - 1).toFloat()
                    val y = bottom - ((value - minValue) / range) * (bottom - top)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, lineColor, style = Stroke(width = 4f, cap = StrokeCap.Round))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(ordered.first().dateKey, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(ordered.last().dateKey, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    onReconcile: () -> Unit,
    onImportHistory: () -> Unit
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
                Text("Будет изменено количество и текущая цена только у уже известных приложению бумаг. Средняя цена покупки останется прежней.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                knownRows.take(20).forEach { (row, current) ->
                    Text("${row.ticker}: по этому договору ${quantity(row.closingQuantity)} шт.; всего в приложении сейчас ${quantity(current!!.quantity)} шт.", fontSize = 12.sp)
                }
                if (knownRows.size > 20) Text("И ещё ${knownRows.size - 20} сопоставленных позиций…", fontSize = 12.sp)
                if (unknownRows.isNotEmpty()) {
                    Text("Не будут изменены: ${unknownRows.size} поз. без совпадения тикера в приложении.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
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
                    "Импорт истории и сверка остатков — разные действия. Сверка не импортирует сделки, не меняет среднюю цену и не удаляет активы, которых нет в отчёте.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = { showReconcileConfirmation = true },
                    enabled = !isImporting && !isReconciling && report.closingCash >= 0.0 &&
                        report.assets.any { it.ticker.isNotBlank() } &&
                        kotlin.math.abs(report.openingCash + report.cashCredits - report.cashDebits - report.closingCash) <= 0.02
                ) {
                    Text(if (isReconciling) "Сверяю…" else "Сверить остатки и позиции")
                }
                TextButton(
                    onClick = onImportHistory,
                    enabled = !isImporting && !isReconciling && (report.cashFlows.isNotEmpty() || completedTrades > 0)
                ) {
                    Text(if (isImporting) "Импортирую…" else "Импортировать историю")
                }
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
                onReconcile()
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
                "Приложение сохранит остаток договора ${report.accountId} и пересчитает общий остаток по всем уже сверенным договорам. Первый договор задаёт начальную базу, последующие добавляются; повторная сверка заменяет только снимок этого договора. Количества позиций также пересчитываются по разнице с предыдущим снимком договора. Средняя цена покупки не изменится; бумаги, не сопоставленные с тикером в приложении, не будут изменены."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Подтвердить сверку") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Назад") } }
    )
}
