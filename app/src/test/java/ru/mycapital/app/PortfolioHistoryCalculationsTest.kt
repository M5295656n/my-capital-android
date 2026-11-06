package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mycapital.app.data.PortfolioSnapshotEntity
import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.PayoutEntity

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
    fun assetXirrFlowsIncludeTradeFeesNetPayoutsExpensesAndRedemptionOnce() {
        val operations = listOf(
            OperationEntity(assetId = 1, operationType = "Покупка", ticker = "BOND", quantity = 1.0, price = 100.0, commission = 1.0, operationDate = 1L),
            OperationEntity(assetId = 1, operationType = "Продажа", ticker = "BOND", quantity = 0.5, price = 120.0, commission = 1.0, operationDate = 2L),
            OperationEntity(assetId = 1, operationType = "Налог", ticker = "BOND", quantity = 1.0, price = 2.0, operationDate = 3L),
            // Imported payout operation rows are intentionally ignored here;
            // their matching PayoutEntity records below are the source of truth.
            OperationEntity(assetId = 1, operationType = "Погашение", ticker = "BOND", quantity = 1.0, price = 100.0, operationDate = 4L)
        )
        val payouts = listOf(
            PayoutEntity(assetId = 1, ticker = "BOND", payoutType = "Купон", amountPerUnit = 12.0, quantity = 1.0, totalAmount = 12.0, taxAmount = 2.0, actualDate = 3L, status = "RECEIVED"),
            PayoutEntity(assetId = 1, ticker = "BOND", payoutType = "Купон", amountPerUnit = 10.0, quantity = 1.0, totalAmount = 10.0, expectedDate = 5L, status = "EXPECTED"),
            PayoutEntity(assetId = 1, ticker = "BOND", payoutType = "Погашение", amountPerUnit = 0.0, quantity = 0.0, totalAmount = 0.0, reportedNetAmount = 100.0, actualDate = 4L, status = "RECEIVED")
        )

        val flows = buildAssetReturnCashFlows(operations, payouts, currentValue = 50.0, valuationDate = 6L)

        assertEquals(
            listOf(1L to -101.0, 2L to 59.0, 3L to -2.0, 3L to 10.0, 4L to 100.0, 6L to 50.0),
            flows
        )
    }

    @Test
    fun xirrMatchesExcelStyleAnnualizedReturnOver365Days() {
        val start = java.time.LocalDate.of(2025, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = java.time.LocalDate.of(2026, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

        assertEquals(10.0, annualizedReturnPercent(listOf(start to -100.0, end to 110.0))!!, 0.0001)
    }

    @Test
    fun xirrAnnualizesPeriodsShorterThanOneYear() {
        val start = java.time.LocalDate.of(2025, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = java.time.LocalDate.of(2025, 7, 2).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

        val result = annualizedReturnPercent(listOf(start to -100.0, end to 110.0))
        assertTrue(result != null && result > 20.0)
    }

    @Test
    fun xirrReturnsNullWhenThereIsNoPositiveDuration() {
        assertTrue(annualizedReturnPercent(listOf(1L to -100.0, 1L to 110.0)) == null)
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
