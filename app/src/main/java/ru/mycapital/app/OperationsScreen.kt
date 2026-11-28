package ru.mycapital.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.BrokerAccountEntity

internal fun sortOperationsChronologically(operations: List<OperationEntity>): List<OperationEntity> =
    operations .let(::sortOperationsChronologically)

private fun operationMoney(value: Double): String =
    String.format(java.util.Locale("ru", "RU"), "%,.2f", value)

private fun operationQuantity(value: Double): String =
    String.format(java.util.Locale("ru", "RU"), "%.6f", value).trimEnd('0').trimEnd(',')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountHistoryFilter(
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
internal fun OperationsScreen(
    operations: List<OperationEntity>,
    accounts: List<ru.mycapital.app.data.BrokerAccountEntity>,
    selectedAccountId: String,
    onAccountSelected: (String) -> Unit,
    onAddCashFlow: () -> Unit,
    modifier: Modifier = Modifier
) {
    var operationTypeFilter by remember { mutableStateOf("Все") }
    var searchQuery by remember { mutableStateOf("") }
    val visibleOperations = remember(operations, selectedAccountId, operationTypeFilter, searchQuery) {
        operations.filter { operation ->
            val matchesAccount = when (selectedAccountId) {
                ALL_HISTORY_ACCOUNTS -> true
                UNASSIGNED_HISTORY_ACCOUNT -> historyAccountId(operation) == null
                else -> historyAccountId(operation) == selectedAccountId
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
        }.sortedWith(compareBy<OperationEntity> { it.operationDate }.thenBy { it.id })
    }
    val purchases = visibleOperations.count { it.operationType == "Покупка" }
    val sales = visibleOperations.count { it.operationType == "Продажа" }
    val cashFlows = visibleOperations.count { it.operationType == "Пополнение" || it.operationType == "Вывод" }
    val groupedOperations = remember(visibleOperations) {
        val formatter = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale("ru", "RU"))
        visibleOperations.groupBy { formatter.format(java.util.Date(it.operationDate)) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("История операций", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Все движения капитала в одном месте", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("Всего", visibleOperations.size.toString(), MaterialTheme.colorScheme.primary),
                    Triple("Покупки", purchases.toString(), MaterialTheme.colorScheme.tertiary),
                    Triple("Продажи", sales.toString(), MaterialTheme.colorScheme.error)
                ).forEach { (label, value, tint) ->
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tint)
                        }
                    }
                }
            }
        }
        item {
            AccountHistoryFilter(accounts, selectedAccountId, onSelect = onAccountSelected)
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск по тикеру, типу или примечанию") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Очистить поиск")
                        }
                    }
                }
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Все", "Сделки", "Деньги", "Выплаты").forEach { filter ->
                    FilterChip(
                        selected = operationTypeFilter == filter,
                        onClick = { operationTypeFilter = filter },
                        label = { Text(filter, maxLines = 1) }
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Операции", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Найдено: ${visibleOperations.size} · Движения денег: $cashFlows", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onAddCashFlow) {
                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Деньги")
                }
            }
        }
        if (visibleOperations.isEmpty()) {
            item {
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (operations.isEmpty()) "История пока пуста" else "Операций не найдено", fontWeight = FontWeight.SemiBold)
                        Text(if (operations.isEmpty()) "Нажмите «+», чтобы добавить первую сделку." else "Измените фильтр или поисковый запрос.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            groupedOperations.forEach { (date, dayOperations) ->
                item(key = "operation-date-$date") {
                    Text(date.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale("ru", "RU")) else it.toString() }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(dayOperations, key = { "operation-${it.id}" }) { op ->
                    val isBuy = op.operationType == "Покупка"
                    val isSell = op.operationType == "Продажа"
                    val isIncome = op.operationType == "Пополнение" || op.operationType == "Дивиденд" || op.operationType == "Купон"
                    val amount = op.quantity * op.price
                    val displayed = when (op.operationType) {
                        "Покупка" -> amount + op.commission
                        "Продажа" -> amount - op.commission
                        else -> amount
                    }
                    val amountColor = when {
                        isBuy -> MaterialTheme.colorScheme.error
                        isSell -> MaterialTheme.colorScheme.secondary
                        isIncome -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(17.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = RoundedCornerShape(13.dp),
                                color = when {
                                    isBuy -> MaterialTheme.colorScheme.errorContainer
                                    isSell -> MaterialTheme.colorScheme.secondaryContainer
                                    isIncome -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        when {
                                            isBuy -> Icons.Default.TrendingDown
                                            isSell -> Icons.Default.TrendingUp
                                            op.operationType == "Вывод" -> Icons.Default.ArrowUpward
                                            else -> Icons.Default.SwapHoriz
                                        },
                                        contentDescription = null,
                                        tint = when {
                                            isBuy -> MaterialTheme.colorScheme.onErrorContainer
                                            isSell -> MaterialTheme.colorScheme.onSecondaryContainer
                                            isIncome -> MaterialTheme.colorScheme.onPrimaryContainer
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(op.operationType + " · " + (op.ticker ?: "Деньги"), fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(
                                    if (op.quantity > 0.0) "${operationQuantity(op.quantity)} × ${operationMoney(op.price)} ₽" else op.note.ifBlank { "Денежная операция" },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                                if (op.commission > 0.0) {
                                    Text("Комиссия: ${operationMoney(op.commission)} ₽", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                (if (isBuy) "−" else if (isSell || isIncome) "+" else "") + operationMoney(displayed) + " ₽",
                                fontWeight = FontWeight.Bold,
                                color = amountColor,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
