package ru.mycapital.app.data

internal fun calculatePayoutNetAmount(grossAmount: Double, taxAmount: Double): Double {
    require(grossAmount.isFinite() && grossAmount > 0.0) { "Сумма выплаты должна быть положительной" }
    require(taxAmount.isFinite() && taxAmount >= 0.0 && taxAmount <= grossAmount) {
        "Налог должен быть от нуля до суммы выплаты"
    }
    return grossAmount - taxAmount
}
