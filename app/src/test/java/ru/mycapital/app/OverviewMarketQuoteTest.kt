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
    fun missingCurrencyAndInvalidNominalAreHandledSafely() {
        assertNull(parseCbrCurrencyRateJson("""{"Valute":{}}""", "CNY", "CNY_RUB_CBR", "CNY/RUB"))
        val json = """{"Valute":{"CNY":{"Nominal":0,"Value":12.5,"Previous":12.0}}}"""
        assertNull(parseCbrCurrencyRateJson(json, "CNY", "CNY_RUB_CBR", "CNY/RUB"))
    }

    @Test
    fun missingPreviousRateDoesNotInventPercentage() {
        val json = """{"Valute":{"EUR":{"Value":91.5}}}"""

        val quote = parseEuroRateJson(json)

        assertEquals(91.5, quote?.price ?: Double.NaN, 0.000001)
        assertNull(quote?.changePercent)
    }

    @Test
    fun goldQuoteFallsBackToLatestDailyCandleCloseWhenMarketdataIsEmpty() {
        val json = """{"candles":{"columns":["begin","open","high","low","close","volume"],"data":[["2026-11-17 00:00:00",9900.0,10000.0,9800.0,9950.0,10],["2026-11-18 00:00:00",10000.0,10100.0,9950.0,10064.5,12]]}}"""

        val quote = parseMoexGoldCandleJson(json, "GLDRUB_TOM", "Золото ₽/г")

        assertEquals(10064.5, quote?.price ?: Double.NaN, 0.000001)
        assertNull(quote?.changePercent)
    }

    @Test
    fun goldDailyCandleParserRejectsMissingOrInvalidClose() {
        assertNull(parseMoexGoldCandleJson("""{"candles":{"columns":["begin","close"],"data":[["2026-11-18",null]]}}""", "GLDRUB_TOM", "Золото ₽/г"))
        assertNull(parseMoexGoldCandleJson("""{"candles":{"columns":["begin"],"data":[["2026-11-18"]]}}""", "GLDRUB_TOM", "Золото ₽/г"))
    }

    @Test
    fun goldQuoteRemainsInDashboardWhenMoexHasNoUsablePrice() {
        val quote = goldQuoteOrPlaceholder(null, null)

        assertEquals("GLDRUB_TOM", quote.ticker)
        assertEquals("Золото ₽/г", quote.label)
        assertNull(quote.price)
        assertNull(quote.changePercent)
    }

    @Test
    fun goldQuoteDoesNotTreatPreviousPriceAsCurrentPrice() {
        val json = """{"marketdata":{"columns":["SECID","BOARDID","LAST","CHANGE","PREVPRICE","MARKETPRICE"],"data":[["GLDRUB_TOM","CETS",null,null,8123.5,null]]}}"""

        val quote = parseMoexGoldQuoteJson(json, "GLDRUB_TOM", "Золото ₽/г")

        assertNull(quote)
    }

    @Test
    fun goldQuoteUsesWeightedAverageWhenLastIsUnavailable() {
        val json = """{"marketdata":{"columns":["SECID","BOARDID","LAST","WAPRICE","CHANGE","PREVPRICE","MARKETPRICE"],"data":[["GLDRUB_TOM","CETS",null,8245.75,null,8200.0,8190.0]]}}"""

        val quote = parseMoexGoldQuoteJson(json, "GLDRUB_TOM", "Золото ₽/г")

        assertEquals(8245.75, quote?.price ?: Double.NaN, 0.000001)
        assertEquals(0.5579268293, quote?.changePercent ?: Double.NaN, 0.000001)
    }

    @Test
    fun goldQuotePrefersCetsBoardAndLastPrice() {
        val json = """{"marketdata":{"columns":["SECID","BOARDID","LAST","CHANGE","PREVPRICE","MARKETPRICE"],"data":[["GLDRUB_TOM","CNGD",8100.0,10.0,8090.0,8080.0],["GLDRUB_TOM","CETS",8200.0,20.0,8180.0,8170.0]]}}"""

        val quote = parseMoexGoldQuoteJson(json, "GLDRUB_TOM", "Золото ₽/г")

        assertEquals(8200.0, quote?.price ?: Double.NaN, 0.000001)
        assertEquals(0.2444987775, quote?.changePercent ?: Double.NaN, 0.000001)
    }

    @Test
    fun heldMoverFallbackUsesPreviousCloseWhenChangeIsMissing() {
        val json = """{"securities":{"columns":["SECID","SHORTNAME"],"data":[["SBER","Сбербанк"]]},"marketdata":{"columns":["SECID","BOARDID","LAST","CHANGE","PREVPRICE","MARKETPRICE"],"data":[["SBER","TQBR",310.0,null,300.0,299.0]]}}"""

        val mover = parseMoexMoverQuoteJson(json, "SBER", listOf("TQBR"))

        assertEquals("Сбербанк", mover?.name)
        assertEquals(310.0, mover?.price ?: Double.NaN, 0.000001)
        assertEquals(10.0, mover?.change ?: Double.NaN, 0.000001)
        assertEquals(3.3333333333, mover?.changePercent ?: Double.NaN, 0.000001)
    }

    @Test
    fun invalidRatesDoNotProducePercentage() {
        assertNull(calculateQuoteChangePercent(91.0, 0.0))
        assertNull(calculateQuoteChangePercent(Double.NaN, 90.0))
    }
}
