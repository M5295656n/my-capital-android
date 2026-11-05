package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketOverviewCalculationsTest {
    @Test
    fun percentChangeUsesPreviousOfficialRateAsBase() {
        assertEquals(2.5, calculatePercentChange(102.5, 100.0)!!, 0.000001)
        assertEquals(-2.5, calculatePercentChange(97.5, 100.0)!!, 0.000001)
    }

    @Test
    fun percentChangeRejectsInvalidPreviousRate() {
        assertNull(calculatePercentChange(100.0, 0.0))
        assertNull(calculatePercentChange(Double.NaN, 100.0))
    }

    @Test
    fun previousCbrRateSkipsDuplicateCurrentFixings() {
        val xml = """<ValCurs><Record Date="03.11.2026"><Nominal>1</Nominal><Value>91,0000</Value></Record><Record Date="04.11.2026"><Nominal>1</Nominal><Value>92,0000</Value></Record><Record Date="05.11.2026"><Nominal>1</Nominal><Value>92,0000</Value></Record></ValCurs>"""
        assertEquals(91.0, parsePreviousCbrRate(xml, 92.0)!!, 0.000001)
    }

    @Test
    fun previousCbrRateReturnsNullWhenNoEarlierDistinctFixingExists() {
        val xml = """<ValCurs><Record Date="05.11.2026"><Value>92,0000</Value></Record></ValCurs>"""
        assertNull(parsePreviousCbrRate(xml, 92.0))
    }
}
