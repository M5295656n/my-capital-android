package ru.mycapital.app.data

import androidx.room.withTransaction
import java.time.ZoneOffset
import java.util.Locale

// Sber reports can contain a concluded trade that has not settled by period end:
// "ЗО" = concluded during the period, with no obligation fulfilled yet. It is still
// a real buy/sell and must be imported; the planned outgoing balances reconcile it.
internal fun isImportableBrokerTrade(status: String): Boolean =
    status.trim().uppercase(Locale.ROOT) in setOf("И", "ЗИ", "ЗО")


/**
 * Calculates weighted-average acquisition cost from a complete buy/sell history.
 * Returns null when the available operations do not explain the current holding
 * (for example, when the report omits purchases made before its start date).
 */
internal fun calculateAverageCostFromCompleteHistory(
    operations: List<OperationEntity>,
    expectedQuantity: Double
): Double? {
    val epsilon = 0.0000001
    var quantity = 0.0
    var totalCost = 0.0

    operations.sortedWith(compareBy<OperationEntity> { it.operationDate }.thenBy { it.id })
        .forEach { operation ->
            when (operation.operationType) {
                "Покупка" -> {
                    if (operation.quantity <= 0.0 || operation.price < 0.0 || operation.commission < 0.0) {
                        return null
                    }
                    quantity += operation.quantity
                    totalCost += operation.quantity * operation.price + operation.commission
                }
                "Продажа" -> {
                    if (operation.quantity <= 0.0 || quantity + epsilon < operation.quantity) {
                        return null
                    }
                    val averageBeforeSale = if (quantity > epsilon) totalCost / quantity else 0.0
                    quantity -= operation.quantity
                    totalCost = averageBeforeSale * quantity
                    if (quantity <= epsilon) {
                        quantity = 0.0
                        totalCost = 0.0
                    }
                }
            }
        }

    if (kotlin.math.abs(quantity - expectedQuantity) > maxOf(epsilon, expectedQuantity * 0.000001)) {
        return null
    }
    return if (quantity > epsilon) totalCost / quantity else 0.0
}

class PortfolioRepository(private val database: PortfolioDatabase) {
    private val dao = database.portfolioDao()


    data class BrokerHistoryImportResult(
        val imported: Int,
        val duplicates: Int,
        val unsettledSkipped: Int,
        val payoutsImported: Int
    )


    data class BrokerSnapshotReconciliationResult(
        val matchedAssets: Int,
        val unmatchedReportAssets: Int,
        val previousCash: Double,
        val resultingCash: Double
    )

