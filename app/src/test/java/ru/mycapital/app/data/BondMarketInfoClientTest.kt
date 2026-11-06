package ru.mycapital.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate

class BondMarketInfoClientTest {
    @Test
    fun parsesMaturityAndUsesExchangeCouponRateWhenAvailable() {
        val json = JSONObject(
            """{
              "securities": {
                "columns": ["SECID", "MATDATE", "COUPONPERCENT", "COUPONVALUE", "COUPONFREQUENCY", "COUPONPERIOD", "FACEVALUE"],
                "data": [["SU26212RMFS9", "2028-01-19", 7.05, 35.15, 2, 182, 1000]]
              }
            }"""
        )

        val result = parseBondMarketInfo(json)

        assertEquals(LocalDate.of(2028, 1, 19), result.maturityDate)
        assertNotNull(result.couponYieldPercent)
        assertEquals(7.05, result.couponYieldPercent!!, 0.000001)
    }

    @Test
    fun calculatesCouponYieldFromAnnualizedCouponAndFaceValueWhenExchangeRateIsMissing() {
        assertEquals(
            35.15 * (365.0 / 182.0) / 1000.0 * 100.0,
            calculateCouponYieldPercent(35.15, 182.0, 2.0, 1000.0)!!,
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
