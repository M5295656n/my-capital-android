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
    fun rejectsBondHistoryUntilHistoricalNominalAndAccruedInterestAreAvailable() {
        val error = runCatching {
            MoexSecurityHistoryClient().getDailyCloses(
                "SU26238RMFS4",
                "Облигация",
                LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-02-01")
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
