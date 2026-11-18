package ru.mycapital.app

import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.PayoutEntity

/**
 * Builds dated cash flows for an individual asset's money-weighted return.
 *
 * Trade cash flows include commissions and explicit asset-level costs. Cash
 * receipts are sourced from the payout ledger so coupon/dividend operations
 * are not counted twice. The current valuation is the terminal cash flow.
 */
internal fun buildAssetReturnCashFlows(
    operations: List<OperationEntity>,
    payouts: List<PayoutEntity>,
    currentValue: Double,
    valuationDate: Long
): List<Pair<Long, Double>> {
    val flows = mutableListOf<Pair<Long, Double>>()
    operations.forEach { operation ->
        val gross = operation.quantity * operation.price
        val amount = when (operation.operationType) {
            "Покупка" -> -(gross + operation.commission)
            "Продажа" -> gross - operation.commission
            "Комиссия", "Налог", "Расход", "Списание" -> -(gross + operation.commission)
            else -> null
        }
        if (amount != null && amount.isFinite() && amount != 0.0) {
            flows += operation.operationDate to amount
        }
    }
    payouts.filter { it.actualDate != null || it.status.equals("RECEIVED", ignoreCase = true) }
        .forEach { payout ->
            val date = payout.actualDate ?: payout.expectedDate ?: return@forEach
            val amount = payout.reportedNetAmount ?: (payout.totalAmount - payout.taxAmount)
            if (amount.isFinite() && amount != 0.0) flows += date to amount
        }
    if (currentValue.isFinite() && currentValue > 0.0) {
        flows += valuationDate to currentValue
    }
    return flows.sortedBy { it.first }
}

/**
 * Excel XIRR (ЧИСТВНДОХ) using a 365-day year and bounded bisection.
 * Returns null when the cash flows do not define a supported positive/negative
 * root within the supported rate interval.
 */
internal fun annualizedReturnPercent(flows: List<Pair<Long, Double>>): Double? {
    val items = flows.filter { it.second.isFinite() && it.second != 0.0 }.sortedBy { it.first }
    if (items.size < 2 || items.none { it.second < 0.0 } || items.none { it.second > 0.0 }) return null
    val start = items.first().first
    val millisPerYear = 365.0 * 24.0 * 60.0 * 60.0 * 1000.0
    if ((items.last().first - start) / millisPerYear <= 0.0) return null
    fun npv(rate: Double) = items.sumOf { (date, amount) ->
        amount / Math.pow(1.0 + rate, (date - start) / millisPerYear)
    }
    var low = -0.9999
    var high = 10.0
    if (npv(low) * npv(high) > 0.0) return null
    repeat(120) {
        val mid = (low + high) / 2.0
        if (npv(low) * npv(mid) <= 0.0) high = mid else low = mid
    }
    val result = (low + high) / 2.0
    return if (result.isFinite()) result * 100.0 else null
}
