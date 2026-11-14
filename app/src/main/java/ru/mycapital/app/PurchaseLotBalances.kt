package ru.mycapital.app

import ru.mycapital.app.data.OperationEntity

internal data class PurchaseLotBalances(
    val remainingByOperationId: Map<Long, Double>,
    val unmatchedSaleQuantity: Double
)

/**
 * Matches recorded sales against recorded purchases using FIFO, separately
 * for each brokerage account and ticker. A null account remains its own book
 * and is never mixed with an imported brokerage account.
 */
internal fun calculateRemainingPurchaseLots(
    operations: List<OperationEntity>
): PurchaseLotBalances {
    data class BookKey(val accountId: String?, val ticker: String)
    data class Lot(val operationId: Long, var quantity: Double)

    val lotsByBook = mutableMapOf<BookKey, ArrayDeque<Lot>>()
    val remainingById = mutableMapOf<Long, Double>()
    var unmatchedSaleQuantity = 0.0

    operations.asSequence()
        .filter { it.operationType == "Покупка" || it.operationType == "Продажа" }
        .sortedWith(compareBy<OperationEntity> { it.operationDate }.thenBy { it.id })
        .forEach { operation ->
            val ticker = operation.ticker?.trim()?.uppercase().orEmpty()
            if (ticker.isBlank() || !operation.quantity.isFinite() || operation.quantity <= 0.0) {
                return@forEach
            }
            val key = BookKey(tradeBookAccountKey(operation), ticker)
            val lots = lotsByBook.getOrPut(key) { ArrayDeque() }

            if (operation.operationType == "Покупка") {
                lots.addLast(Lot(operation.id, operation.quantity))
                remainingById[operation.id] = operation.quantity
            } else {
                var toSell = operation.quantity
                while (toSell > 0.0000001 && lots.isNotEmpty()) {
                    val lot = lots.first()
                    val matched = minOf(toSell, lot.quantity)
                    lot.quantity -= matched
                    toSell -= matched
                    remainingById[lot.operationId] = lot.quantity.coerceAtLeast(0.0)
                    if (lot.quantity <= 0.0000001) lots.removeFirst()
                }
                unmatchedSaleQuantity += toSell
            }
        }

    return PurchaseLotBalances(
        remainingByOperationId = remainingById,
        unmatchedSaleQuantity = unmatchedSaleQuantity
    )
}
