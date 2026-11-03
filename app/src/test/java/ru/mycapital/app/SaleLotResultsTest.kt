package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mycapital.app.data.OperationEntity

class SaleLotResultsTest {
    @Test
    fun saleProfitUsesFifoCostAndBothSideCommissions() {
        val result = calculateSaleLotResults(
            listOf(
                trade(1, "Покупка", 2.0, 100.0, 1L, 2.0),
                trade(2, "Покупка", 2.0, 110.0, 2L, 2.0),
                trade(3, "Продажа", 3.0, 120.0, 3L, 3.0)
            )
        ).single()

        // Buy lots cost 101 and 111 per share. Sale commission is allocated to matched shares.
        assertEquals(3.0, result.matchedQuantity, 0.000001)
        assertEquals(0.0, result.unmatchedQuantity, 0.000001)
        assertEquals(313.0, result.costBasis, 0.000001)
        assertEquals(357.0, result.netProceeds, 0.000001)
        assertEquals(44.0, result.profit, 0.000001)
    }

    @Test
    fun unmatchedSaleQuantityHasNoInventedCostBasis() {
        val result = calculateSaleLotResults(
            listOf(trade(1, "Продажа", 3.0, 120.0, 1L, 1.5))
        ).single()

        assertEquals(0.0, result.matchedQuantity, 0.000001)
        assertEquals(3.0, result.unmatchedQuantity, 0.000001)
        assertEquals(0.0, result.costBasis, 0.000001)
        assertEquals(0.0, result.netProceeds, 0.000001)
        assertEquals(0.0, result.profit, 0.000001)
    }

    @Test
    fun salesAreMatchedOnlyWithinSameBrokerAccount() {
        val result = calculateSaleLotResults(
            listOf(
                trade(1, "Покупка", 2.0, 100.0, 1L, sourceKey = "SBER:ACCOUNT-A:TRADE:buy"),
                trade(2, "Продажа", 1.0, 120.0, 2L, sourceKey = "SBER:ACCOUNT-B:TRADE:sell")
            )
        ).single()

        assertEquals(0.0, result.matchedQuantity, 0.000001)
        assertEquals(1.0, result.unmatchedQuantity, 0.000001)
    }

    @Test
    fun saleCommissionIsAllocatedOnlyToMatchedPart() {
        val result = calculateSaleLotResults(
            listOf(
                trade(1, "Покупка", 1.0, 100.0, 1L),
                trade(2, "Продажа", 2.0, 120.0, 2L, 2.0)
            )
        ).single()

        assertEquals(1.0, result.matchedQuantity, 0.000001)
        assertEquals(1.0, result.unmatchedQuantity, 0.000001)
        assertEquals(119.0, result.netProceeds, 0.000001)
        assertEquals(19.0, result.profit, 0.000001)
    }

    private fun trade(
        id: Long,
        type: String,
        quantity: Double,
        price: Double,
        date: Long,
        commission: Double = 0.0,
        sourceKey: String? = null
    ) = OperationEntity(
        id = id,
        assetId = null,
        operationType = type,
        ticker = "SBER",
        quantity = quantity,
        price = price,
        commission = commission,
        operationDate = date,
        sourceKey = sourceKey
    )
}
