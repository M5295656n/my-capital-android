package ru.mycapital.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.PortfolioDatabase
import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.PortfolioRepository

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
    val repository = remember { PortfolioRepository(PortfolioDatabase.getInstance(context)) }

    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF176B55))) {
        Scaffold(
            floatingActionButton = {
                if (selected == Screen.Assets || selected == Screen.Operations) {
                    FloatingActionButton(onClick = { if (selected == Screen.Assets) showAddAsset = true else showAddOperation = true }) {
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
                Screen.Overview -> OverviewScreen(assets, total, cashBalance ?: 0.0, Modifier.padding(padding))
                Screen.Assets -> AssetsScreen(assets, Modifier.padding(padding))
                Screen.Operations -> OperationsScreen(operations, onAddCashFlow = { showCashFlow = true }, modifier = Modifier.padding(padding))
                else -> PlaceholderScreen(selected, Modifier.padding(padding))
            }
        }
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
private fun OverviewScreen(assets: List<AssetEntity>, total: Double, cashBalance: Double, modifier: Modifier = Modifier) {
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
                else "Стоимость рассчитана по количеству и последней сохранённой цене. Автоматические котировки подключим отдельно.",
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
