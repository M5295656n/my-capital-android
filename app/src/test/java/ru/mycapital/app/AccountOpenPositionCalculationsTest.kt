package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mycapital.app.data.OperationEntity

class AccountOpenPositionCalculationsTest {
    @Test
    fun calculatesRemainingFifoCostBasisIncludingBuyFees() {
        val result = calculateAccountOpenPositionResult(
            operations = listOf(
                trade("Покупка", 10.0, 100.0, 10.0, 1L),
                trade("Покупка", 5.0, 120.0, 5.0, 2L),
                trade("Продажа", 12.0, 130.0, 12.0, 3L)
            ),
            actualQuantities = mapOf("SBER" to 3.0),
            currentPrices = mapOf("SBER" to 140.0)
        )

        assertEquals(363.0, result.costBasis, 0.000001)
        assertEquals(420.0, result.marketValue, 0.000001)
        assertEquals(57.0, result.profit, 0.000001)
        assertEquals(0.0, result.unmatchedQuantity, 0.000001)
    }

    @Test
    fun flagsOpeningPositionsWithoutPurchaseHistory() {
        val result = calculateAccountOpenPositionResult(
            operations = listOf(trade("Покупка", 3.0, 100.0, 0.0, 1L)),
            actualQuantities = mapOf("SBER" to 5.0, "LKOH" to 2.0),
            currentPrices = mapOf("SBER" to 110.0, "LKOH" to 7000.0)
        )

        assertEquals(300.0, result.costBasis, 0.000001)
        assertEquals(330.0, result.marketValue, 0.000001)
        assertEquals(30.0, result.profit, 0.000001)
        assertEquals(4.0, result.unmatchedQuantity, 0.000001)
    }

    @Test
    fun flagsSalesWithoutMatchingPurchases() {
        val result = calculateAccountOpenPositionResult(
            operations = listOf(trade("Продажа", 2.0, 100.0, 0.0, 1L)),
            actualQuantities = emptyMap(),
            currentPrices = emptyMap()
        )

        assertEquals(0.0, result.profit, 0.000001)
        assertEquals(2.0, result.unmatchedQuantity, 0.000001)
    }

    private fun trade(
        type: String,
        quantity: Double,
        price: Double,
        commission: Double,
        date: Long
    ) = OperationEntity(
        assetId = null,
        operationType = type,
        ticker = "SBER",
        quantity = quantity,
        price = price,
        commission = commission,
        operationDate = date
    )
}
