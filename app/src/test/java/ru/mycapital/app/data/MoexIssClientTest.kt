package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MoexIssClientTest {
    @Test fun convertsBondPercentQuoteToFullRubPriceIncludingAccruedInterest() {
        assertEquals(786.50, normalizeMoexPrice(78.07, "Облигация", 1_000.0, 5.80), 0.0001)
        assertEquals(813.98, normalizeMoexPrice(81.398, "Облигация", 1_000.0, 0.0), 0.0001)
    }

    @Test fun selectsMainTradingBoardForEachAssetType() {
        assertEquals("TQBR", preferredMoexBoard("Акция"))
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
}
