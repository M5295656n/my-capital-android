package ru.mycapital.app

import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.PayoutEntity
import ru.mycapital.app.data.calculateReceivedPayoutNetAmount

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
    payouts.forEach { payout ->
        val date = payout.actualDate ?: payout.expectedDate ?: return@forEach
        val amount = calculateReceivedPayoutNetAmount(payout) ?: return@forEach
        if (amount != 0.0) flows += date to amount
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

/**
 * Returns the remaining FIFO acquisition cost of positions that have been
 * fully closed through bond redemption or full amortization.
 *
 * These positions have zero current market value, so their remaining purchase
 * basis is no longer present in quantity * averagePrice. Subtracting this basis
 * prevents returned principal from being reported as pure investment profit.
 * Incomplete or invalid trade histories are deliberately excluded.
 */
internal fun calculateRedeemedPositionCostBasis(
    assets: List<AssetEntity>,
    operations: List<OperationEntity>,
    payouts: List<PayoutEntity>
): Double {
    val epsilon = 0.000001
    var totalCostBasis = 0.0

    assets.forEach { asset ->
        if (!asset.quantity.isFinite() || asset.quantity < -epsilon || asset.quantity > epsilon) {
            return@forEach
        }
        val ticker = asset.ticker.trim().uppercase()
        val hasReceivedPrincipalReturn = payouts.any { payout ->
            val belongsToAsset = payout.assetId == asset.id ||
                payout.ticker.trim().equals(ticker, ignoreCase = true)
            val type = payout.payoutType.trim().lowercase(java.util.Locale.ROOT)
            belongsToAsset &&
                (type == "погашение" || type == "амортизация") &&
                (calculateReceivedPayoutNetAmount(payout) ?: 0.0) > 0.0
        }
        if (!hasReceivedPrincipalReturn) return@forEach

        val trades = operations.filter { operation ->
            (operation.assetId == asset.id ||
                operation.ticker?.trim()?.equals(ticker, ignoreCase = true) == true) &&
                (operation.operationType == "Покупка" || operation.operationType == "Продажа")
        }
        if (trades.isEmpty()) return@forEach

        val validHistory = trades.all { operation ->
            val quantity = operation.quantity
            val price = operation.price
            val commission = operation.commission
            val gross = quantity * price
            val totalCost = gross + commission
            quantity.isFinite() && quantity > 0.0 &&
                price.isFinite() && price >= 0.0 &&
                commission.isFinite() && commission >= 0.0 &&
                gross.isFinite() && totalCost.isFinite()
        }
        if (!validHistory) return@forEach

        val lots = calculateRemainingPurchaseLots(trades)
        if (!lots.unmatchedSaleQuantity.isFinite() || lots.unmatchedSaleQuantity > epsilon) {
            return@forEach
        }
        val netTradeQuantity = trades.sumOf { operation ->
            if (operation.operationType == "Покупка") operation.quantity else -operation.quantity
        }
        val remainingQuantity = lots.remainingByOperationId.values.sum()
        if (!netTradeQuantity.isFinite() || netTradeQuantity <= epsilon ||
            !remainingQuantity.isFinite() ||
            kotlin.math.abs(netTradeQuantity - remainingQuantity) > epsilon
        ) return@forEach

        val remainingCostBasis = trades.asSequence()
            .filter { it.operationType == "Покупка" }
            .sumOf { purchase ->
                val remaining = lots.remainingByOperationId[purchase.id] ?: 0.0
                val unitCost = (purchase.quantity * purchase.price + purchase.commission) /
                    purchase.quantity
                remaining * unitCost
            }
        if (!remainingCostBasis.isFinite() || remainingCostBasis < 0.0) return@forEach

        val nextTotal = totalCostBasis + remainingCostBasis
        if (nextTotal.isFinite()) totalCostBasis = nextTotal
    }
    return totalCostBasis
}
