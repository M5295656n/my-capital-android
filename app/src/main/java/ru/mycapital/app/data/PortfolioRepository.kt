package ru.mycapital.app.data

import androidx.room.withTransaction

class PortfolioRepository(private val database: PortfolioDatabase) {
    private val dao = database.portfolioDao()

    suspend fun recordTrade(
        assetId: Long,
        isBuy: Boolean,
        quantity: Double,
        price: Double,
        commission: Double = 0.0,
        note: String = ""
    ) {
        require(quantity > 0.0) { "Количество должно быть больше нуля" }
        require(price >= 0.0) { "Цена не может быть отрицательной" }
        require(commission >= 0.0) { "Комиссия не может быть отрицательной" }

        database.withTransaction {
            dao.ensureCashBalance(CashBalanceEntity())
            val cash = dao.getCashBalance() ?: 0.0
            val grossAmount = quantity * price

            val nextCash = if (isBuy) {
                val totalCost = grossAmount + commission
                require(cash + 0.0000001 >= totalCost) {
                    "Недостаточно свободных денег. Нужно ${"%.2f".format(java.util.Locale.US, totalCost)} ₽, доступно ${"%.2f".format(java.util.Locale.US, cash)} ₽"
                }
                (cash - totalCost).coerceAtLeast(0.0)
            } else {
                val proceeds = grossAmount - commission
                require(proceeds >= 0.0) { "Комиссия не может превышать сумму продажи" }
                cash + proceeds
            }

            val asset = requireNotNull(dao.getAsset(assetId)) { "Актив не найден" }
            val nextQuantity = if (isBuy) asset.quantity + quantity else asset.quantity - quantity
            require(nextQuantity >= -0.0000001) { "Недостаточно бумаг для продажи" }

            val nextAverage = when {
                nextQuantity <= 0.0000001 -> 0.0
                isBuy -> ((asset.quantity * asset.averagePrice) + grossAmount + commission) / nextQuantity
                else -> asset.averagePrice
            }

            dao.updateCashBalance(nextCash)
            dao.updateAsset(
                asset.copy(
                    quantity = nextQuantity.coerceAtLeast(0.0),
                    averagePrice = nextAverage,
                    currentPrice = price,
                    updatedAt = System.currentTimeMillis()
                )
            )
            dao.insertOperation(
                OperationEntity(
                    assetId = asset.id,
                    operationType = if (isBuy) "Покупка" else "Продажа",
                    ticker = asset.ticker,
                    quantity = quantity,
                    price = price,
                    commission = commission,
                    note = note
                )
            )
        }
    }

    suspend fun recordCashFlow(isDeposit: Boolean, amount: Double, note: String = "") {
        require(amount > 0.0) { "Сумма должна быть больше нуля" }
        database.withTransaction {
            dao.ensureCashBalance(CashBalanceEntity())
            val current = dao.getCashBalance() ?: 0.0
            val next = if (isDeposit) current + amount else current - amount
            require(next >= -0.0000001) { "Недостаточно свободных денег" }
            dao.updateCashBalance(next.coerceAtLeast(0.0))
            dao.insertOperation(
                OperationEntity(
                    assetId = null,
                    operationType = if (isDeposit) "Пополнение" else "Вывод",
                    ticker = "RUB",
                    quantity = 1.0,
                    price = amount,
                    commission = 0.0,
                    note = note
                )
            )
        }
    }
}
