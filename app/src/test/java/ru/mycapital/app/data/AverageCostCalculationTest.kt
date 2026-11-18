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
    fun saleKeepsFifoCostForRemainingShares() {
        val operations = listOf(
            operation("Покупка", quantity = 3.0, price = 100.0, commission = 0.0, date = 1L),
            operation("Покупка", quantity = 1.0, price = 140.0, commission = 0.0, date = 2L),
            operation("Продажа", quantity = 2.0, price = 120.0, commission = 0.0, date = 3L)
        )

        assertEquals(120.0, calculateAverageCostFromCompleteHistory(operations, 2.0)!!, 0.000001)
    }

    @Test
    fun refusesPartialHistoryWhenOpeningPositionIsMissing() {
        val operations = listOf(
            operation("Покупка", quantity = 2.0, price = 100.0, commission = 0.0, date = 1L)
        )

        assertNull(calculateAverageCostFromCompleteHistory(operations, 5.0))
    }


    @Test
    fun usesFifoCostForRemainingLotAfterDifferentPricePurchaseAndSale() {
        val operations = listOf(
            operation("Покупка", quantity = 1.0, price = 100.0, commission = 0.0, date = 1L),
            operation("Покупка", quantity = 1.0, price = 200.0, commission = 0.0, date = 2L),
            operation("Продажа", quantity = 1.0, price = 150.0, commission = 0.0, date = 3L)
        )

        assertEquals(200.0, calculateAverageCostFromCompleteHistory(operations, 1.0)!!, 0.000001)
    }

    @Test
    fun fifoCostBasisDoesNotCrossBrokerAccounts() {
        val operations = listOf(
            operation("Покупка", 1.0, 100.0, 0.0, 1L, id = 1L, accountId = "ACCOUNT-A"),
            operation("Покупка", 1.0, 200.0, 0.0, 2L, id = 2L, accountId = "ACCOUNT-B"),
            operation("Продажа", 1.0, 150.0, 0.0, 3L, id = 3L, accountId = "ACCOUNT-B")
        )

        assertEquals(100.0, calculateAverageCostFromCompleteHistory(operations, 1.0)!!, 0.000001)
    }

    @Test
    fun rejectsNonFiniteTradeHistoryInsteadOfReturningInvalidAverage() {
        val operations = listOf(
            operation("Покупка", quantity = 1.0, price = Double.NaN, commission = 0.0, date = 1L)
        )

        assertNull(calculateAverageCostFromCompleteHistory(operations, 1.0))
    }

    private fun operation(
        type: String,
        quantity: Double,
        price: Double,
        commission: Double,
        date: Long,
        id: Long = 0L,
        accountId: String? = null
    ) = OperationEntity(
        id = id,
        assetId = 1L,
        operationType = type,
        ticker = "TEST",
        quantity = quantity,
        price = price,
        commission = commission,
        operationDate = date,
        accountId = accountId
    )
}
