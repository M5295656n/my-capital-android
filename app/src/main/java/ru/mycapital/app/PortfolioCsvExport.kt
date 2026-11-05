package ru.mycapital.app

import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.BrokerAccountAssetEntity
import ru.mycapital.app.data.BrokerAccountCashEntity
import ru.mycapital.app.data.BrokerAccountEntity
import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.PayoutEntity
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a portable CSV archive. Broker agreement identifiers and raw source keys
 * are intentionally excluded; rows are linked by the user-defined account name.
 */
internal fun writePortfolioExportZip(
    output: OutputStream,
    assets: List<AssetEntity>,
    operations: List<OperationEntity>,
    payouts: List<PayoutEntity>,
    accounts: List<BrokerAccountEntity>,
    accountCash: List<BrokerAccountCashEntity>,
    accountAssets: List<BrokerAccountAssetEntity>,
    cashBalance: Double
) {
    val accountNames = accounts.associate { it.accountId to it.displayName }
    ZipOutputStream(output).use { zip ->
        zip.writeCsv("assets.csv", buildString {
            appendCsvRow("Тикер", "Название", "Тип актива", "Количество", "Средняя цена", "Текущая цена", "Валюта", "Стоимость")
            assets.sortedBy { it.ticker }.forEach { asset ->
                appendCsvRow(asset.ticker, asset.name, asset.assetType, asset.quantity, asset.averagePrice,
                    asset.currentPrice, asset.currency, asset.quantity * asset.currentPrice)
            }
        })
        zip.writeCsv("broker_accounts.csv", buildString {
            appendCsvRow("Название счёта", "Тип счёта", "Идентификатор", "Денежный остаток")
            accounts.sortedBy { it.displayName }.forEach { account ->
                val masked = "••••" + account.accountId.takeLast(4)
                val balance = accountCash.firstOrNull { it.accountId == account.accountId }?.balance ?: 0.0
                appendCsvRow(account.displayName, account.accountType, masked, balance)
            }
            appendCsvRow("Общий остаток", "Все счета", "", cashBalance)
        })
        zip.writeCsv("broker_account_positions.csv", buildString {
            appendCsvRow("Название счёта", "Тикер", "Количество")
            accountAssets.sortedWith(compareBy({ accountNames[it.accountId].orEmpty() }, { it.ticker })).forEach { row ->
                appendCsvRow(accountNames[row.accountId] ?: ("Договор ••••" + row.accountId.takeLast(4)), row.ticker, row.quantity)
            }
        })
        zip.writeCsv("operations.csv", buildString {
            appendCsvRow("Дата", "Счёт", "Тип операции", "Тикер", "Количество", "Цена", "Комиссия", "Примечание")
            operations.sortedBy { it.operationDate }.forEach { operation ->
                appendCsvRow(
                    exportDate(operation.operationDate),
                    exportAccountName(operation.sourceKey, accountNames),
                    operation.operationType,
                    operation.ticker.orEmpty(),
                    operation.quantity,
                    operation.price,
                    operation.commission,
                    operation.note
                )
            }
        })
        zip.writeCsv("payouts.csv", buildString {
            appendCsvRow("Тикер", "Счёт", "Тип выплаты", "На единицу", "Количество", "Начислено",
                "Налог", "Чистая сумма из отчёта", "Ожидаемая дата", "Дата отсечки", "Источник", "Дата получения", "Статус")
            payouts.sortedBy { it.actualDate ?: it.expectedDate ?: Long.MAX_VALUE }.forEach { payout ->
                appendCsvRow(
                    payout.ticker,
                    exportAccountName(payout.sourceKey, accountNames),
                    payout.payoutType,
                    payout.amountPerUnit,
                    payout.quantity,
                    payout.totalAmount,
                    payout.taxAmount,
                    payout.reportedNetAmount?.toString().orEmpty(),
                    payout.expectedDate?.let(::exportDate).orEmpty(),
                    payout.recordDate?.let(::exportDate).orEmpty(),
                    payout.sourceName.orEmpty(),
                    payout.actualDate?.let(::exportDate).orEmpty(),
                    payout.status
                )
            }
        })
    }
}

internal fun escapeCsvField(value: String): String =
    "\"" + value.replace("\"", "\"\"") + "\""

private fun StringBuilder.appendCsvRow(vararg values: Any?) {
    append(values.joinToString(";") { escapeCsvField(it?.toString().orEmpty()) })
    append("\r\n")
}

private fun ZipOutputStream.writeCsv(name: String, content: String) {
    putNextEntry(ZipEntry(name))
    write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
    write(content.toByteArray(Charsets.UTF_8))
    closeEntry()
}

private fun exportDate(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString()

private fun exportAccountName(sourceKey: String?, accountNames: Map<String, String>): String {
    val parts = sourceKey?.split(':') ?: return "Без привязки"
    if (parts.size >= 3 && parts[0] == "SBER") {
        val accountId = parts[1]
        return accountNames[accountId] ?: ("Договор ••••" + accountId.takeLast(4))
    }
    return "Без привязки"
}
