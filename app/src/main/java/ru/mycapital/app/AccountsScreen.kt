package ru.mycapital.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.BrokerAccountAssetEntity
import ru.mycapital.app.data.BrokerAccountCashEntity
import ru.mycapital.app.data.BrokerAccountEntity
import java.util.Locale

@Composable
internal fun AccountsScreen(
    accounts: List<BrokerAccountEntity>,
    accountCash: List<BrokerAccountCashEntity>,
    accountAssets: List<BrokerAccountAssetEntity>,
    assets: List<AssetEntity>,
    operations: List<ru.mycapital.app.data.OperationEntity>,
    payouts: List<ru.mycapital.app.data.PayoutEntity>,
    onSaveAccount: (BrokerAccountEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var editingAccount by remember { mutableStateOf<BrokerAccountEntity?>(null) }
    val assetsByTicker = remember(assets) { assets.associateBy { it.ticker.uppercase() } }
    val cashByAccount = remember(accountCash) { accountCash.associateBy { it.accountId } }
    val positionsByAccount = remember(accountAssets) { accountAssets.groupBy { it.accountId } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Счета", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Раздельные остатки по договорам из импортированных брокерских отчётов.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (accounts.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.AccountBalance, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Счета пока не добавлены", fontWeight = FontWeight.SemiBold)
                        Text("Импортируйте отчёт по основному брокерскому счёту или ИИС-3. Приложение создаст карточку договора автоматически.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(accounts, key = { it.accountId }) { account ->
                val positions = positionsByAccount[account.accountId].orEmpty()
                val cash = cashByAccount[account.accountId]?.balance ?: 0.0
                val positionValue = positions.sumOf { row ->
                    row.quantity * (assetsByTicker[row.ticker.uppercase()]?.currentPrice ?: 0.0)
                }
                val accountTradePrefix = "SBER:${account.accountId}:TRADE:"
                val accountPayoutPrefix = "SBER:${account.accountId}:PAYOUT:"
                val accountOperations = operations.filter { it.sourceKey?.startsWith(accountTradePrefix) == true }
                val realized = calculateRealizedTradingResult(accountOperations)
                val receivedPayouts = payouts.filter {
                    it.sourceKey?.startsWith(accountPayoutPrefix) == true && it.status == "RECEIVED"
                }
                val receivedPayoutIncome = receivedPayouts.sumOf {
                    it.reportedNetAmount ?: (it.totalAmount - it.taxAmount)
                }
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(account.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(account.accountType, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(onClick = { editingAccount = account }) {
                                Icon(Icons.Default.Edit, contentDescription = "Изменить название и тип счёта")
                            }
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Денежный остаток", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatMoney(cash) + " ₽", fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Стоимость бумаг", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatMoney(positionValue) + " ₽", fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Всего по договору", fontWeight = FontWeight.SemiBold)
                            Text(formatMoney(cash + positionValue) + " ₽", fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Реализованный результат FIFO", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                (if (realized.profit > 0.0) "+" else "") + formatMoney(realized.profit) + " ₽",
                                fontWeight = FontWeight.SemiBold,
                                color = if (realized.profit >= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Полученные выплаты", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatMoney(receivedPayoutIncome) + " ₽", fontWeight = FontWeight.SemiBold)
                        }
                        if (realized.unmatchedQuantity > 0.0000001) {
                            Text(
                                "Для расчёта FIFO не хватает истории покупок по ${String.format(Locale("ru", "RU"), "%.4f", realized.unmatchedQuantity)} шт. Результат частичный.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "Показатели учитывают только импортированные операции и выплаты этого договора. Нереализованная прибыль не рассчитывается отдельно по счёту, поскольку средняя цена в карточке актива общая.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("Позиции: " + positions.size, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        positions.sortedBy { it.ticker }.forEach { row ->
                            val asset = assetsByTicker[row.ticker.uppercase()]
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(row.ticker + " · " + formatQuantity(row.quantity) + " шт.")
                                Text(formatMoney(row.quantity * (asset?.currentPrice ?: 0.0)) + " ₽")
                            }
                        }
                        Text("Идентификатор договора: ••••" + account.accountId.takeLast(4), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Text("Оценка бумаг использует последние общие котировки. Распределение отражает последний импортированный снимок каждого договора; активы без привязки к договору сюда не включены.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    editingAccount?.let { account ->
        var name by remember(account.accountId) { mutableStateOf(account.displayName) }
        var type by remember(account.accountId) { mutableStateOf(account.accountType) }
        AlertDialog(
            onDismissRequest = { editingAccount = null },
            title = { Text("Настроить счёт") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true)
                    Text("Тип счёта", style = MaterialTheme.typography.labelLarge)
                    listOf("Брокерский счёт", "ИИС-3", "Другой").forEach { option ->
                        FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option) })
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    onSaveAccount(account.copy(displayName = name.trim(), accountType = type))
                    editingAccount = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editingAccount = null }) { Text("Отмена") } }
        )
    }
}

private fun formatMoney(value: Double): String =
    String.format(Locale("ru", "RU"), "%,.2f", value)

private fun formatQuantity(value: Double): String =
    String.format(Locale("ru", "RU"), "%.4f", value).trimEnd('0').trimEnd(',')
