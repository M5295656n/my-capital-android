package ru.mycapital.app

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationHistoryRangeTest {
    private val today = LocalDate.of(2026, 11, 3)
    private val zone = ZoneId.of("UTC")

    private fun timestamp(date: LocalDate) =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun thirtyDayRangeIncludesTodayAndFirstDay() {
        assertTrue(isOperationWithinHistoryRange(timestamp(today), OperationHistoryRange.LAST_30_DAYS, today, zone))
        assertTrue(isOperationWithinHistoryRange(timestamp(today.minusDays(29)), OperationHistoryRange.LAST_30_DAYS, today, zone))
        assertFalse(isOperationWithinHistoryRange(timestamp(today.minusDays(30)), OperationHistoryRange.LAST_30_DAYS, today, zone))
    }

    @Test
    fun ninetyDayRangeUsesInclusiveCalendarDays() {
        assertTrue(isOperationWithinHistoryRange(timestamp(today.minusDays(89)), OperationHistoryRange.LAST_90_DAYS, today, zone))
        assertFalse(isOperationWithinHistoryRange(timestamp(today.minusDays(90)), OperationHistoryRange.LAST_90_DAYS, today, zone))
    }

    @Test
    fun yearRangeIncludesLast365Days() {
        assertTrue(isOperationWithinHistoryRange(timestamp(today.minusDays(364)), OperationHistoryRange.LAST_YEAR, today, zone))
        assertFalse(isOperationWithinHistoryRange(timestamp(today.minusDays(365)), OperationHistoryRange.LAST_YEAR, today, zone))
    }

    @Test
    fun recentRangesExcludeFutureOperations() {
        assertFalse(isOperationWithinHistoryRange(timestamp(today.plusDays(1)), OperationHistoryRange.LAST_30_DAYS, today, zone))
    }

    @Test
    fun allRangeDoesNotRestrictOperationDate() {
        assertTrue(isOperationWithinHistoryRange(timestamp(today.minusYears(10)), OperationHistoryRange.ALL, today, zone))
        assertTrue(isOperationWithinHistoryRange(timestamp(today.plusYears(1)), OperationHistoryRange.ALL, today, zone))
    }
}
