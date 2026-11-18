package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.PayoutEntity

class AssetReturnCalculationsTest {
    @Test
    fun cashFlowsIncludeTradeCostsReceivedPayoutAndTerminalValue() {
        val flows = buildAssetReturnCashFlows(
            operations = listOf(
                OperationEntity(
                    assetId = 1L,
                    operationType = "Покупка",
                    ticker = "SBER",
                    quantity = 2.0,
                    price = 100.0,
                    commission = 2.0,
                    operationDate = 10L
                ),
                OperationEntity(
                    assetId = 1L,
                    operationType = "Продажа",
                    ticker = "SBER",
                    quantity = 1.0,
                    price = 120.0,
                    commission = 1.0,
                    operationDate = 30L
                )
            ),
            payouts = listOf(
                PayoutEntity(
                    assetId = 1L,
                    ticker = "SBER",
                    payoutType = "Дивиденд",
                    amountPerUnit = 5.0,
                    quantity = 2.0,
                    totalAmount = 10.0,
                    taxAmount = 1.0,
                    expectedDate = 40L
                ),
                PayoutEntity(
                    assetId = 1L,
                    ticker = "SBER",
                    payoutType = "Дивиденд",
                    amountPerUnit = 5.0,
                    quantity = 2.0,
                    totalAmount = 10.0,
                    taxAmount = 1.0,
                    actualDate = 50L,
                    status = "RECEIVED"
                )
            ),
            currentValue = 80.0,
            valuationDate = 60L
        )

        assertEquals(
            listOf(10L to -202.0, 30L to 119.0, 50L to 9.0, 60L to 80.0),
            flows
        )
    }

    @Test
    fun reportedNetPayoutAmountTakesPrecedenceOverGrossLessTax() {
        val payout = PayoutEntity(
            assetId = null,
            ticker = "SBER",
            payoutType = "Дивиденд",
            amountPerUnit = 1.0,
            quantity = 1.0,
            totalAmount = 100.0,
            taxAmount = 13.0,
            actualDate = 100L,
            reportedNetAmount = 84.0
        )

        assertEquals(
            listOf(100L to 84.0),
            buildAssetReturnCashFlows(emptyList(), listOf(payout), 0.0, 200L)
        )
    }

    @Test
    fun annualizedReturnMatchesTenPercentOver365Days() {
        val year = 365L * 24L * 60L * 60L * 1000L

        assertEquals(10.0, annualizedReturnPercent(listOf(0L to -100.0, year to 110.0))!!, 0.000001)
    }

    @Test
    fun annualizedReturnIsNullWithoutBothCashFlowSigns() {
        assertNull(annualizedReturnPercent(listOf(0L to -100.0, 365L * 24L * 60L * 60L * 1000L to -10.0)))
    }
}
