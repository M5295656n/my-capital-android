package ru.mycapital.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.PayoutEntity

private fun dialogMoney(value: Double): String =
    String.format(java.util.Locale("ru", "RU"), "%,.2f", value)

private fun dialogQuantity(value: Double): String =
    String.format(java.util.Locale("ru", "RU"), "%.6f", value).trimEnd('0').trimEnd(',')

@Composable
internal fun ReceivePayoutDialog(
    payout: PayoutEntity,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var taxText by remember(payout.id) { mutableStateOf(dialogMoney(payout.taxAmount)) }
    val tax = parseDecimalInput(taxText) ?: -1.0
    val valid = tax.isFinite() && tax >= 0.0 && tax <= payout.totalAmount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подтвердить получение") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${payout.ticker} · ${payout.payoutType}")
                Text("Начислено: ${dialogMoney(payout.totalAmount)} ₽")
                OutlinedTextField(
                    value = taxText,
                    onValueChange = { taxText = it },
                    label = { Text("Фактически удержанный налог, ₽") },
                    singleLine = true
                )
                if (valid) Text("Будет зачислено: ${dialogMoney(payout.totalAmount - tax)} ₽")
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
internal fun AddPayoutDialog(
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
                Text("Начислено: ${dialogMoney(gross)} ₽ · К получению: ${dialogMoney((gross - tax).coerceAtLeast(0.0))} ₽",
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
internal fun CashFlowDialog(onDismiss: () -> Unit, onSave: (Boolean, Double) -> Unit) {
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
internal fun AddOperationDialog(assets: List<AssetEntity>, onDismiss: () -> Unit, onSave: (AssetEntity, Boolean, Double, Double, Double) -> Unit) {
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
internal fun EditAssetDialog(
    asset: AssetEntity,
    onDismiss: () -> Unit,
    onSave: (AssetEntity) -> Unit
) {
    var name by remember(asset.id) { mutableStateOf(asset.name) }
    var type by remember(asset.id) { mutableStateOf(asset.assetType) }
    var quantityText by remember(asset.id) { mutableStateOf(dialogQuantity(asset.quantity)) }
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
internal fun AddAssetDialog(
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
