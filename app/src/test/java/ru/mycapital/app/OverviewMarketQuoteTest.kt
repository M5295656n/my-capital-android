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
    fun dollarAndYuanRatesAreParsedFromOneCbrSnapshot() {
        val json = """{"Valute":{"USD":{"Nominal":1,"Value":92.5,"Previous":91.0},"CNY":{"Nominal":10,"Value":125.0,"Previous":120.0}}}"""

        val dollar = parseCbrCurrencyRateJson(json, "USD", "USD_RUB_CBR", "USD/RUB")
        val yuan = parseCbrCurrencyRateJson(json, "CNY", "CNY_RUB_CBR", "CNY/RUB")

        assertEquals("USD/RUB", dollar?.label)
        assertEquals(92.5, dollar?.price ?: Double.NaN, 0.000001)
        assertEquals(1.6483516484, dollar?.changePercent ?: Double.NaN, 0.000001)
        assertEquals("CNY/RUB", yuan?.label)
        assertEquals(12.5, yuan?.price ?: Double.NaN, 0.000001)
        assertEquals(4.1666666667, yuan?.changePercent ?: Double.NaN, 0.000001)
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
