package ru.mycapital.app.data

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialDividendAnnouncementsTest {
    @Test
    fun x5OfficialRecommendationReplacesStaleForecastAndKeepsNextForecast() {
        val today = LocalDate.of(2026, 11, 6)
        fun epoch(date: LocalDate) = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val events = listOf(
            DividendCalendarEvent("X5", 98.0, "FORECAST", epoch(LocalDate.of(2027, 1, 6)), null, "LS:X5:98"),
            DividendCalendarEvent("X5", 74.0, "FORECAST", epoch(LocalDate.of(2027, 7, 7)), null, "LS:X5:74")
        )

        val merged = mergeOfficialDividendAnnouncements(events, today)
        assertEquals(2, merged.size)
        assertEquals(listOf(204.0, 74.0), merged.sortedBy { it.amountPerUnit }.map { it.amountPerUnit }.reversed())
        assertEquals(278.0, merged.sumOf { it.amountPerUnit }, 0.000001)
        assertEquals(11.87, merged.sumOf { it.amountPerUnit } / 2342.0 * 100.0, 0.01)
        assertNotNull(merged.singleOrNull { it.status == "RECOMMENDED" })
        assertEquals("Рекомендовано советом директоров", ru.mycapital.app.dividendStatusLabel("RECOMMENDED"))
        assertNull(merged.singleOrNull { it.amountPerUnit == 98.0 })
    }

    @Test
    fun staleJanuaryForecastRemainsSuppressedAfterOfficialRecordDate() {
        val events = listOf(
            DividendCalendarEvent(
                "X5", 98.0, "FORECAST",
                LocalDate.of(2027, 1, 6).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                null, "LS:X5:98"
            )
        )
        val merged = mergeOfficialDividendAnnouncements(events, LocalDate.of(2026, 12, 12))
        assertTrueEmpty(merged)
    }

    private fun assertTrueEmpty(events: List<DividendCalendarEvent>) {
        org.junit.Assert.assertTrue(events.isEmpty())
    }
}
