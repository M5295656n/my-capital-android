package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mycapital.app.data.AssetEntity

class TargetPortfolioCalculationsTest {
    private fun asset(ticker: String, type: String, quantity: Double, price: Double) = AssetEntity(
        ticker = ticker, name = ticker, assetType = type, quantity = quantity,
        averagePrice = price, currentPrice = price
    )

    @Test
    fun actualWeightsUseOnlyIndividualSharesAndIncludeMissingPlanPositions() {
        val assets = listOf(
            asset("SBER", "Акция", 10.0, 100.0),
            asset("LKOH", "Акция", 5.0, 200.0),
            asset("SBMM", "Фонд денежного рынка", 100.0, 10.0),
            asset("SBGD", "Золото", 10.0, 100.0),
            asset("EQMX", "Фонд акций", 100.0, 10.0),
            asset("X5", "Акция", 0.0, 300.0)
        )
        val plan = listOf(
            TargetPortfolioPosition("SBER", "Сбербанк", 50.0),
            TargetPortfolioPosition("LKOH", "Лукойл", 30.0),
            TargetPortfolioPosition("X5", "X5", 20.0)
        )

        val result = calculateTargetPortfolioSummary(assets, plan)
        val rows = result.rows.associateBy { it.position.ticker }

        assertEquals(2000.0, result.equityValue, 0.0001)
        assertEquals(100.0, result.targetWeightTotal, 0.0001)
        assertEquals(50.0, rows.getValue("SBER").actualWeight, 0.0001)
        assertEquals(50.0, rows.getValue("LKOH").actualWeight, 0.0001)
        assertEquals(0.0, rows.getValue("X5").actualWeight, 0.0001)
        assertEquals(50.0, result.executionPercent, 0.0001)
    }

    @Test
    fun zeroAndSoldPositionsDoNotAffectActualWeight() {
        val assets = listOf(
            asset("SBER", "Акция", 10.0, 100.0),
            asset("LKOH", "Акция", 0.0, 500.0)
        )
        val result = calculateTargetPortfolioSummary(
            assets,
            listOf(TargetPortfolioPosition("SBER", "Сбербанк", 70.0), TargetPortfolioPosition("LKOH", "Лукойл", 30.0))
        )
        assertEquals(1000.0, result.equityValue, 0.0001)
        assertEquals(100.0, result.rows.first { it.position.ticker == "SBER" }.actualWeight, 0.0001)
        assertEquals(0.0, result.rows.first { it.position.ticker == "LKOH" }.actualWeight, 0.0001)
    }

    @Test
    fun targetWeightMustTotalExactlyOneHundredPercent() {
        assertTrue(isTargetPortfolioWeightTotalValid(100.0))
        assertTrue(isTargetPortfolioWeightTotalValid(99.9995))
        assertFalse(isTargetPortfolioWeightTotalValid(99.0))
        assertFalse(isTargetPortfolioWeightTotalValid(101.0))
    }
}
