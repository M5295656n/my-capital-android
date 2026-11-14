package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mycapital.app.data.OperationEntity

class RealizedTradingCalculationsTest {
    @Test
    fun fifoDoesNotMixSameTickerAcrossBrokerAccounts() {
        val result = calculateRealizedTradingResult(
            listOf(
                trade("Покупка", 1.0, 100.0, date = 1L, sourceKey = "SBER:MAIN-001:TRADE:buy"),
                trade("Покупка", 1.0, 200.0, date = 2L, sourceKey = "SBER:IIS-003:TRADE:buy"),
                trade("Продажа", 1.0, 150.0, date = 3L, sourceKey = "SBER:IIS-003:TRADE:sell")
            )
        )

        assertEquals(-50.0, result.profit, 0.000001)
        assertEquals(0.0, result.unmatchedQuantity, 0.000001)
    }

    @Test
    fun manualTradesDoNotMatchImportedTradesWithUnknownAccount() {
        val result = calculateRealizedTradingResult(
            listOf(
                trade("Покупка", 1.0, 100.0, date = 1L),
                trade("Продажа", 1.0, 150.0, date = 2L, sourceKey = "SBER:MAIN-001:TRADE:sell")
            )
        )

        assertEquals(0.0, result.profit, 0.000001)
        assertEquals(1.0, result.unmatchedQuantity, 0.000001)
    }

    @Test
    fun fifoUsesCommissionAdjustedCostsWithinOneAccount() {
        val result = calculateRealizedTradingResult(
            listOf(
                trade("Покупка", 2.0, 100.0, commission = 2.0, date = 1L, sourceKey = "SBER:MAIN-001:TRADE:buy-1"),
                trade("Покупка", 1.0, 120.0, commission = 1.0, date = 2L, sourceKey = "SBER:MAIN-001:TRADE:buy-2"),
                trade("Продажа", 2.0, 130.0, commission = 2.0, date = 3L, sourceKey = "SBER:MAIN-001:TRADE:sell")
            )
        )

        assertEquals(56.0, result.profit, 0.000001)
        assertEquals(0.0, result.unmatchedQuantity, 0.000001)
    }

    @Test
    fun ignoresNonFiniteBuyAndReportsFollowingSaleAsUnmatched() {
        val result = calculateRealizedTradingResult(
            listOf(
                trade("Покупка", 1.0, Double.NaN, date = 1L),
                trade("Продажа", 1.0, 150.0, date = 2L)
            )
        )

        assertEquals(0.0, result.profit, 0.000001)
        assertEquals(1.0, result.unmatchedQuantity, 0.000001)
        assertTrue(result.profit.isFinite())
    }

    @Test
    fun rejectsNonFiniteSaleValuesWithoutContaminatingProfit() {
        val result = calculateRealizedTradingResult(
            listOf(
                trade("Покупка", 2.0, 100.0, date = 1L),
                trade("Продажа", 1.0, Double.POSITIVE_INFINITY, date = 2L)
            )
        )

        assertEquals(0.0, result.profit, 0.000001)
        assertEquals(1.0, result.unmatchedQuantity, 0.000001)
        assertTrue(result.profit.isFinite())
    }

    @Test
    fun skipsTradeWhoseFiniteInputsOverflowDuringCalculation() {
        val result = calculateRealizedTradingResult(
            listOf(
                trade("Покупка", 2.0, Double.MAX_VALUE, date = 1L),
                trade("Продажа", 1.0, 100.0, date = 2L)
            )
        )

        assertEquals(0.0, result.profit, 0.000001)
        assertEquals(1.0, result.unmatchedQuantity, 0.000001)
        assertFalse(result.profit.isNaN())
        assertFalse(result.profit.isInfinite())
    }

    @Test
    fun overflowingSaleDoesNotPartiallyConsumeEarlierFifoLots() {
        val result = calculateRealizedTradingResult(
            listOf(
                trade("Покупка", 1.0, 0.0, date = 1L),
                trade("Продажа", 1.0, Double.MAX_VALUE / 2.0, date = 2L),
                trade("Покупка", 1.0, 0.0, date = 3L),
                trade("Покупка", 1.0, Double.MAX_VALUE / 4.0, date = 4L),
                trade("Продажа", 2.0, Double.MAX_VALUE / 2.0, date = 5L),
                // This must still match the first lot, proving the overflowing
                // two-unit sale did not consume any part of its FIFO allocation.
                trade("Продажа", 1.0, 0.0, date = 6L)
            )
        )

        assertEquals(Double.MAX_VALUE / 2.0, result.profit, 0.0)
        assertEquals(2.0, result.unmatchedQuantity, 0.000001)
        assertTrue(result.profit.isFinite())
    }

    @Test
    fun unmatchedQuantitySaturatesInsteadOfOverflowing() {
        val result = calculateRealizedTradingResult(
            listOf(
                trade("Продажа", Double.MAX_VALUE, 0.0, date = 1L),
                trade("Продажа", Double.MAX_VALUE, 0.0, date = 2L)
            )
        )

        assertEquals(Double.MAX_VALUE, result.unmatchedQuantity, 0.0)
        assertTrue(result.unmatchedQuantity.isFinite())
    }

    @Test
    fun accountIdIsReadOnlyFromScopedTradeKeys() {
        assertEquals("MAIN-001", brokerAccountIdFromTradeSourceKey("SBER:MAIN-001:TRADE:123"))
        assertNull(brokerAccountIdFromTradeSourceKey(null))
        assertNull(brokerAccountIdFromTradeSourceKey("SBER:TRADE:123"))
        assertNull(brokerAccountIdFromTradeSourceKey("SBER:MAIN-001:CASH:123"))
    }

    private fun trade(
        type: String,
        quantity: Double,
        price: Double,
        commission: Double = 0.0,
        date: Long,
        sourceKey: String? = null
    ) = OperationEntity(
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
