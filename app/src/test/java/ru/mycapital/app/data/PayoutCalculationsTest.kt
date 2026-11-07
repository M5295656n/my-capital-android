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
