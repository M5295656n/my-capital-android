package ru.mycapital.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Reconstructed end-of-day ledger state. This is quantity/cash history only;
 * market value must be calculated separately from actual historical quotations.
 */
internal data class PortfolioLedgerDay(
    val date: LocalDate,
    val cashBalance: Double,
    val quantities: Map<String, Double>,
    val externalFlow: Double
)

/**
 * Rebuilds a single brokerage account from its statement opening cash and imported
 * executed operations. Returns null when the cash baseline is ambiguous or missing.
 *
 * The first point is the date of the first purchase, as requested for the portfolio
 * chart. Deposits/withdrawals change cash but are separately identified so a later
 * unit-NAV calculation can exclude them from investment performance.
 */
internal fun reconstructPortfolioLedger(
    openingCash: Double?,
    periodStart: LocalDate?,
    operations: List<OperationEntity>,
    throughDate: LocalDate
): List<PortfolioLedgerDay>? {
    if (openingCash == null || !openingCash.isFinite() || openingCash < 0.0 || periodStart == null) {
        return null
    }

    val dated = operations.map { operation ->
        operation to Instant.ofEpochMilli(operation.operationDate).atZone(ZoneOffset.UTC).toLocalDate()
    }.filter { (_, date) -> !date.isBefore(periodStart) && !date.isAfter(throughDate) }
        .sortedWith(compareBy<Pair<OperationEntity, LocalDate>> { it.second }.thenBy { it.first.id })

    val firstPurchaseDate: LocalDate = dated.firstOrNull { it.first.operationType == "Покупка" }?.second
        ?: return emptyList()
    if (firstPurchaseDate.isAfter(throughDate)) return emptyList()

    var cash = openingCash
    val quantities = linkedMapOf<String, Double>()
    val byDate = dated.groupBy { it.second }
    val result = mutableListOf<PortfolioLedgerDay>()
    var date = periodStart

    while (!date.isAfter(throughDate)) {
        var externalFlow = 0.0
        byDate[date].orEmpty().forEach { (operation, _) ->
            val type = operation.operationType
            val ticker = operation.ticker?.trim()?.uppercase().orEmpty()
            when (type) {
                "Пополнение" -> {
                    val amount = operation.quantity * operation.price
                    cash += amount
                    externalFlow += amount
                }
                "Вывод" -> {
                    val amount = operation.quantity * operation.price
                    cash -= amount
                    externalFlow -= amount
                }
                "Дивиденд", "Купон" -> cash += operation.quantity * operation.price
                "Покупка" -> {
                    if (ticker.isNotBlank() && operation.quantity > 0.0 && operation.price >= 0.0) {
                        quantities[ticker] = (quantities[ticker] ?: 0.0) + operation.quantity
                        cash -= operation.quantity * operation.price + operation.commission
                    }
                }
                "Продажа" -> {
                    if (ticker.isNotBlank() && operation.quantity > 0.0 && operation.price >= 0.0) {
                        quantities[ticker] = ((quantities[ticker] ?: 0.0) - operation.quantity).coerceAtLeast(0.0)
                        cash += operation.quantity * operation.price - operation.commission
                    }
                }
            }
        }
        if (!date.isBefore(firstPurchaseDate)) {
            result += PortfolioLedgerDay(
                date = date,
                cashBalance = cash,
                quantities = quantities.filterValues { it > 0.0000001 }.toMap(),
                externalFlow = externalFlow
            )
        }
        date = date.plusDays(1)
    }
    return result
}
