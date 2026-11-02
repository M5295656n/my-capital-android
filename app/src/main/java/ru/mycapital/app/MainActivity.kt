package ru.mycapital.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
import ru.mycapital.app.data.PortfolioDatabase
import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.PayoutEntity
import ru.mycapital.app.data.PortfolioRepository
import ru.mycapital.app.data.PortfolioSnapshotEntity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyCapitalApp() }
    }
}

private enum class Screen(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Overview("Обзор", Icons.Default.Home),
    Assets("Активы", Icons.Default.AccountBalanceWallet),
    Payouts("Выплаты", Icons.Default.Payments),
    Operations("Операции", Icons.Default.SwapHoriz),
    Analytics("Аналитика", Icons.Default.ShowChart)
}

@Composable
private fun MyCapitalApp() {
    val context = LocalContext.current
    val dao = remember { PortfolioDatabase.getInstance(context).portfolioDao() }
    val assets by dao.observeAssets().collectAsState(initial = emptyList())
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
    val portfolioSnapshots by dao.observePortfolioSnapshots().collectAsState(initial = emptyList())
    var showAddPayout by remember { mutableStateOf(false) }
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
                Screen.Overview -> OverviewScreen(assets, total, cashBalance ?: 0.0, refreshQuotes, refreshEvents, Modifier.padding(padding))
                Screen.Assets -> AssetsScreen(assets, Modifier.padding(padding))
                Screen.Operations -> OperationsScreen(operations, onAddCashFlow = { showCashFlow = true }, modifier = Modifier.padding(padding))
                Screen.Payouts -> PayoutsScreen(payouts, Modifier.padding(padding))
                Screen.Analytics -> AnalyticsScreen(assets, total, cashBalance ?: 0.0, portfolioSnapshots, Modifier.padding(padding))
                else -> PlaceholderScreen(selected, Modifier.padding(padding))
            }
        }
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
private fun OverviewScreen(assets: List<AssetEntity>, total: Double, cashBalance: Double, onRefreshQuotes: () -> Unit, onRefreshEvents: () -> Unit, modifier: Modifier = Modifier) {
    val assetCount = assets.size
    val portfolioTotal = total + cashBalance
    val invested = assets.sumOf { it.quantity * it.averagePrice }
    val unrealized = total - invested
    val returnPercent = if (invested > 0.0) unrealized / invested * 100.0 else 0.0
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

@Composable
private fun OperationsScreen(operations: List<OperationEntity>, onAddCashFlow: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Text("Операции", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("История сделок и движения денег", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAddCashFlow) { Text("Пополнить / вывести деньги") }
        Spacer(Modifier.height(12.dp))
        if (operations.isEmpty()) Text("Пока нет операций. Нажмите +, чтобы добавить сделку.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(operations, key = { it.id }) { op ->
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
private fun PayoutsScreen(payouts: List<PayoutEntity>, modifier: Modifier = Modifier) {
    val received = payouts.filter { it.status == "RECEIVED" }.sumOf { it.totalAmount - it.taxAmount }
    val expected = payouts.filter { it.status == "EXPECTED" }.sumOf { it.totalAmount }
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Text("Выплаты", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Дивиденды и купоны: ожидаемые отдельно от полученных", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Получено после налога", money(received) + " ₽", Modifier.weight(1f))
            MetricCard("Ожидается до налога", money(expected) + " ₽", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        if (payouts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Пока нет выплат. Нажмите +, чтобы добавить дивиденд или купон.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                items(payouts, key = { it.id }) { payout ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(payout.ticker + " · " + payout.payoutType, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(if (payout.status == "RECEIVED") "Получено" else "Ожидается",
                                    color = if (payout.status == "RECEIVED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("Начислено: ${money(payout.totalAmount)} ₽ · Налог: ${money(payout.taxAmount)} ₽")
                            Text(if (payout.status == "RECEIVED") "На счёт: ${money(payout.totalAmount - payout.taxAmount)} ₽" else "Ожидаемая сумма, ещё не включена в свободные деньги")
                            payout.expectedDate?.let { Text("Дата события: " + java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru", "RU")).format(java.util.Date(it)), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
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
    val amount = amountText.replace(',', '.').toDoubleOrNull() ?: 0.0
    val quantity = quantityText.replace(',', '.').toDoubleOrNull() ?: 0.0
    val tax = taxText.replace(',', '.').toDoubleOrNull() ?: -1.0
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
    val amount = amountText.toDoubleOrNull() ?: 0.0
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
    val qty = qtyText.toDoubleOrNull() ?: 0.0
    val price = priceText.toDoubleOrNull() ?: -1.0
    val fee = feeText.toDoubleOrNull() ?: -1.0
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
        (quantity.toDoubleOrNull() ?: 0.0) > 0 &&
        (average.toDoubleOrNull() ?: -1.0) >= 0 &&
        (current.toDoubleOrNull() ?: -1.0) >= 0

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
    snapshots: List<PortfolioSnapshotEntity>,
    modifier: Modifier = Modifier
) {
    val invested = assets.sumOf { it.quantity * it.averagePrice }
    val unrealized = marketValue - invested
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
                    Text("Расчёт не включает полученные дивиденды и купоны.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            IndexBenchmarkSection(snapshots)
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
            val adjustedBase = previousValue + snapshot.externalFlow
            if (adjustedBase > 0.0 && previousValue > 0.0) {
                nav *= snapshot.totalValue / adjustedBase
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
@Composable
private fun PlaceholderScreen(screen: Screen, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(screen.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
            Text(screen.title, style = MaterialTheme.typography.headlineSmall)
            Text("Раздел будет реализован следующим этапом", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun money(value: Double): String = String.format(java.util.Locale("ru", "RU"), "%,.2f", value)
