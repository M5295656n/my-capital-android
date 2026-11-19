package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MoexIssClientTest {
    @Test fun convertsBondPercentQuoteToFullRubPriceIncludingAccruedInterest() {
        assertEquals(786.50, normalizeMoexPrice(78.07, "Облигация", 1_000.0, 5.80), 0.0001)
        assertEquals(813.98, normalizeMoexPrice(81.398, "Облигация", 1_000.0, 0.0), 0.0001)
    }

    @Test fun prefersOfficialCurrentBondPriceAndNeverUsesPreviousDayMarketPrice() {
        assertEquals(81.40, selectBondQuotePrice(81.10, 81.25, 81.40)!!, 0.0001)
        assertEquals(81.25, selectBondQuotePrice(81.10, 81.25, null)!!, 0.0001)
        assertEquals(81.10, selectBondQuotePrice(81.10, null, null)!!, 0.0001)
        assertEquals(null, selectBondQuotePrice(null, null, null))
    }

    @Test fun selectsMainTradingBoardForEachAssetType() {
        assertEquals(listOf("TQOB", "TQCB"), preferredMoexBoards("Облигация"))
        assertEquals(listOf("TQBR"), preferredMoexBoards("Акция"))
        assertEquals(listOf("TQTF"), preferredMoexBoards("Фонд"))
        assertEquals(emptyList<String>(), preferredMoexBoards("Другое"))
    }

    @Test fun keepsSharePricesUnchanged() {
        assertEquals(296.25, normalizeMoexPrice(296.25, "Акция", null), 0.0001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesBondQuoteWhenFaceValueIsMissing() {
        normalizeMoexPrice(78.07, "Облигация", null, 5.80)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesBondQuoteWhenAccruedInterestIsMissing() {
        normalizeMoexPrice(78.07, "Облигация", 1_000.0, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesNegativeAccruedInterest() {
        normalizeMoexPrice(78.07, "Облигация", 1_000.0, -0.01)
    }
    @Test fun routesGoldSpotToCurrencyMarketAndCetsBoard() {
        assertEquals(MoexMarketRoute("currency", "selt", "CETS"), moexMarketRoute("GLDRUB_TOM", "Золото"))
        assertEquals(MoexMarketRoute("currency", "selt", "CETS"), moexMarketRoute("GLDRUB_TOD", "Золото"))
    }

    @Test fun keepsRegularSharesAndFundsOnStockMarket() {
        assertEquals(MoexMarketRoute("stock", "shares"), moexMarketRoute("SBER", "Акция"))
        assertEquals(MoexMarketRoute("stock", "shares"), moexMarketRoute("GOLD", "Фонд"))
    }

    @Test fun triesGoldTomBeforeTodAndPreservesExplicitTodPreference() {
        assertEquals(listOf("GLDRUB_TOM", "GLDRUB_TOD"), moexQuoteCandidates("GLDRUB_TOM"))
        assertEquals(listOf("GLDRUB_TOD", "GLDRUB_TOM"), moexQuoteCandidates("GLDRUB_TOD"))
        assertEquals(listOf("SBER"), moexQuoteCandidates("SBER"))
    }

    @Test fun spotGoldQuoteUsesLastThenWeightedAverageAndRejectsMissingValues() {
        assertEquals(10_250.0, selectSpotQuotePrice(10_250.0, 10_240.0)!!, 0.0001)
        assertEquals(10_240.0, selectSpotQuotePrice(null, 10_240.0)!!, 0.0001)
        assertEquals(null, selectSpotQuotePrice(null, null))
        assertEquals(null, selectSpotQuotePrice(0.0, -1.0))
    }

}
