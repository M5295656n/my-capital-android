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
    val imoexHistory = java.util.TreeMap(imoexByDate)
    val mcftrHistory = java.util.TreeMap(mcftrByDate)
    // Keep every portfolio valuation date. If an index has no observation on
    // that date (for example, its ISS series is published later), use its most
    // recent known close rather than truncating the entire comparison to the
    // intersection of all three calendars.
    val dates = portfolioByDate.keys.sorted()
    if (dates.size < 2) return emptyList()

    fun indexCloseOnOrBefore(history: java.util.NavigableMap<String, Double>, date: String): Double? =
        history.floorEntry(date)?.value

    val first = dates.first()
    val firstPortfolio = portfolioByDate[first] ?: return emptyList()
    val firstImoex = indexCloseOnOrBefore(imoexHistory, first) ?: return emptyList()
    val firstMcftr = indexCloseOnOrBefore(mcftrHistory, first) ?: return emptyList()
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
        val indexPrice = indexCloseOnOrBefore(imoexHistory, date) ?: return@forEach
        val totalReturnIndex = indexCloseOnOrBefore(mcftrHistory, date) ?: return@forEach
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


/** Returns the portfolio valuations inside a rolling window ending at the latest available date. */
internal fun selectBenchmarkHistoryPeriod(
    history: List<PortfolioMarketHistoryPoint>,
    months: Long?
): List<PortfolioMarketHistoryPoint> {
    val ordered = history.sortedBy { it.dateKey }
    if (months == null) return ordered
    require(months > 0) { "Период должен быть положительным" }
    val lastDate = ordered.lastOrNull()?.dateKey?.let(java.time.LocalDate::parse) ?: return emptyList()
    val cutoff = lastDate.minusMonths(months)
    return ordered.filter { !java.time.LocalDate.parse(it.dateKey).isBefore(cutoff) }
}
