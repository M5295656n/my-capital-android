package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mycapital.app.data.OperationEntity

class PurchaseLotBalancesTest {
    @Test
    fun fifoConsumesOldestPurchaseFirstAndKeepsPartialLot() {
        val result = calculateRemainingPurchaseLots(
            listOf(
                trade(1, "Покупка", 3.0, 100.0, 1L),
                trade(2, "Покупка", 2.0, 110.0, 2L),
                trade(3, "Продажа", 4.0, 120.0, 3L)
            )
        )

        assertEquals(0.0, result.remainingByOperationId[1L]!!, 0.000001)
        assertEquals(1.0, result.remainingByOperationId[2L]!!, 0.000001)
        assertEquals(0.0, result.unmatchedSaleQuantity, 0.000001)
    }

    @Test
    fun salesAreMatchedOnlyWithinSameBrokerAccount() {
        val result = calculateRemainingPurchaseLots(
            listOf(
                trade(1, "Покупка", 2.0, 100.0, 1L, "SBER:ACCOUNT-A:TRADE:buy"),
                trade(2, "Продажа", 1.0, 120.0, 2L, "SBER:ACCOUNT-B:TRADE:sell")
            )
        )

        assertEquals(2.0, result.remainingByOperationId[1L]!!, 0.000001)
        assertEquals(1.0, result.unmatchedSaleQuantity, 0.000001)
    }

    @Test
    fun manualTradesDoNotConsumeImportedLots() {
        val result = calculateRemainingPurchaseLots(
            listOf(
                trade(1, "Покупка", 2.0, 100.0, 1L, "SBER:ACCOUNT-A:TRADE:buy"),
                trade(2, "Продажа", 1.0, 120.0, 2L)
            )
        )

        assertEquals(2.0, result.remainingByOperationId[1L]!!, 0.000001)
        assertEquals(1.0, result.unmatchedSaleQuantity, 0.000001)
    }

    @Test
    fun partialSaleLeavesRemainderInOriginalPurchase() {
        val result = calculateRemainingPurchaseLots(
            listOf(
                trade(1, "Покупка", 5.0, 100.0, 1L),
                trade(2, "Продажа", 2.0, 120.0, 2L)
            )
        )

        assertEquals(3.0, result.remainingByOperationId[1L]!!, 0.000001)
    }

    private fun trade(
        id: Long,
        type: String,
        quantity: Double,
        price: Double,
        date: Long,
        sourceKey: String? = null
    ) = OperationEntity(
        id = id,
        assetId = null,
        operationType = type,
        ticker = "SBER",
        quantity = quantity,
        price = price,
        operationDate = date,
        sourceKey = sourceKey
    )
}
