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
            val asset = requireNotNull(dao.getAsset(assetId)) { "Актив не найден" }
            val nextQuantity = if (isBuy) asset.quantity + quantity else asset.quantity - quantity
            require(nextQuantity >= -0.0000001) { "Недостаточно бумаг для продажи" }

            val nextAverage = when {
                nextQuantity <= 0.0000001 -> 0.0
                isBuy -> ((asset.quantity * asset.averagePrice) + (quantity * price) + commission) / nextQuantity
                else -> asset.averagePrice
            }

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
}
