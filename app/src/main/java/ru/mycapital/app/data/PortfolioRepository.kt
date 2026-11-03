package ru.mycapital.app.data

import androidx.room.withTransaction
import java.time.ZoneOffset

internal fun isFullyExecutedBrokerTrade(status: String): Boolean = status.trim() == "И"

class PortfolioRepository(private val database: PortfolioDatabase) {
    private val dao = database.portfolioDao()


    data class BrokerHistoryImportResult(val imported: Int, val duplicates: Int, val unsettledSkipped: Int)


    data class BrokerSnapshotReconciliationResult(
        val matchedAssets: Int,
        val unmatchedReportAssets: Int,
        val previousCash: Double,
        val reportCash: Double
    )

    suspend fun reconcileBrokerReportSnapshot(report: SberBrokerReport): BrokerSnapshotReconciliationResult {
        var matched = 0
        var unmatched = 0
        var previousCash = 0.0
        database.withTransaction {
            dao.ensureCashBalance(CashBalanceEntity())
            previousCash = dao.getCashBalance() ?: 0.0
            report.assets.forEach { row ->
                val ticker = row.ticker.trim().uppercase()
                val asset = ticker.takeIf { it.isNotBlank() }?.let { dao.getAssetByTicker(it) }
                if (asset == null) {
                    unmatched++
                } else {
                    dao.updateAsset(
                        asset.copy(
                            quantity = row.closingQuantity,
                            currentPrice = row.closingPrice.takeIf { it > 0.0 } ?: asset.currentPrice,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    matched++
                }
            }
            // Statement closing cash is an absolute balance, not a cash flow.
            // Set it directly; never add it to the app's existing balance.
            dao.updateCashBalance(report.closingCash)
        }
        return BrokerSnapshotReconciliationResult(
            matchedAssets = matched,
            unmatchedReportAssets = unmatched,
            previousCash = previousCash,
            reportCash = report.closingCash
        )
    }

    suspend fun importBrokerReportHistory(report: SberBrokerReport): BrokerHistoryImportResult {
        var imported = 0
        var duplicates = 0
        var unsettledSkipped = 0
        database.withTransaction {
            val assetsByTicker = dao.getAssets().associateBy { it.ticker.uppercase() }
            report.cashFlows.forEach { flow ->
                val amount = flow.credit.takeIf { it > 0.0 } ?: flow.debit
                if (amount <= 0.0) return@forEach
                val isDeposit = flow.credit > 0.0
                val row = OperationEntity(
                    assetId = null,
                    operationType = if (isDeposit) "Пополнение" else "Вывод",
                    ticker = "RUB",
                    quantity = 1.0,
                    price = amount,
                    commission = 0.0,
                    operationDate = flow.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                    note = "Импорт из брокерского отчёта: ${flow.description}",
                    sourceKey = flow.rowKey
                )
                val id = dao.insertImportedOperation(row)
                if (id == -1L) duplicates++ else imported++
            }
            report.trades.forEach { trade ->
                // Only the explicit fully executed status is imported. Other codes may
                // represent unsettled, partially settled or pending obligations.
                if (!isFullyExecutedBrokerTrade(trade.status)) {
                    unsettledSkipped++
                    return@forEach
                }
                val isBuy = trade.side.contains("Покуп", ignoreCase = true)
                val isSell = trade.side.contains("Прод", ignoreCase = true)
                if (!isBuy && !isSell) {
                    unsettledSkipped++
                    return@forEach
                }
                val row = OperationEntity(
                    assetId = assetsByTicker[trade.ticker.uppercase()]?.id,
                    operationType = if (isBuy) "Покупка" else "Продажа",
                    ticker = trade.ticker.uppercase(),
                    quantity = trade.quantity,
                    price = trade.price,
                    commission = trade.totalFee,
                    operationDate = trade.tradeDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                    note = "Импорт из брокерского отчёта; сделка № ${trade.dealNumber}",
                    sourceKey = trade.sourceKey
                )
                val id = dao.insertImportedOperation(row)
                if (id == -1L) duplicates++ else imported++
            }
        }
        return BrokerHistoryImportResult(imported, duplicates, unsettledSkipped)
    }

    suspend fun recordTrade(
        assetId: Long,
        isBuy: Boolean,
        quantity: Double,
        price: Double,
        commission: Double = 0.0,
        note: String = ""
    ) {
        require(quantity > 0.0) { "Количество должно быть больше нуля" }
        require(price >= 0.0) { "Цена не может быть отрицательной" }
        require(commission >= 0.0) { "Комиссия не может быть отрицательной" }

        database.withTransaction {
            dao.ensureCashBalance(CashBalanceEntity())
            val cash = dao.getCashBalance() ?: 0.0
            val grossAmount = quantity * price

            val nextCash = if (isBuy) {
                val totalCost = grossAmount + commission
                require(cash + 0.0000001 >= totalCost) {
                    "Недостаточно свободных денег. Нужно ${"%.2f".format(java.util.Locale.US, totalCost)} ₽, доступно ${"%.2f".format(java.util.Locale.US, cash)} ₽"
                }
                (cash - totalCost).coerceAtLeast(0.0)
            } else {
                val proceeds = grossAmount - commission
                require(proceeds >= 0.0) { "Комиссия не может превышать сумму продажи" }
                cash + proceeds
            }

            val asset = requireNotNull(dao.getAsset(assetId)) { "Актив не найден" }
            val nextQuantity = if (isBuy) asset.quantity + quantity else asset.quantity - quantity
            require(nextQuantity >= -0.0000001) { "Недостаточно бумаг для продажи" }

            val nextAverage = when {
                nextQuantity <= 0.0000001 -> 0.0
                isBuy -> ((asset.quantity * asset.averagePrice) + grossAmount + commission) / nextQuantity
                else -> asset.averagePrice
            }

            dao.updateCashBalance(nextCash)
            dao.updateAsset(
                asset.copy(
                    quantity = nextQuantity.coerceAtLeast(0.0),
                    averagePrice = nextAverage,
                    currentPrice = price,
                    updatedAt = System.currentTimeMillis()
                )
            )
            dao.insertOperation(
                OperationEntity(
                    assetId = asset.id,
                    operationType = if (isBuy) "Покупка" else "Продажа",
                    ticker = asset.ticker,
                    quantity = quantity,
                    price = price,
                    commission = commission,
                    note = note
                )
            )
        }
    }

    suspend fun recordCashFlow(isDeposit: Boolean, amount: Double, note: String = "") {
        require(amount > 0.0) { "Сумма должна быть больше нуля" }
        database.withTransaction {
            dao.ensureCashBalance(CashBalanceEntity())
            val current = dao.getCashBalance() ?: 0.0
            val next = if (isDeposit) current + amount else current - amount
            require(next >= -0.0000001) { "Недостаточно свободных денег" }
            dao.updateCashBalance(next.coerceAtLeast(0.0))
            dao.insertOperation(
                OperationEntity(
                    assetId = null,
                    operationType = if (isDeposit) "Пополнение" else "Вывод",
                    ticker = "RUB",
                    quantity = 1.0,
                    price = amount,
                    commission = 0.0,
                    note = note
                )
            )
        }
    }

    /**
     * Confirms an existing expected payout. The transaction and status guard make
     * cash crediting idempotent: a payout can be credited only once.
     */
    suspend fun confirmPayoutReceived(payoutId: Long, taxAmount: Double, receivedAt: Long = System.currentTimeMillis()): Boolean {
        require(taxAmount.isFinite() && taxAmount >= 0.0) { "Налог не может быть отрицательным" }
        return database.withTransaction {
            val payout = requireNotNull(dao.getPayoutById(payoutId)) { "Выплата не найдена" }
            if (payout.status == "RECEIVED") return@withTransaction false
            require(payout.status == "EXPECTED") { "Можно подтвердить только ожидаемую выплату" }
            val net = calculatePayoutNetAmount(payout.totalAmount, taxAmount)
            dao.updatePayout(payout.copy(taxAmount = taxAmount, actualDate = receivedAt, status = "RECEIVED"))
            dao.ensureCashBalance(CashBalanceEntity())
            val current = dao.getCashBalance() ?: 0.0
            dao.updateCashBalance(current + net)
            dao.insertOperation(
                OperationEntity(
                    assetId = payout.assetId,
                    operationType = payout.payoutType,
                    ticker = payout.ticker,
                    quantity = payout.quantity,
                    price = payout.amountPerUnit,
                    commission = taxAmount,
                    operationDate = receivedAt,
                    note = "Фактически получено; после налога: ${"%.2f".format(java.util.Locale.US, net)} ₽"
                )
            )
            true
        }
    }

    suspend fun recordPayout(
        assetId: Long?,
        ticker: String,
        payoutType: String,
        amountPerUnit: Double,
        quantity: Double,
        taxAmount: Double,
        isReceived: Boolean
    ) {
        require(ticker.isNotBlank()) { "Укажите тикер" }
        require(payoutType == "Дивиденд" || payoutType == "Купон") { "Неизвестный тип выплаты" }
        require(amountPerUnit > 0.0) { "Сумма на единицу должна быть больше нуля" }
        require(quantity > 0.0) { "Количество должно быть больше нуля" }
        val gross = amountPerUnit * quantity
        require(taxAmount >= 0.0 && taxAmount <= gross) { "Налог должен быть от нуля до суммы выплаты" }
        val now = System.currentTimeMillis()

        database.withTransaction {
            dao.insertPayout(
                PayoutEntity(
                    assetId = assetId,
                    ticker = ticker.trim().uppercase(),
                    payoutType = payoutType,
                    amountPerUnit = amountPerUnit,
                    quantity = quantity,
                    totalAmount = gross,
                    taxAmount = taxAmount,
                    // No expected payment date is collected by the current form; do not invent one.
                    expectedDate = null,
                    actualDate = if (isReceived) now else null,
                    status = if (isReceived) "RECEIVED" else "EXPECTED"
                )
            )
            if (isReceived) {
                dao.ensureCashBalance(CashBalanceEntity())
                val net = gross - taxAmount
                val current = dao.getCashBalance() ?: 0.0
                dao.updateCashBalance(current + net)
                dao.insertOperation(
                    OperationEntity(
                        assetId = assetId,
                        operationType = payoutType,
                        ticker = ticker.trim().uppercase(),
                        quantity = quantity,
                        price = amountPerUnit,
                        commission = taxAmount,
                        note = "Фактически получено; после налога: ${"%.2f".format(java.util.Locale.US, net)} ₽"
                    )
                )
            }
        }
    }
}
