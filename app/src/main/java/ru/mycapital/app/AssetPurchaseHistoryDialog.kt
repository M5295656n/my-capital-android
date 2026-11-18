package ru.mycapital.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.BrokerAccountEntity

@Composable
internal fun AssetPurchaseHistoryDialog(
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
                                val accountId = historyAccountId(purchase)
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
                            val accountId = historyAccountId(sale)
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

