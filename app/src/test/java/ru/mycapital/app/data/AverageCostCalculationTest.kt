package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AverageCostCalculationTest {
    @Test
    fun calculatesWeightedAverageIncludingBuyCommissions() {
        val operations = listOf(
            operation("Покупка", quantity = 2.0, price = 100.0, commission = 1.0, date = 1L),
            operation("Покупка", quantity = 1.0, price = 130.0, commission = 1.0, date = 2L)
        )

        assertEquals(110.6666667, calculateAverageCostFromCompleteHistory(operations, 3.0)!!, 0.000001)
    }

    @Test
    fun saleKeepsWeightedAverageCostForRemainingShares() {
        val operations = listOf(
            operation("Покупка", quantity = 3.0, price = 100.0, commission = 0.0, date = 1L),
            operation("Покупка", quantity = 1.0, price = 140.0, commission = 0.0, date = 2L),
            operation("Продажа", quantity = 2.0, price = 120.0, commission = 0.0, date = 3L)
        )

        assertEquals(110.0, calculateAverageCostFromCompleteHistory(operations, 2.0)!!, 0.000001)
    }

    @Test
    fun refusesPartialHistoryWhenOpeningPositionIsMissing() {
        val operations = listOf(
            operation("Покупка", quantity = 2.0, price = 100.0, commission = 0.0, date = 1L)
        )

        assertNull(calculateAverageCostFromCompleteHistory(operations, 5.0))
    }

    private fun operation(
        type: String,
        quantity: Double,
        price: Double,
        commission: Double,
        date: Long
    ) = OperationEntity(
        assetId = 1L,
        operationType = type,
        ticker = "TEST",
        quantity = quantity,
        price = price,
        commission = commission,
        operationDate = date
    )
}
