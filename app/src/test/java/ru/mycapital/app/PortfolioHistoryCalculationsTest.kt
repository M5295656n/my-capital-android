package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mycapital.app.data.PortfolioSnapshotEntity

class PortfolioHistoryCalculationsTest {
    private fun snapshot(date: String, total: Double, flow: Double = 0.0) =
        PortfolioSnapshotEntity(
            dateKey = date,
            capturedAt = 0L,
            assetsValue = total,
            cashValue = 0.0,
            totalValue = total,
            externalFlow = flow
        )

    @Test
    fun returnCalculationExcludesExternalDeposit() {
        val points = calculatePortfolioHistoryPoints(
            listOf(snapshot("2026-11-01", 1_000.0), snapshot("2026-11-02", 1_500.0, 500.0)),
            PortfolioHistoryRange.ALL
        )

        assertEquals(100.0, points.last().nav, 0.000001)
    }

    @Test
    fun returnCalculationIncludesMarketGrowthAfterDeposit() {
        val points = calculatePortfolioHistoryPoints(
            listOf(snapshot("2026-11-01", 1_000.0), snapshot("2026-11-02", 1_600.0, 500.0)),
            PortfolioHistoryRange.ALL
        )

        assertEquals(110.0, points.last().nav, 0.000001)
    }

    @Test
    fun selectedRangeFiltersOlderSnapshotsAndResetsBase() {
        val points = calculatePortfolioHistoryPoints(
            listOf(
                snapshot("2026-01-01", 1_000.0),
                snapshot("2026-10-01", 1_100.0),
                snapshot("2026-11-01", 1_210.0)
            ),
            PortfolioHistoryRange.ONE_MONTH
        )

        assertEquals(2, points.size)
        assertEquals("2026-10-01", points.first().dateKey)
        assertEquals(100.0, points.first().nav, 0.000001)
        assertEquals(110.0, points.last().nav, 0.000001)
    }

    @Test
    fun snapshotsAreSortedChronologically() {
        val points = calculatePortfolioHistoryPoints(
            listOf(snapshot("2026-11-02", 1_100.0), snapshot("2026-11-01", 1_000.0)),
            PortfolioHistoryRange.ALL
        )

        assertEquals("2026-11-01", points.first().dateKey)
        assertTrue(points.last().totalValue > points.first().totalValue)
    }

    @Test
    fun allRangeKeepsFullHistory() {
        val points = calculatePortfolioHistoryPoints(
            listOf(snapshot("2025-01-01", 1_000.0), snapshot("2026-11-01", 1_100.0)),
            PortfolioHistoryRange.ALL
        )

        assertEquals(2, points.size)
    }
}
