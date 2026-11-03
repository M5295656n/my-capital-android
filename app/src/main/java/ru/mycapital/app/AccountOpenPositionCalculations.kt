package ru.mycapital.app

import ru.mycapital.app.data.OperationEntity

internal data class AccountOpenPositionResult(
    val costBasis: Double,
    val marketValue: Double,
    val profit: Double,
    val unmatchedQuantity: Double
)

/**
 * Values only the remaining FIFO lots that can be traced to recorded purchases.
 * The latest broker snapshot caps the quantity being valued; unknown opening
 * positions and sales without matching purchases are reported as incomplete.
 */
internal fun calculateAccountOpenPositionResult(
    operations: List<OperationEntity>,
    actualQuantities: Map<String, Double>,
    currentPrices: Map<String, Double>
): AccountOpenPositionResult {
    data class Lot(var quantity: Double, val unitCost: Double)
    val lotsByTicker = mutableMapOf<String, ArrayDeque<Lot>>()
    var unmatchedSales = 0.0

    operations.asSequence()
        .filter { it.operationType == "Покупка" || it.operationType == "Продажа" }
        .sortedBy { it.operationDate }
        .forEach { operation ->
            val ticker = operation.ticker?.trim()?.uppercase().orEmpty()
            if (ticker.isBlank() || !operation.quantity.isFinite() || operation.quantity <= 0.0 ||
                !operation.price.isFinite() || operation.price < 0.0 ||
                !operation.commission.isFinite() || operation.commission < 0.0
            ) return@forEach

            val lots = lotsByTicker.getOrPut(ticker) { ArrayDeque() }
            if (operation.operationType == "Покупка") {
                lots.addLast(
                    Lot(
                        quantity = operation.quantity,
                        unitCost = (operation.quantity * operation.price + operation.commission) / operation.quantity
                    )
                )
            } else {
                var remaining = operation.quantity
                while (remaining > 0.0000001 && lots.isNotEmpty()) {
                    val lot = lots.first()
                    val matched = minOf(remaining, lot.quantity)
                    remaining -= matched
                    lot.quantity -= matched
                    if (lot.quantity <= 0.0000001) lots.removeFirst()
                }
                unmatchedSales += remaining
            }
        }

    var costBasis = 0.0
    var marketValue = 0.0
    var quantityGap = 0.0
    lotsByTicker.forEach { (ticker, lots) ->
        val knownQuantity = lots.sumOf { it.quantity }
        val actualQuantity = (actualQuantities[ticker] ?: 0.0).coerceAtLeast(0.0)
        val valuedQuantity = minOf(knownQuantity, actualQuantity)
        var remainingToValue = valuedQuantity
        lots.forEach { lot ->
            if (remainingToValue > 0.0000001) {
                val matched = minOf(remainingToValue, lot.quantity)
                costBasis += matched * lot.unitCost
                remainingToValue -= matched
            }
        }
        marketValue += valuedQuantity * (currentPrices[ticker] ?: 0.0).coerceAtLeast(0.0)
        quantityGap += kotlin.math.abs(actualQuantity - knownQuantity)
    }
    actualQuantities.forEach { (ticker, quantity) ->
        if (ticker.uppercase() !in lotsByTicker) quantityGap += quantity.coerceAtLeast(0.0)
    }

    val profit = marketValue - costBasis
    return AccountOpenPositionResult(
        costBasis = costBasis,
        marketValue = marketValue,
        profit = profit,
        unmatchedQuantity = quantityGap + unmatchedSales
    )
}
