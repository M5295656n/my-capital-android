package ru.mycapital.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.SberBrokerReport
import ru.mycapital.app.data.isImportableBrokerTrade

@Composable
internal fun BrokerReportPreviewDialog(
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
internal fun BrokerReportReconciliationConfirmation(
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
