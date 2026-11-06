package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate

class BondMarketInfoClientTest {
    @Test
    fun parsesMaturityAndUsesExchangeCouponRateWhenAvailable() {
        val result = createBondMarketInfo(
            maturityDate = "2028-01-19",
            couponValue = 35.15,
            couponPeriodDays = 182.0,
            couponsPerYear = 2.0,
            faceValue = 1000.0,
            couponPercentFromExchange = 7.05
        )

        assertEquals(LocalDate.of(2028, 1, 19), result.maturityDate)
        assertNotNull(result.couponYieldPercent)
        assertEquals(7.05, result.couponYieldPercent!!, 0.000001)
    }

    @Test
    fun calculatesCouponYieldFromAnnualizedCouponAndFaceValueWhenExchangeRateIsMissing() {
        val result = createBondMarketInfo(
            maturityDate = "2031-09-17",
            couponValue = 42.38,
            couponPeriodDays = 182.0,
            couponsPerYear = 2.0,
            faceValue = 1000.0,
            couponPercentFromExchange = null
        )
        assertEquals(
            42.38 * (365.0 / 182.0) / 1000.0 * 100.0,
            result.couponYieldPercent!!,
            0.000001
        )
    }

    @Test
    fun returnsNullWhenCouponOrFaceValueIsUnavailable() {
        assertNull(calculateCouponYieldPercent(null, 182.0, 2.0, 1000.0))
        assertNull(calculateCouponYieldPercent(35.15, 182.0, 2.0, null))
        assertNull(parseBondMaturityDate("not-a-date"))
    }
}