    suspend fun reconcileBrokerReportSnapshot(report: SberBrokerReport): BrokerSnapshotReconciliationResult {
        var matched = 0
        var unmatched = 0
        var previousCash = 0.0
        var resultingCash = 0.0
        database.withTransaction {
            if (dao.getBrokerAccount(report.accountId) == null) {
                dao.upsertBrokerAccount(
                    BrokerAccountEntity(
                        accountId = report.accountId,
                        displayName = "Договор ••••" + report.accountId.takeLast(4),
                        accountType = "Не указан"
                    )
                )
            }
            dao.ensureCashBalance(CashBalanceEntity())
            previousCash = dao.getCashBalance() ?: 0.0

            // Keep one absolute cash snapshot per brokerage agreement. The first
            // imported agreement establishes the baseline; additional agreements
            // are added, and later imports replace only that agreement's amount.
            val knownAccountCash = dao.getBrokerAccountCashAccounts()
            val oldAccountCash = dao.getBrokerAccountCash(report.accountId)
            // Prefer Sber's planned outgoing balance when unsettled transactions exist.
            // This keeps cash and securities on the same post-settlement basis.
            val snapshotCash = report.plannedClosingCash ?: report.closingCash
            resultingCash = if (oldAccountCash != null) {
                previousCash - oldAccountCash.balance + snapshotCash
            } else if (knownAccountCash.isEmpty()) {
                snapshotCash
            } else {
                previousCash + snapshotCash
            }
            dao.upsertBrokerAccountCash(BrokerAccountCashEntity(report.accountId, snapshotCash))
            dao.updateCashBalance(resultingCash.coerceAtLeast(0.0))

            val oldAccountAssets = dao.getBrokerAccountAssets(report.accountId).associateBy { it.ticker.uppercase() }
            val currentAssets = dao.getAssets().associateBy { it.ticker.uppercase() }.toMutableMap()
            val reportAssets = report.assets
                .filter { it.ticker.isNotBlank() }
                .groupBy { it.ticker.trim().uppercase() }
                .mapValues { (_, rows) ->
                    rows.first().copy(closingQuantity = rows.sumOf { it.effectiveQuantity })
                }
            val isFirstAccountSnapshot = knownAccountCash.isEmpty()

            // A first snapshot must be able to initialize an empty app. Create missing
            // portfolio instruments from the broker's security directory before applying
            // account-scoped quantities; otherwise every valid report row is counted as
            // unmatched and the user sees cash but no assets.
            reportAssets.forEach { (ticker, row) ->
                if (ticker !in currentAssets && row.closingQuantity > 0.0) {
                    val type = row.assetType
                    val id = dao.insertAsset(
                        AssetEntity(
                            ticker = ticker,
                            name = row.name,
                            assetType = type,
                            quantity = 0.0,
                            // The holdings snapshot contains market value, not acquisition cost.
                            // Leave cost basis unknown rather than inventing an average purchase price.
                            averagePrice = 0.0,
                            currentPrice = row.closingPrice
                        )
                    )
                    currentAssets[ticker] = AssetEntity(
                        id = id,
                        ticker = ticker,
                        name = row.name,
                        assetType = type,
                        quantity = 0.0,
                        averagePrice = 0.0,
                        currentPrice = row.closingPrice
                    )
                }
            }

            // Quantities in AssetEntity are portfolio-wide. Apply only the delta
            // belonging to this agreement, so importing IIS-3 cannot overwrite
            // positions already reconciled from the main brokerage account.
            (oldAccountAssets.keys + reportAssets.keys).forEach { ticker ->
                val asset = currentAssets[ticker]
                val reportRow = reportAssets[ticker]
                if (asset == null) {
                    if (reportRow != null && reportRow.closingQuantity > 0.0) unmatched++
                    return@forEach
                }
                if (reportRow != null) matched++
                val oldQuantity = oldAccountAssets[ticker]?.quantity ?: 0.0
                val newQuantity = reportRow?.closingQuantity ?: 0.0
                val nextQuantity = if (isFirstAccountSnapshot) {
                    reportRow?.closingQuantity ?: asset.quantity
                } else {
                    (asset.quantity - oldQuantity + newQuantity).coerceAtLeast(0.0)
                }
                dao.updateAsset(
                    asset.copy(
                        quantity = nextQuantity,
                        currentPrice = reportRow?.closingPrice?.takeIf { it > 0.0 } ?: asset.currentPrice,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }

            dao.deleteBrokerAccountAssets(report.accountId)
            dao.upsertBrokerAccountAssets(
                reportAssets.map { (ticker, row) ->
                    BrokerAccountAssetEntity(report.accountId, ticker, row.closingQuantity)
                }
            )
        }
        return BrokerSnapshotReconciliationResult(
            matchedAssets = matched,
            unmatchedReportAssets = unmatched,
            previousCash = previousCash,
            resultingCash = resultingCash.coerceAtLeast(0.0)
        )
    }

    data class BrokerReportImportAndReconciliationResult(
        val reconciliation: BrokerSnapshotReconciliationResult,
        val history: BrokerHistoryImportResult
    )

    /**
     * Applies the holdings/cash snapshot first, then imports the statement's
     * transaction and payout history. Both steps run in one Room transaction.
     */
    suspend fun importAndReconcileBrokerReport(
        report: SberBrokerReport
    ): BrokerReportImportAndReconciliationResult = database.withTransaction {
        val reconciliation = reconcileBrokerReportSnapshot(report)
        val history = importBrokerReportHistory(report)
        BrokerReportImportAndReconciliationResult(reconciliation, history)
    }

    suspend fun importBrokerReportHistory(report: SberBrokerReport): BrokerHistoryImportResult {
        var imported = 0
        var duplicates = 0
        var unsettledSkipped = 0
        var payoutsImported = 0
        database.withTransaction {
            if (dao.getBrokerAccount(report.accountId) == null) {
                dao.upsertBrokerAccount(
                    BrokerAccountEntity(
                        accountId = report.accountId,
                        displayName = "Договор ••••" + report.accountId.takeLast(4),
                        accountType = "Не указан"
                    )
                )
            }
            dao.upsertBrokerCashOpening(
                BrokerCashOpeningEntity(
                    accountId = report.accountId,
                    periodStart = report.periodStart.toString(),
                    openingCash = report.openingCash,
                    periodEnd = report.periodEnd.toString()
                )
            )
            val assetsByTicker = dao.getAssets().associateBy { it.ticker.uppercase() }
            report.cashFlows.forEach { flow ->
                val kind = flow.kind
                if (kind == BrokerCashFlowKind.IGNORE) return@forEach
                val amount = when (kind) {
                    BrokerCashFlowKind.DEPOSIT, BrokerCashFlowKind.DIVIDEND, BrokerCashFlowKind.COUPON, BrokerCashFlowKind.REDEMPTION, BrokerCashFlowKind.AMORTIZATION -> flow.credit
                    BrokerCashFlowKind.WITHDRAWAL -> flow.debit
                    BrokerCashFlowKind.INTERNAL_TRANSFER -> {
                        val transfer = flow.internalTransfer ?: return@forEach
                        if (report.accountId != transfer.fromAccountId && report.accountId != transfer.toAccountId) return@forEach
                        if (flow.credit > 0.0 && flow.debit == 0.0 && report.accountId == transfer.toAccountId) flow.credit
                        else if (flow.debit > 0.0 && flow.credit == 0.0 && report.accountId == transfer.fromAccountId) flow.debit
                        else return@forEach
                    }
                    else -> 0.0
                }
                if (amount <= 0.0) return@forEach
                val operationType = when (kind) {
                    BrokerCashFlowKind.DEPOSIT -> "Пополнение"
                    BrokerCashFlowKind.WITHDRAWAL -> "Вывод"
                    BrokerCashFlowKind.DIVIDEND -> "Дивиденд"
                    BrokerCashFlowKind.COUPON -> "Купон"
                    BrokerCashFlowKind.REDEMPTION -> "Погашение"
                    BrokerCashFlowKind.AMORTIZATION -> "Амортизация"
                    BrokerCashFlowKind.INTERNAL_TRANSFER ->
                        if (flow.credit > 0.0) "Внутренний перевод входящий" else "Внутренний перевод исходящий"
                    else -> return@forEach
                }
                val payout = kind == BrokerCashFlowKind.DIVIDEND || kind == BrokerCashFlowKind.COUPON || kind == BrokerCashFlowKind.REDEMPTION || kind == BrokerCashFlowKind.AMORTIZATION
                val ticker = if (payout) flow.ticker else "RUB"
                val note = when (kind) {
                    BrokerCashFlowKind.DIVIDEND ->
                        if (flow.description.contains("налог удержан", true))
                            "Чистая сумма по брокерскому отчёту; налог удержан, точная сумма налога не указана"
                        else "Чистая сумма по брокерскому отчёту; налоговая разбивка не указана"
                    BrokerCashFlowKind.COUPON ->
                        "Сумма зачисления по брокерскому отчёту; налоговая разбивка не указана"
                    BrokerCashFlowKind.REDEMPTION ->
                        "Погашение по брокерскому отчёту: ${flow.description}"
                    BrokerCashFlowKind.AMORTIZATION ->
                        "Амортизация по брокерскому отчёту: ${flow.description}"
                    else -> "Импорт из брокерского отчёта: ${flow.description}"
                }
                val scopedFlowKey = flow.rowKey.removePrefix("SBER:CASH:").removePrefix("CASH:")
                val scopedSourceKey = "SBER:${report.accountId}:CASH:$scopedFlowKey"
                if (payout) {
                    // A credited broker-report row is evidence of receipt. The report
                    // supplies the net credit, but may not disclose gross or withheld tax.
                    // Keep that net amount separately instead of inventing a gross/tax split.
                    // Older releases stored the exact parser rowKey without an account
                    // prefix. Promote that exact-key record in place before inserting;
                    // never infer identity from ticker/date/amount alone.
                    val payoutSourceKey = "SBER:${report.accountId}:PAYOUT:$scopedFlowKey"
                    val legacyPayout = dao.getPayoutBySourceKey(flow.rowKey)
                    val scopedPayout = dao.getPayoutBySourceKey(payoutSourceKey)
                    val receivedAt = flow.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                    val payoutAssetId = ticker?.let { symbol -> assetsByTicker[symbol.uppercase()]?.id }
                    when {
                        scopedPayout != null -> Unit
                        legacyPayout != null -> {
                            dao.updatePayout(
                                legacyPayout.copy(
                                    assetId = payoutAssetId,
                                    ticker = ticker ?: legacyPayout.ticker,
                                    payoutType = operationType,
                                    actualDate = receivedAt,
                                    status = "RECEIVED",
                                    sourceKey = payoutSourceKey,
                                    reportedNetAmount = amount
                                )
                            )
                            payoutsImported++
                        }
                        else -> {
                            val payoutId = dao.insertPayout(
                                PayoutEntity(
                                    assetId = payoutAssetId,
                                    ticker = ticker ?: "—",
                                    payoutType = operationType,
                                    amountPerUnit = 0.0,
                                    quantity = 0.0,
                                    totalAmount = 0.0,
                                    taxAmount = 0.0,
                                    expectedDate = null,
                                    actualDate = receivedAt,
                                    status = "RECEIVED",
                                    sourceKey = payoutSourceKey,
                                    reportedNetAmount = amount
                                )
                            )
                            if (payoutId != -1L) payoutsImported++
                        }
                    }
                }
                val row = OperationEntity(
                    assetId = ticker?.let { symbol -> assetsByTicker[symbol.uppercase()]?.id },
                    operationType = operationType,
                    ticker = ticker,
                    quantity = 1.0,
                    price = amount,
                    commission = 0.0,
                    operationDate = flow.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                    note = note,
                    sourceKey = scopedSourceKey,
                    accountId = report.accountId
                )
                // Older app versions used an unscoped key. Respect it during upgrade
                // so re-importing an old statement cannot create a duplicate.
                if (dao.getOperationBySourceKey(flow.rowKey) != null) {
                    duplicates++
                } else {
                    val id = dao.insertImportedOperation(row)
                    if (id == -1L) duplicates++ else imported++
                }
            }
            report.trades.forEach { trade ->
                // Only the explicit fully executed status is imported. Other codes may
                // represent unsettled, partially settled or pending obligations.
                if (!isImportableBrokerTrade(trade.status)) {
                    unsettledSkipped++
                    return@forEach
                }
                val isBuy = trade.side.contains("Покуп", ignoreCase = true)
                val isSell = trade.side.contains("Прод", ignoreCase = true)
                if (!isBuy && !isSell) {
                    unsettledSkipped++
                    return@forEach
                }
                val scopedSourceKey = "SBER:${report.accountId}:TRADE:${trade.sourceKey.removePrefix("SBER:")}"
                val row = OperationEntity(
                    assetId = assetsByTicker[trade.ticker.uppercase()]?.id,
                    operationType = if (isBuy) "Покупка" else "Продажа",
                    ticker = trade.ticker.uppercase(),
                    quantity = trade.quantity,
                    price = trade.price,
                    commission = trade.totalFee,
                    operationDate = trade.tradeDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                    settlementDate = trade.settlementDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                    note = "Импорт из брокерского отчёта; сделка № ${trade.dealNumber}",
                    sourceKey = scopedSourceKey,
                    accountId = report.accountId
                )
                val settlementTimestamp = trade.settlementDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                val legacyDuplicate = dao.getOperationBySourceKey(trade.sourceKey)
                if (legacyDuplicate != null) {
                    // Upgrade older imports in place: settlementDate was not stored
                    // by previous app versions.
                    dao.updateOperationSettlementDate(trade.sourceKey, settlementTimestamp)
                    duplicates++
                } else {
                    val id = dao.insertImportedOperation(row)
                    if (id == -1L) {
                        dao.updateOperationSettlementDate(scopedSourceKey, settlementTimestamp)
                        duplicates++
                    } else imported++
                }
            }

            // Rebuild average acquisition cost only when the stored trade history
            // fully explains the current snapshot quantity. If the statement starts
            // after the first purchase, keep the existing value rather than publish
            // a misleading partial-period average.
            assetsByTicker.values.forEach { asset ->
                val history = dao.getOperationsForAsset(asset.id)
                val calculatedAverage = calculateAverageCostFromCompleteHistory(history, asset.quantity)
                if (calculatedAverage != null) {
                    dao.updateAsset(
                        asset.copy(
                            averagePrice = calculatedAverage,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        return BrokerHistoryImportResult(imported, duplicates, unsettledSkipped, payoutsImported)
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
            require(payout.status in setOf("EXPECTED", "ANNOUNCED", "RECOMMENDED", "FORECAST")) {
                "Можно подтвердить только объявленную или ожидаемую выплату"
            }
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
                    note = "Фактически получено; после налога: ${"%.2f".format(java.util.Locale.US, net)} ₽",
                    sourceKey = payout.sourceKey
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
        isReceived: Boolean,
        expectedDate: Long? = null
    ) {
        require(ticker.isNotBlank()) { "Укажите тикер" }
        require(payoutType == "Дивиденд" || payoutType == "Купон") { "Неизвестный тип выплаты" }
        require(amountPerUnit > 0.0) { "Сумма на единицу должна быть больше нуля" }
        require(quantity > 0.0) { "Количество должно быть больше нуля" }
        val gross = amountPerUnit * quantity
        require(taxAmount >= 0.0 && taxAmount <= gross) { "Налог должен быть от нуля до суммы выплаты" }
        if (!isReceived) require(expectedDate != null) { "Укажите дату ожидаемой выплаты" }
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
                    expectedDate = if (isReceived) null else expectedDate,
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
