package ru.mycapital.app.data

import java.util.Locale

internal fun calculatePayoutNetAmount(grossAmount: Double, taxAmount: Double): Double {
    require(grossAmount.isFinite() && grossAmount > 0.0) { "Сумма выплаты должна быть положительной" }
    require(taxAmount.isFinite() && taxAmount >= 0.0 && taxAmount <= grossAmount) {
        "Налог должен быть от нуля до суммы выплаты"
    }
    return grossAmount - taxAmount
}

/**
 * Bond principal returned through amortization or maturity is capital repayment,
 * not investment income. Keep these events in the calendar, but exclude them
 * from income totals and income charts.
 */
internal fun isIncomePayoutType(payoutType: String): Boolean {
    val normalized = payoutType.trim().lowercase(java.util.Locale.ROOT)
    return !normalized.contains("погаш") && !normalized.contains("амортизац")
}


internal data class ReceivedPayoutTotals(
    val totalNetAmount: Double,
    val investmentIncome: Double,
    val dividends: Double,
    val coupons: Double,
    val returnedPrincipal: Double
)

/**
 * Returns the validated net cash amount for a payout that was actually received.
 * An explicitly reported broker net amount takes precedence because the source
 * may not disclose the gross/tax split. Otherwise both gross and tax are validated.
 */
internal fun calculateReceivedPayoutNetAmount(payout: PayoutEntity): Double? {
    if (!payout.status.equals("RECEIVED", ignoreCase = true) && payout.actualDate == null) return null

    payout.reportedNetAmount?.let { reported ->
        return reported.takeIf { it.isFinite() && it >= 0.0 }
    }

    val gross = payout.totalAmount
    val tax = payout.taxAmount
    if (!gross.isFinite() || gross < 0.0 || !tax.isFinite() || tax < 0.0 || tax > gross) {
        return null
    }
    return (gross - tax).takeIf { it.isFinite() && it >= 0.0 }
}

/**
 * One shared definition of cash actually received from payouts.
 * Returned bond principal is included in total cash received and total investment
 * result, but kept separate from investment income.
 */
internal fun calculateReceivedPayoutTotals(payouts: List<PayoutEntity>): ReceivedPayoutTotals {
    var total = 0.0
    var income = 0.0
    var dividends = 0.0
    var coupons = 0.0
    var principal = 0.0

    payouts.forEach { payout ->
            val amount = calculateReceivedPayoutNetAmount(payout) ?: return@forEach
            val nextTotal = total + amount
            if (!nextTotal.isFinite()) return@forEach
            total = nextTotal
            when (payout.payoutType.trim().lowercase(Locale.ROOT)) {
                "погашение", "амортизация" -> {
                    val next = principal + amount
                    if (next.isFinite()) principal = next
                }
                "дивиденд" -> {
                    val nextIncome = income + amount
                    val nextDividends = dividends + amount
                    if (nextIncome.isFinite()) income = nextIncome
                    if (nextDividends.isFinite()) dividends = nextDividends
                }
                "купон" -> {
                    val nextIncome = income + amount
                    val nextCoupons = coupons + amount
                    if (nextIncome.isFinite()) income = nextIncome
                    if (nextCoupons.isFinite()) coupons = nextCoupons
                }
                else -> if (isIncomePayoutType(payout.payoutType)) {
                    val next = income + amount
                    if (next.isFinite()) income = next
                }
            }
        }
    return ReceivedPayoutTotals(total, income, dividends, coupons, principal)
}
