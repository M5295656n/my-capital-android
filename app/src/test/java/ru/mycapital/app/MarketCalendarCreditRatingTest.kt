package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketCalendarCreditRatingTest {
    @Test
    fun extractsIssueAndIssuerRatingsSeparatelyFromCombinedSummary() {
        val summary = "Размещение облигаций. Рейтинг выпуска: AAA(RU) Рейтинг эмитента: AA+(RU), прогноз стабильный"

        assertEquals("AAA(RU)", extractCreditRating(summary, "выпуска"))
        assertEquals("AA+(RU), прогноз стабильный", extractCreditRating(summary, "эмитента"))
    }

    @Test
    fun returnsNullWhenRatingIsNotDisclosed() {
        assertNull(extractCreditRating("Размещение облигаций. Купон: 12%", "выпуска"))
        assertNull(extractCreditRating("Размещение облигаций. Купон: 12%", "эмитента"))
    }
}
