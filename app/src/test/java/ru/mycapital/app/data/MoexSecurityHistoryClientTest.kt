package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MoexSecurityHistoryClientTest {
    @Test
    fun parsesDailyCloseAndSkipsNullOrInvalidRows() {
        val points = parseDailyCandleRows(
            listOf(
                "2026-09-15 00:00:00" to 128.3,
                "2026-09-16 00:00:00" to null,
                "2026-09-17 00:00:00" to 0.0,
                "not-a-date" to 130.0,
                "2026-09-18 00:00:00" to 131.5
            )
        )
        assertEquals(2, points.size)
        assertEquals(LocalDate.parse("2026-09-15"), points[0].date)
        assertEquals(128.3, points[0].close, 0.000001)
        assertEquals(LocalDate.parse("2026-09-18"), points[1].date)
    }

    @Test
    fun duplicateDatesKeepOneChronologicalPoint() {
        val points = parseDailyCandleRows(
            listOf(
                "2026-09-16 00:00:00" to 129.0,
                "2026-09-15 00:00:00" to 128.0,
                "2026-09-16 00:00:00" to 130.0
            )
        )
        assertEquals(2, points.size)
        assertTrue(points.zipWithNext().all { it.first.date < it.second.date })
    }

    @Test
    fun accruedInterestUsesActualCouponPeriodDays() {
        val coupons = listOf(
            MoexBondCoupon(LocalDate.parse("2026-03-01"), 50.0),
            MoexBondCoupon(LocalDate.parse("2026-06-01"), 50.0)
        )
        val accrued = calculateBondAccruedInterest(LocalDate.parse("2026-04-01"), coupons)
        assertEquals(50.0 * 31.0 / 92.0, accrued!!, 0.000001)
    }

    @Test
    fun firstCouponCanUseExplicitStartDate() {
        val coupons = listOf(
            MoexBondCoupon(
                couponDate = LocalDate.parse("2026-06-01"),
                value = 50.0,
                startDate = LocalDate.parse("2026-03-01")
            )
        )
        assertEquals(
            50.0 * 31.0 / 92.0,
            calculateBondAccruedInterest(LocalDate.parse("2026-04-01"), coupons)!!,
            0.000001
        )
    }

    @Test
    fun dirtyPriceConvertsPercentOfNominalAndAddsAccruedInterest() {
        assertEquals(967.5, calculateBondDirtyPrice(95.5, 1_000.0, 12.5), 0.000001)
    }

    @Test
    fun reconstructsPastNominalFromTodaysFaceValueAndLaterAmortizations() {
        val amortizations = listOf(
            MoexBondAmortization(LocalDate.parse("2026-05-01"), 100.0),
            MoexBondAmortization(LocalDate.parse("2026-10-01"), 200.0),
            MoexBondAmortization(LocalDate.parse("2027-03-01"), 100.0)
        )
        assertEquals(
            1_300.0,
            historicalBondFaceValue(1_000.0, amortizations, LocalDate.parse("2026-04-01"), LocalDate.parse("2026-11-05")),
            0.000001
        )
        assertEquals(
            1_200.0,
            historicalBondFaceValue(1_000.0, amortizations, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-11-05")),
            0.000001
        )
    }

}
