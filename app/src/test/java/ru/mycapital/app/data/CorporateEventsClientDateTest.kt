package ru.mycapital.app.data

import org.junit.Assert.assertFalse
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
}
