package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mycapital.app.data.AssetEntity

class PortfolioAllocationCalculationsTest {
    @Test
    fun groupsAssetsByTypeAndIncludesCashInPortfolioShare() {
        val assets = listOf(
            asset("SBER", "Акция", 2.0, 100.0),
            asset("LKOH", "Акция", 1.0, 300.0),
            asset("SU26238", "Облигация", 1.0, 200.0)
        )

        val result = calculatePortfolioAllocation(assets, 200.0)

        assertEquals(listOf("Акция", "Облигация", "Денежные средства"), result.map { it.label })
        assertEquals(500.0, result[0].value, 0.000001)
        assertEquals(500.0 / 900.0, result[0].share, 0.000001)
        assertEquals(200.0 / 900.0, result[1].share, 0.000001)
        assertEquals(200.0 / 900.0, result[2].share, 0.000001)
        assertEquals(1.0, result.sumOf { it.share }, 0.000001)
    }

    @Test
    fun blankAssetTypeIsGroupedAsOtherAndZeroValuesAreOmitted() {
        val assets = listOf(
            asset("AAA", "", 1.0, 50.0),
            asset("BBB", "Фонд", 0.0, 100.0)
        )

        val result = calculatePortfolioAllocation(assets, 0.0)

        assertEquals(1, result.size)
        assertEquals("Прочее", result.single().label)
        assertEquals(1.0, result.single().share, 0.000001)
    }

    @Test
    fun emptyPortfolioReturnsNoAllocation() {
        assertTrue(calculatePortfolioAllocation(emptyList(), 0.0).isEmpty())
    }

    private fun asset(ticker: String, type: String, quantity: Double, price: Double) =
        AssetEntity(
            ticker = ticker,
            name = ticker,
            assetType = type,
            quantity = quantity,
            averagePrice = price,
            currentPrice = price
        )
}
