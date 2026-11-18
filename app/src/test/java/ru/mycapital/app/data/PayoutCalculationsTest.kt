package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PayoutCalculationsTest {
    @Test
    fun calculatesNetPayoutAfterTax() {
        assertEquals(870.0, calculatePayoutNetAmount(1000.0, 130.0), 0.000001)
    }

    @Test
    fun allowsZeroTax() {
        assertEquals(1000.0, calculatePayoutNetAmount(1000.0, 0.0), 0.000001)
    }

    @Test
    fun rejectsTaxAboveGrossAmount() {
        assertThrows(IllegalArgumentException::class.java) {
            calculatePayoutNetAmount(1000.0, 1000.01)
        }
    }

    @Test
    fun rejectsNegativeTax() {
        assertThrows(IllegalArgumentException::class.java) {
            calculatePayoutNetAmount(1000.0, -1.0)
        }
    }

    @Test
    fun rejectsInvalidGrossAmount() {
        assertThrows(IllegalArgumentException::class.java) {
            calculatePayoutNetAmount(0.0, 0.0)
        }
    }

    @Test
    fun aggregatesReceivedIncomeAndReturnedBondPrincipalSeparately() {
        val totals = calculateReceivedPayoutTotals(
            listOf(
                PayoutEntity(assetId = 1L, ticker = "SBER", payoutType = "Дивиденд", amountPerUnit = 10.0, quantity = 1.0, totalAmount = 10.0, taxAmount = 1.0, status = "RECEIVED"),
                PayoutEntity(assetId = 2L, ticker = "RU000A", payoutType = "Купон", amountPerUnit = 20.0, quantity = 1.0, totalAmount = 20.0, reportedNetAmount = 17.0, status = "RECEIVED"),
                PayoutEntity(assetId = 3L, ticker = "RU000A", payoutType = "Амортизация", amountPerUnit = 200.0, quantity = 1.0, totalAmount = 200.0, status = "RECEIVED"),
                PayoutEntity(assetId = 4L, ticker = "RU000A", payoutType = "Погашение", amountPerUnit = 1000.0, quantity = 1.0, totalAmount = 1000.0, actualDate = 100L, status = "PAID"),
                PayoutEntity(assetId = 5L, ticker = "GAZP", payoutType = "Дивиденд", amountPerUnit = 5.0, quantity = 1.0, totalAmount = 5.0, status = "EXPECTED")
            )
        )

        assertEquals(1246.0, totals.totalNetAmount, 0.000001)
        assertEquals(36.0, totals.investmentIncome, 0.000001)
        assertEquals(9.0, totals.dividends, 0.000001)
        assertEquals(17.0, totals.coupons, 0.000001)
        assertEquals(1200.0, totals.returnedPrincipal, 0.000001)
    }

    @Test
    fun ignoresNonFiniteOrNegativeReceivedAmounts() {
        val totals = calculateReceivedPayoutTotals(
            listOf(
                PayoutEntity(assetId = 1L, ticker = "SBER", payoutType = "Дивиденд", amountPerUnit = 1.0, quantity = 1.0, totalAmount = Double.NaN, status = "RECEIVED"),
                PayoutEntity(assetId = 2L, ticker = "SBER", payoutType = "Купон", amountPerUnit = 1.0, quantity = 1.0, totalAmount = -5.0, status = "RECEIVED")
            )
        )
        assertEquals(0.0, totals.totalNetAmount, 0.000001)
        assertEquals(0.0, totals.investmentIncome, 0.000001)
    }

    @Test
    fun excludesBondMaturityFromInvestmentIncome() {
        assertEquals(false, isIncomePayoutType("Погашение"))
    }

    @Test
    fun excludesBondAmortizationFromInvestmentIncome() {
        assertEquals(false, isIncomePayoutType("Амортизация"))
    }

    @Test
    fun includesCouponsAndDividendsAsInvestmentIncome() {
        assertEquals(true, isIncomePayoutType("Купон"))
        assertEquals(true, isIncomePayoutType("Дивиденд"))
    }
}
