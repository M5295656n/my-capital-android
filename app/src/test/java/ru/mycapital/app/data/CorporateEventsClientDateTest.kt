package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CorporateEventsClientDateTest {
    @Test
    fun keepsPayoutScheduledForToday() {
        val today = LocalDate.of(2026, 11, 2)
        assertTrue(isEventDateUpcoming(today, today))
    }

    @Test
    fun keepsFuturePayout() {
        val today = LocalDate.of(2026, 11, 2)
        assertTrue(isEventDateUpcoming(today.plusDays(1), today))
    }

    @Test
    fun excludesPastPayout() {
        val today = LocalDate.of(2026, 11, 2)
        assertFalse(isEventDateUpcoming(today.minusDays(1), today))
    }

    @Test
    fun stockDividendsRequireActualPaymentDateColumn() {
        val columns = mapOf("registryclosedate" to 0, "recorddate" to 1, "value" to 2)
        assertNull(paymentDateColumn(isBond = false, columns))
    }

    @Test
    fun stockDividendsUsePaymentDateWhenPresent() {
        val columns = mapOf("registryclosedate" to 0, "paymentdate" to 1, "value" to 2)
        assertEquals(1, paymentDateColumn(isBond = false, columns))
    }

    @Test
    fun bondCouponsPreferCouponDate() {
        val columns = mapOf("paymentdate" to 0, "coupondate" to 1)
        assertEquals(1, paymentDateColumn(isBond = true, columns))
    }

    @Test
    fun bondCouponsCanUsePaymentDateFallback() {
        assertEquals(0, paymentDateColumn(isBond = true, mapOf("paymentdate" to 0)))
    }

    @Test
    fun rejectsMalformedDates() {
        assertNull(parseMoexEventDate("2026-13-40"))
        assertNull(parseMoexEventDate("2026-11"))
        assertEquals(LocalDate.of(2026, 11, 3), parseMoexEventDate("2026-11-03"))
    }
}
