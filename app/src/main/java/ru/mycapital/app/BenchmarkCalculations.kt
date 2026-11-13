package ru.mycapital.app

import ru.mycapital.app.data.MoexIndexPoint

internal data class BenchmarkComparisonPoint(
    val dateKey: String,
    val portfolioValue: Double,
    val imoexValue: Double,
    val mcftrValue: Double
)

/**
 * Builds investable benchmark portfolios using the same external cash flows as
 * the real portfolio. Flows are applied at the end of each day, matching the
 * cash-flow convention used by buildPortfolioMarketHistory.
 */
internal fun buildCashFlowAdjustedBenchmarkSeries(
    history: List<PortfolioMarketHistoryPoint>,
    imoex: List<MoexIndexPoint>,
    mcftr: List<MoexIndexPoint>
): List<BenchmarkComparisonPoint> {
    val portfolioByDate = history.associateBy { it.dateKey }
    val imoexByDate = imoex.associate { it.date to it.value }
    val mcftrByDate = mcftr.associate { it.date to it.value }
    val dates = portfolioByDate.keys.intersect(imoexByDate.keys)
        .intersect(mcftrByDate.keys).sorted()
    if (dates.size < 2) return emptyList()

    val first = dates.first()
    val firstPortfolio = portfolioByDate[first] ?: return emptyList()
    val firstImoex = imoexByDate[first] ?: return emptyList()
    val firstMcftr = mcftrByDate[first] ?: return emptyList()
    if (firstImoex <= 0.0 || firstMcftr <= 0.0) return emptyList()

    var imoexValue = firstPortfolio.totalValue
    var mcftrValue = firstPortfolio.totalValue
    var previousImoex = firstImoex
    var previousMcftr = firstMcftr
    var previousCumulativeFlow = firstPortfolio.investedCapital

    val result = mutableListOf(
        BenchmarkComparisonPoint(first, firstPortfolio.totalValue, imoexValue, mcftrValue)
    )
    dates.drop(1).forEach { date ->
        val portfolioPoint = portfolioByDate[date] ?: return@forEach
        val indexPrice = imoexByDate[date] ?: return@forEach
        val totalReturnIndex = mcftrByDate[date] ?: return@forEach
        if (previousImoex <= 0.0 || previousMcftr <= 0.0 ||
            indexPrice <= 0.0 || totalReturnIndex <= 0.0) return@forEach

        val externalFlow = portfolioPoint.investedCapital - previousCumulativeFlow
        // First accrue the index return on capital held through the session,
        // then apply that day's external flow at the close.
        imoexValue = imoexValue * (indexPrice / previousImoex) + externalFlow
        mcftrValue = mcftrValue * (totalReturnIndex / previousMcftr) + externalFlow
        result += BenchmarkComparisonPoint(date, portfolioPoint.totalValue, imoexValue, mcftrValue)

        previousImoex = indexPrice
        previousMcftr = totalReturnIndex
        previousCumulativeFlow = portfolioPoint.investedCapital
    }
    return result
}
