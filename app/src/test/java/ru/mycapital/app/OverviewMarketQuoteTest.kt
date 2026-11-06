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
    @Test
    fun dollarRateUsesCorrectLabelAndRublesPerDollar() {
        val json = """{"Valute":{"USD":{"Nominal":1,"Value":82.0,"Previous":80.0}}}"""

        val quote = parseCbrCurrencyRateJson(json, "USD", "USD/RUB", "USD_RUB_CBR")

        assertEquals("USD/RUB", quote?.label)
        assertEquals("USD_RUB_CBR", quote?.ticker)
        assertEquals(82.0, quote?.price ?: Double.NaN, 0.000001)
        assertEquals(2.5, quote?.changePercent ?: Double.NaN, 0.000001)
    }

    @Test
    fun yenRateNormalizesCbrNominalOfOneHundredYenToOneYen() {
        val json = """{"Valute":{"JPY":{"Nominal":100,"Value":54.25,"Previous":53.5}}}"""

        val quote = parseCbrCurrencyRateJson(json, "JPY", "JPY/RUB", "JPY_RUB_CBR")

        assertEquals("JPY/RUB", quote?.label)
        assertEquals("JPY_RUB_CBR", quote?.ticker)
        assertEquals(0.5425, quote?.price ?: Double.NaN, 0.000001)
        assertEquals((54.25 - 53.5) / 53.5 * 100.0, quote?.changePercent ?: Double.NaN, 0.000001)
    }

    @Test
    fun goldQuoteLabelExplicitlyStatesRublesPerGram() {
        val quote = MarketQuoteRow("GLDRUB_TOM", "Золото ₽/г", 12_345.67, null)

        assertEquals("GLDRUB_TOM", quote.ticker)
        assertEquals("Золото ₽/г", quote.label)
        assertEquals(12_345.67, quote.price ?: Double.NaN, 0.000001)
    }

    @Test
    fun missingCurrencyAndInvalidNominalAreHandledSafely() {
        assertNull(parseCbrCurrencyRateJson("""{"Valute":{}}""", "JPY", "JPY/RUB", "JPY_RUB_CBR"))
        val json = """{"Valute":{"JPY":{"Nominal":0,"Value":54.25,"Previous":53.5}}}"""
        assertNull(parseCbrCurrencyRateJson(json, "JPY", "JPY/RUB", "JPY_RUB_CBR"))
    }

}
