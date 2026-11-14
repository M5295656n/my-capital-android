package ru.mycapital.app

import ru.mycapital.app.data.OperationEntity

internal data class RealizedTradingResult(
    val profit: Double,
    val unmatchedQuantity: Double
)

/**
 * FIFO matching over recorded trades only.
 *
 * Imported trades are matched within their brokerage agreement. Manually entered
 * trades have no agreement metadata and are therefore kept in a separate book.
 * Unmatched sales are surfaced rather than assigned an invented cost basis.
 *
 * Persisted historical rows are treated as untrusted input: non-finite values and
 * arithmetic overflow must never contaminate the portfolio's realized-profit total.
 */
internal fun calculateRealizedTradingResult(
    operations: List<OperationEntity>
): RealizedTradingResult {
    data class Lot(var quantity: Double, val unitCost: Double)
    data class TradeBookKey(val accountId: String?, val ticker: String)

    val lotsByBook = mutableMapOf<TradeBookKey, ArrayDeque<Lot>>()
    var profit = 0.0
    var unmatched = 0.0
    fun addUnmatched(quantity: Double) {
        val next = unmatched + quantity
        unmatched = if (next.isFinite()) next else Double.MAX_VALUE
    }

    operations.asSequence()
        .filter { it.operationType == "Покупка" || it.operationType == "Продажа" }
        .sortedBy { it.operationDate }
        .forEach { operation ->
            val ticker = operation.ticker?.trim()?.uppercase().orEmpty()
            if (ticker.isBlank()) return@forEach

            val quantity = operation.quantity
            if (!quantity.isFinite() || quantity <= 0.0) {
                return@forEach
            }

            val price = operation.price
            val commission = operation.commission
            if (!price.isFinite() || !commission.isFinite() || price < 0.0 || commission < 0.0) {
                if (operation.operationType == "Продажа") addUnmatched(quantity)
                return@forEach
            }

            val key = TradeBookKey(
                accountId = brokerAccountIdFromTradeSourceKey(operation.sourceKey),
                ticker = ticker
            )
            val lots = lotsByBook.getOrPut(key) { ArrayDeque() }

            if (operation.operationType == "Покупка") {
                val grossCost = quantity * price
                val totalCost = grossCost + commission
                val unitCost = totalCost / quantity
                if (!grossCost.isFinite() || !totalCost.isFinite() || !unitCost.isFinite()) {
                    return@forEach
                }
                lots.addLast(Lot(quantity, unitCost))
            } else {
                val grossProceeds = quantity * price
                val netProceeds = grossProceeds - commission
                val netSalePerUnit = netProceeds / quantity
                if (!grossProceeds.isFinite() || !netProceeds.isFinite() ||
                    netProceeds < 0.0 || !netSalePerUnit.isFinite()
                ) {
                    addUnmatched(quantity)
                    return@forEach
                }

                var remaining = quantity
                while (remaining > 0.0000001 && lots.isNotEmpty()) {
                    val lot = lots.first()
                    val matched = minOf(remaining, lot.quantity)
                    val increment = matched * (netSalePerUnit - lot.unitCost)
                    val nextProfit = profit + increment
                    if (!increment.isFinite() || !nextProfit.isFinite()) {
                        addUnmatched(remaining)
                        return@forEach
                    }
                    profit = nextProfit
                    remaining -= matched
                    lot.quantity -= matched
                    if (lot.quantity <= 0.0000001) lots.removeFirst()
                }
                addUnmatched(remaining)
            }
        }

    return RealizedTradingResult(profit, unmatched)
}

/** Expected imported trade key: SBER:<accountId>:TRADE:<broker row key>. */
internal fun brokerAccountIdFromTradeSourceKey(sourceKey: String?): String? {
    if (sourceKey.isNullOrBlank()) return null
    val parts = sourceKey.split(':', limit = 4)
    if (parts.size < 4 || parts[0] != "SBER" || parts[2] != "TRADE") return null
    return parts[1].takeIf { it.isNotBlank() }
}
