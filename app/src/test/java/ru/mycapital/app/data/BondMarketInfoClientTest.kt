package ru.mycapital.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate

class BondMarketInfoClientTest {
    @Test
    fun parsesMaturityAndCalculatesAnnualCouponYieldAgainstCleanPrice() {
        val json = JSONObject(
            """{
              "securities": {
                "columns": ["SECID", "MATDATE", "COUPONVALUE", "COUPONFREQUENCY", "COUPONPERIOD"],
                "data": [["SU26212RMFS9", "2028-01-19", 35.15, 2, 182]]
              },
              "marketdata": {
                "columns": ["SECID", "BOARDID", "ACCRUEDINT"],
                "data": [["SU26212RMFS9", "TQOB", 10.0]]
              }
            }"""
        )

        val result = parseBondMarketInfo(json, currentDirtyPrice = 1035.0)

        assertEquals(LocalDate.of(2028, 1, 19), result.maturityDate)
        assertNotNull(result.couponYieldPercent)
        assertEquals(35.15 * 2.0 / 1025.0 * 100.0, result.couponYieldPercent!!, 0.000001)
    }

    @Test
    fun calculatesCouponYieldUsingCouponPeriodWhenFrequencyIsMissing() {
        assertEquals(
            35.0 * (365.0 / 182.0) / 1000.0 * 100.0,
            calculateCurrentCouponYieldPercent(35.0, couponFrequencyPerYear(null, 182.0), 1000.0)!!,
            0.000001
        )
    }

    @Test
    fun returnsNullWhenCouponOrPriceIsUnavailable() {
        assertNull(calculateCurrentCouponYieldPercent(null, 2.0, 1000.0))
        assertNull(calculateCurrentCouponYieldPercent(35.0, 2.0, null))
        assertNull(parseBondMaturityDate("not-a-date"))
    }
}
