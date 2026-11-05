package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverviewMarketQuoteTest {
    @Test
    fun euroRateShowsChangeAgainstPreviousPublishedRate() {
        val json = """{"Valute":{"EUR":{"Value":91.5,"Previous":90.0}}}"""

        val quote = parseEuroRateJson(json)

        assertEquals(91.5, quote?.price ?: Double.NaN, 0.000001)
        assertEquals(1.6666666667, quote?.changePercent ?: Double.NaN, 0.000001)
    }

    @Test
    fun euroRateShowsNegativeChange() {
        val json = """{"Valute":{"EUR":{"Value":89.1,"Previous":90.0}}}"""

        val quote = parseEuroRateJson(json)

        assertEquals(-1.0, quote?.changePercent ?: Double.NaN, 0.000001)
    }

    @Test
    fun missingPreviousRateDoesNotInventPercentage() {
        val json = """{"Valute":{"EUR":{"Value":91.5}}}"""

        val quote = parseEuroRateJson(json)

        assertEquals(91.5, quote?.price ?: Double.NaN, 0.000001)
        assertNull(quote?.changePercent)
    }

    @Test
    fun invalidRatesDoNotProducePercentage() {
        assertNull(calculateQuoteChangePercent(91.0, 0.0))
        assertNull(calculateQuoteChangePercent(Double.NaN, 90.0))
    }
}
