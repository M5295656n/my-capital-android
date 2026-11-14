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
 */
internal fun calculateRealizedTradingResult(
    operations: List<OperationEntity>
): RealizedTradingResult {
    data class Lot(var quantity: Double, val unitCost: Double)
    data class TradeBookKey(val accountId: String?, val ticker: String)

    val lotsByBook = mutableMapOf<TradeBookKey, ArrayDeque<Lot>>()
    var profit = 0.0
    var unmatched = 0.0

    operations.asSequence()
        .filter { it.operationType == "Покупка" || it.operationType == "Продажа" }
        .sortedWith(compareBy<OperationEntity> { it.operationDate }.thenBy { it.id })
        .forEach { operation ->
            val ticker = operation.ticker?.trim()?.uppercase().orEmpty()
            if (ticker.isBlank() || operation.quantity <= 0.0) return@forEach

            val key = TradeBookKey(
                accountId = tradeBookAccountKey(operation),
                ticker = ticker
            )
            val lots = lotsByBook.getOrPut(key) { ArrayDeque() }

            if (operation.operationType == "Покупка") {
                val unitCost =
                    (operation.quantity * operation.price + operation.commission) / operation.quantity
                lots.addLast(Lot(operation.quantity, unitCost))
            } else {
                var remaining = operation.quantity
                val netSalePerUnit =
                    (operation.quantity * operation.price - operation.commission) / operation.quantity

                while (remaining > 0.0000001 && lots.isNotEmpty()) {
                    val lot = lots.first()
                    val matched = minOf(remaining, lot.quantity)
                    profit += matched * (netSalePerUnit - lot.unitCost)
                    remaining -= matched
                    lot.quantity -= matched
                    if (lot.quantity <= 0.0000001) lots.removeFirst()
                }
                unmatched += remaining
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

/**
 * Resolve a trade book without guessing an account for legacy imports.
 * Explicit accountId wins; scoped broker keys are the fallback. An unscoped
 * legacy Sber trade is isolated by operation id so it cannot be matched to
 * another unknown-account trade or to a manual trade.
 */
internal fun tradeBookAccountKey(operation: OperationEntity): String? {
    operation.accountId?.takeIf { it.isNotBlank() }?.let { return it }
    brokerAccountIdFromTradeSourceKey(operation.sourceKey)?.let { return it }
    val sourceKey = operation.sourceKey.orEmpty()
    if (sourceKey.startsWith("SBER:TRADE:", ignoreCase = true)) {
        return "LEGACY_UNKNOWN:" + operation.id
    }
    return null
}
