package ru.mycapital.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.BrokerAccountAssetEntity
import ru.mycapital.app.data.PayoutEntity

class PayoutVisibilityTest {
    private fun payout(
        ticker: String = "ROSN",
        type: String = "Дивиденд",
        status: String = "FORECAST",
        sourceKey: String? = null
    ) = PayoutEntity(
        assetId = null,
        ticker = ticker,
        payoutType = type,
        amountPerUnit = 10.0,
        quantity = 2.0,
        totalAmount = 20.0,
        expectedDate = 1_800_000_000_000L,
        status = status,
        sourceKey = sourceKey
    )

    private fun asset(ticker: String, quantity: Double) = AssetEntity(
        ticker = ticker,
        name = ticker,
        assetType = "Акция",
        quantity = quantity,
        averagePrice = 100.0,
        currentPrice = 100.0
    )

    private fun accountAsset(accountId: String, ticker: String, quantity: Double) =
        BrokerAccountAssetEntity(accountId, ticker, quantity)

    @Test
    fun hidesFutureDividendForFullySoldAssetButKeepsReceivedHistory() {
        val sold = payout()
        val received = sold.copy(status = "RECEIVED", actualDate = 1_700_000_000_000L)

        assertFalse(shouldShowPayoutForCurrentHoldings(
            sold, listOf(asset("ROSN", 0.0)), emptyList(), ALL_HISTORY_ACCOUNTS
        ))
        assertTrue(shouldShowPayoutForCurrentHoldings(
            received, listOf(asset("ROSN", 0.0)), emptyList(), ALL_HISTORY_ACCOUNTS
        ))
    }

    @Test
    fun keepsForecastWhenSomeUnitsAreStillHeld() {
        assertTrue(shouldShowPayoutForCurrentHoldings(
            payout(), listOf(asset("ROSN", 0.5)), emptyList(), ALL_HISTORY_ACCOUNTS
        ))
    }

    @Test
    fun accountScopedForecastRequiresHoldingOnTheSameAccount() {
        val scoped = payout(sourceKey = "SBER:ACCOUNT-A:PAYOUT:dividend-1")
        val positions = listOf(accountAsset("ACCOUNT-B", "ROSN", 2.0))

        assertFalse(shouldShowPayoutForCurrentHoldings(
            scoped, listOf(asset("ROSN", 2.0)), positions, ALL_HISTORY_ACCOUNTS
        ))
        assertFalse(shouldShowPayoutForCurrentHoldings(
            scoped, listOf(asset("ROSN", 2.0)), positions, "ACCOUNT-A"
        ))
        assertTrue(shouldShowPayoutForCurrentHoldings(
            scoped, listOf(asset("ROSN", 2.0)),
            positions + accountAsset("ACCOUNT-A", "ROSN", 1.0), "ACCOUNT-A"
        ))
    }

    @Test
    fun unscopedForecastUsesSelectedAccountOrAggregatePortfolioPosition() {
        val unscoped = payout(sourceKey = null)
        val positions = listOf(accountAsset("ACCOUNT-A", "ROSN", 1.0))

        assertTrue(shouldShowPayoutForCurrentHoldings(
            unscoped, listOf(asset("ROSN", 1.0)), positions, ALL_HISTORY_ACCOUNTS
        ))
        assertTrue(shouldShowPayoutForCurrentHoldings(
            unscoped, listOf(asset("ROSN", 1.0)), positions, "ACCOUNT-A"
        ))
        assertFalse(shouldShowPayoutForCurrentHoldings(
            unscoped, listOf(asset("ROSN", 0.0)), positions, "ACCOUNT-B"
        ))
        assertFalse(shouldShowPayoutForCurrentHoldings(
            unscoped, listOf(asset("ROSN", 0.0)), positions, UNASSIGNED_HISTORY_ACCOUNT
        ))
    }

    @Test
    fun hidesFutureBondCouponForFullySoldBond() {
        assertFalse(shouldShowPayoutForCurrentHoldings(
            payout(ticker = "SU26238RMFS4", type = "Купон"),
            listOf(asset("SU26238RMFS4", 0.0)),
            listOf(accountAsset("ACCOUNT-A", "SU26238RMFS4", 0.0)),
            ALL_HISTORY_ACCOUNTS
        ))
    }

    @Test
    fun leavesNonDividendPayoutsAndUnknownStatusesUntouched() {
        val soldAsset = listOf(asset("ROSN", 0.0))
        assertTrue(shouldShowPayoutForCurrentHoldings(
            payout(type = "Погашение"), soldAsset, emptyList(), ALL_HISTORY_ACCOUNTS
        ))
        assertTrue(shouldShowPayoutForCurrentHoldings(
            payout(status = "CUSTOM"), soldAsset, emptyList(), ALL_HISTORY_ACCOUNTS
        ))
    }
}
