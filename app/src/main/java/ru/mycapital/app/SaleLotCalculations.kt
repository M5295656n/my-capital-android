package ru.mycapital.app

import ru.mycapital.app.data.OperationEntity

internal data class SaleLotResult(
    val operationId: Long,
    val matchedQuantity: Double,
    val unmatchedQuantity: Double,
    val costBasis: Double,
    val netProceeds: Double,
    val profit: Double
)

/** Calculates realized result for each sale using FIFO, within account and ticker. */
internal fun calculateSaleLotResults(
    operations: List<OperationEntity>
): List<SaleLotResult> {
    data class BookKey(val accountId: String?, val ticker: String)
    data class Lot(var quantity: Double, val unitCost: Double)

    val lotsByBook = mutableMapOf<BookKey, ArrayDeque<Lot>>()
    val results = mutableListOf<SaleLotResult>()

    operations.asSequence()
        .filter { it.operationType == "Покупка" || it.operationType == "Продажа" }
        .sortedWith(compareBy<OperationEntity> { it.operationDate }.thenBy { it.id })
        .forEach { operation ->
            val ticker = operation.ticker?.trim()?.uppercase().orEmpty()
            if (ticker.isBlank() || !operation.quantity.isFinite() ||
                !operation.price.isFinite() || !operation.commission.isFinite() ||
                operation.quantity <= 0.0
            ) return@forEach

            val key = BookKey(
                brokerAccountIdFromTradeSourceKey(operation.sourceKey),
                ticker
            )
            val lots = lotsByBook.getOrPut(key) { ArrayDeque() }

            if (operation.operationType == "Покупка") {
                val unitCost = (operation.quantity * operation.price + operation.commission) /
                    operation.quantity
                lots.addLast(Lot(operation.quantity, unitCost))
            } else {
                var remaining = operation.quantity
                var matchedQuantity = 0.0
                var costBasis = 0.0
                while (remaining > 0.0000001 && lots.isNotEmpty()) {
                    val lot = lots.first()
                    val matched = minOf(remaining, lot.quantity)
                    matchedQuantity += matched
                    costBasis += matched * lot.unitCost
                    remaining -= matched
                    lot.quantity -= matched
                    if (lot.quantity <= 0.0000001) lots.removeFirst()
                }
                val unmatched = remaining.coerceAtLeast(0.0)
                val matchedCommission = operation.commission *
                    (matchedQuantity / operation.quantity)
                val netProceeds = matchedQuantity * operation.price - matchedCommission
                results += SaleLotResult(
                    operationId = operation.id,
                    matchedQuantity = matchedQuantity,
                    unmatchedQuantity = unmatched,
                    costBasis = costBasis,
                    netProceeds = netProceeds,
                    profit = netProceeds - costBasis
                )
            }
        }

    return results
}
