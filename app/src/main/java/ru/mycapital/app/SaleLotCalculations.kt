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
    val epsilon = 0.0000001

    operations.asSequence()
        .filter { it.operationType == "Покупка" || it.operationType == "Продажа" }
        .sortedWith(compareBy<OperationEntity> { it.operationDate }.thenBy { it.id })
        .forEach { operation ->
            val ticker = operation.ticker?.trim()?.uppercase().orEmpty()
            val quantity = operation.quantity
            val price = operation.price
            val commission = operation.commission
            if (ticker.isBlank() || !quantity.isFinite() || quantity <= 0.0 ||
                !price.isFinite() || price < 0.0 ||
                !commission.isFinite() || commission < 0.0
            ) {
                if (operation.operationType == "Продажа" && quantity.isFinite() && quantity > 0.0) {
                    results += SaleLotResult(operation.id, 0.0, quantity, 0.0, 0.0, 0.0)
                }
                return@forEach
            }

            val key = BookKey(
                operation.accountId?.takeIf { it.isNotBlank() } ?: brokerAccountIdFromTradeSourceKey(operation.sourceKey),
                ticker
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
                val totalNetProceeds = grossProceeds - commission
                val netSalePerUnit = totalNetProceeds / quantity
                if (!grossProceeds.isFinite() || !totalNetProceeds.isFinite() ||
                    totalNetProceeds < 0.0 || !netSalePerUnit.isFinite()
                ) {
                    results += SaleLotResult(operation.id, 0.0, quantity, 0.0, 0.0, 0.0)
                    return@forEach
                }

                var remaining = quantity
                var matchedQuantity = 0.0
                var costBasis = 0.0
                val allocations = mutableListOf<Pair<Lot, Double>>()
                for (lot in lots) {
                    if (remaining <= epsilon) break
                    val matched = minOf(remaining, lot.quantity)
                    val nextMatched = matchedQuantity + matched
                    val nextCostBasis = costBasis + matched * lot.unitCost
                    if (!nextMatched.isFinite() || !nextCostBasis.isFinite()) {
                        remaining = quantity
                        matchedQuantity = 0.0
                        costBasis = 0.0
                        allocations.clear()
                        break
                    }
                    allocations += lot to matched
                    matchedQuantity = nextMatched
                    costBasis = nextCostBasis
                    remaining -= matched
                }

                val matchedCommission = commission * (matchedQuantity / quantity)
                val netProceeds = matchedQuantity * price - matchedCommission
                val profit = netProceeds - costBasis
                if (!matchedCommission.isFinite() || !netProceeds.isFinite() ||
                    netProceeds < 0.0 || !profit.isFinite()
                ) {
                    results += SaleLotResult(operation.id, 0.0, quantity, 0.0, 0.0, 0.0)
                    return@forEach
                }

                allocations.forEach { (lot, matched) ->
                    check(lots.firstOrNull() === lot) { "FIFO lot order changed during calculation." }
                    lot.quantity -= matched
                    if (lot.quantity <= epsilon) lots.removeFirst()
                }
                results += SaleLotResult(
                    operationId = operation.id,
                    matchedQuantity = matchedQuantity,
                    unmatchedQuantity = remaining.coerceAtLeast(0.0),
                    costBasis = costBasis,
                    netProceeds = netProceeds,
                    profit = profit
                )
            }
        }

    return results
}
