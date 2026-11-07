package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mycapital.app.data.AssetEntity

class AnalyticsCalculationsTest {
    private fun asset(
        ticker: String,
        type: String = "Акция",
        quantity: Double = 1.0,
        price: Double = 100.0
    ) = AssetEntity(
        ticker = ticker,
        name = ticker,
        assetType = type,
        quantity = quantity,
        averagePrice = price,
        currentPrice = price
    )

    @Test
    fun classifiesEquitiesBondsMoneyMarketGoldAndUnknown() {
        assertEquals(PortfolioAssetClass.EQUITIES, classifyPortfolioAsset(asset("SBER")))
        assertEquals(PortfolioAssetClass.EQUITIES, classifyPortfolioAsset(asset("EQMX", "Фонд")))
        assertEquals(PortfolioAssetClass.FIXED_INCOME, classifyPortfolioAsset(asset("SBMM", "Фонд")))
        assertEquals(PortfolioAssetClass.FIXED_INCOME, classifyPortfolioAsset(asset("SU26238RMFS4", "Облигация")))
        assertEquals(PortfolioAssetClass.GOLD, classifyPortfolioAsset(asset("GOLD", "Фонд")))
        assertEquals(PortfolioAssetClass.OTHER, classifyPortfolioAsset(asset("UNKNOWN", "Фонд")))
    }

    @Test
    fun classSlicesIncludeCashAndSumToOne() {
        val slices = calculatePortfolioClassSlices(
            listOf(asset("SBER", quantity = 1.0, price = 100.0), asset("SBMM", "Фонд", 1.0, 100.0)),
            cashBalance = 100.0
        )
        assertEquals(3, slices.size)
        assertEquals(1.0, slices.sumOf { it.share }, 0.000001)
        assertEquals(100.0, slices.first { it.assetClass == PortfolioAssetClass.EQUITIES }.value, 0.000001)
        assertEquals(100.0, slices.first { it.assetClass == PortfolioAssetClass.CASH }.value, 0.000001)
    }

    @Test
    fun unknownEquitySectorIsExplicitlyUnallocated() {
        val sectors = calculatePortfolioSectorSlices(
            listOf(asset("SBER"), asset("NOSECTOR")),
            totalPortfolioValue = 300.0
        )
        assertEquals(2, sectors.size)
        assertEquals("Не распределено", sectors.first { it.sector == "Не распределено" }.sector)
        assertEquals(200.0 / 300.0, sectors.sumOf { it.shareOfPortfolio }, 0.000001)
    }

    @Test
    fun sectorAggregationAndConcentrationAreSortedByValue() {
        val assets = listOf(
            asset("SBER", quantity = 1.0, price = 100.0),
            asset("SBERP", quantity = 1.0, price = 50.0),
            asset("LKOH", quantity = 1.0, price = 200.0)
        )
        val sectors = calculatePortfolioSectorSlices(assets, 500.0)
        assertEquals("Нефть и газ", sectors.first().sector)
        assertEquals(200.0, sectors.first().value, 0.000001)
        val concentration = calculatePortfolioConcentration(assets, 500.0)
        assertEquals("LKOH", concentration.first().ticker)
        assertEquals(200.0, concentration.first().value, 0.000001)
        assertEquals(150.0, concentration.first { it.ticker == "SBER" }.value, 0.000001)
    }


    @Test
    fun compositionSnapshotsContainClassAndSectorDimensions() {
        val rows = buildPortfolioCompositionSnapshots(
            dateKey = "2026-11-08",
            accountId = "ALL",
            assets = listOf(asset("SBER", quantity = 2.0, price = 100.0), asset("SBMM", "Фонд", 1.0, 100.0)),
            cashBalance = 100.0
        )
        assertEquals(true, rows.any { it.dimension == "CLASS" && it.category == "EQUITIES" && it.value == 200.0 })
        assertEquals(true, rows.any { it.dimension == "CLASS" && it.category == "CASH" && it.value == 100.0 })
        assertEquals(true, rows.any { it.dimension == "SECTOR" && it.category == "Финансы" && it.value == 200.0 })
    }

    @Test
    fun emptyOrZeroValuePortfolioHasNoSlices() {
        assertEquals(0, calculatePortfolioClassSlices(emptyList(), 0.0).size)
        assertEquals(0, calculatePortfolioSectorSlices(listOf(asset("SBER")), 0.0).size)
        assertEquals(0, calculatePortfolioConcentration(listOf(asset("SBER")), 0.0).size)
        assertNull(sectorForAnalyticsTicker("NOTREAL"))
    }
}
