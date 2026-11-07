package ru.mycapital.app.data

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
