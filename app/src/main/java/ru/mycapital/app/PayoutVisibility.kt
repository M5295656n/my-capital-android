package ru.mycapital.app

import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.BrokerAccountAssetEntity
import ru.mycapital.app.data.PayoutEntity

/**
 * Keep historical payouts visible, but do not present future dividend/coupon
 * estimates for instruments that are no longer held.
 *
 * Account-scoped payouts are checked against that broker account's position.
 * Portfolio-level payouts are checked against aggregate portfolio holdings.
 */
internal fun shouldShowPayoutForCurrentHoldings(
    payout: PayoutEntity,
    assets: List<AssetEntity>,
    accountAssets: List<BrokerAccountAssetEntity>,
    selectedAccountId: String
): Boolean {
    val status = payout.status.uppercase()
    val isUpcoming = status in setOf("EXPECTED", "ANNOUNCED", "RECOMMENDED", "FORECAST")
    val isDividendOrCoupon = payout.payoutType.contains("дивиденд", ignoreCase = true) ||
        payout.payoutType.contains("купон", ignoreCase = true)

    if (!isUpcoming || !isDividendOrCoupon) return true

    val ticker = payout.ticker.trim().uppercase()
    if (ticker.isEmpty()) return false

    fun heldOnAccount(accountId: String): Boolean =
        accountAssets.any {
            it.accountId == accountId &&
                it.ticker.trim().equals(ticker, ignoreCase = true) &&
                it.quantity > 0.0
        }

    fun heldInPortfolio(): Boolean =
        assets.any {
            it.ticker.trim().equals(ticker, ignoreCase = true) && it.quantity > 0.0
        }

    val payoutAccountId = historyAccountId(payout.sourceKey)
    return when (selectedAccountId) {
        ALL_HISTORY_ACCOUNTS -> {
            if (payoutAccountId != null) heldOnAccount(payoutAccountId) else heldInPortfolio()
        }
        UNASSIGNED_HISTORY_ACCOUNT -> payoutAccountId == null && heldInPortfolio()
        else -> {
            if (payoutAccountId != null && payoutAccountId != selectedAccountId) {
                false
            } else {
                heldOnAccount(selectedAccountId)
            }
        }
    }
}
