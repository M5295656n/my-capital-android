package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
