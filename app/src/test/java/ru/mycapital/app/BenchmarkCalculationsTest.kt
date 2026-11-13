package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mycapital.app.data.MoexIndexPoint

class BenchmarkCalculationsTest {
    private fun point(date: String, total: Double, flows: Double) =
        PortfolioMarketHistoryPoint(dateKey = date, totalValue = total, investedCapital = flows, nav = 100.0)

    @Test
    fun benchmarkReceivesSameExternalFlowsAsPortfolio() {
        val history = listOf(
            point("2026-01-01", 1_000.0, 0.0),
            point("2026-01-02", 1_500.0, 500.0),
            point("2026-01-03", 1_470.0, 200.0)
        )
        val imoex = listOf(
            MoexIndexPoint("2026-01-01", 100.0),
            MoexIndexPoint("2026-01-02", 110.0),
            MoexIndexPoint("2026-01-03", 110.0)
        )
        val mcftr = listOf(
            MoexIndexPoint("2026-01-01", 200.0),
            MoexIndexPoint("2026-01-02", 220.0),
            MoexIndexPoint("2026-01-03", 242.0)
        )

        val result = buildCashFlowAdjustedBenchmarkSeries(history, imoex, mcftr)

        assertEquals(3, result.size)
        assertEquals(1_000.0, result[0].imoexValue, 0.000001)
        assertEquals(1_600.0, result[1].imoexValue, 0.000001)
        assertEquals(1_300.0, result[2].imoexValue, 0.000001)
        assertEquals(1_600.0, result[1].mcftrValue, 0.000001)
        assertEquals(1_460.0, result[2].mcftrValue, 0.000001)
        assertEquals(1_470.0, result.last().portfolioValue, 0.000001)
    }

    @Test
    fun withdrawalsReduceBenchmarkCapitalAtTheEndOfTheDay() {
        val history = listOf(
            point("2026-01-01", 1_000.0, 0.0),
            point("2026-01-02", 800.0, -200.0),
            point("2026-01-03", 880.0, -200.0)
        )
        val index = listOf(
            MoexIndexPoint("2026-01-01", 100.0),
            MoexIndexPoint("2026-01-02", 110.0),
            MoexIndexPoint("2026-01-03", 121.0)
        )

        val result = buildCashFlowAdjustedBenchmarkSeries(history, index, index)

        assertEquals(900.0, result[1].imoexValue, 0.000001)
        assertEquals(990.0, result[2].imoexValue, 0.000001)
    }

    @Test
    fun onlyCommonDatesAreUsedAndAtLeastTwoAreRequired() {
        val history = listOf(
            point("2026-01-01", 100.0, 0.0),
            point("2026-01-02", 110.0, 0.0),
            point("2026-01-03", 120.0, 0.0)
        )
        val index = listOf(
            MoexIndexPoint("2026-01-01", 100.0),
            MoexIndexPoint("2026-01-03", 120.0)
        )

        val result = buildCashFlowAdjustedBenchmarkSeries(history, index, index)

        assertEquals(listOf("2026-01-01", "2026-01-03"), result.map { it.dateKey })
        assertTrue(buildCashFlowAdjustedBenchmarkSeries(history.take(1), index, index).isEmpty())
    }
}
