package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MoexIssClientTest {
    @Test fun convertsBondPercentQuoteToRublesUsingFaceValue() {
        assertEquals(780.70, normalizeMoexPrice(78.07, "Облигация", 1_000.0), 0.0001)
        assertEquals(813.98, normalizeMoexPrice(81.398, "Облигация", 1_000.0), 0.0001)
    }

    @Test fun keepsSharePricesUnchanged() {
        assertEquals(296.25, normalizeMoexPrice(296.25, "Акция", null), 0.0001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesBondQuoteWhenFaceValueIsMissing() {
        normalizeMoexPrice(78.07, "Облигация", null)
    }
}
