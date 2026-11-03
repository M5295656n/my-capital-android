package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecimalInputTest {
    @Test
    fun parsesRussianDecimalComma() {
        assertEquals(1234.56, parseDecimalInput("1234,56")!!, 0.000001)
    }

    @Test
    fun parsesInvariantDecimalPoint() {
        assertEquals(1234.56, parseDecimalInput("1234.56")!!, 0.000001)
    }

    @Test
    fun ignoresGroupingWhitespace() {
        assertEquals(1234.56, parseDecimalInput("1 234,56")!!, 0.000001)
    }

    @Test
    fun rejectsMalformedAndNonFiniteValues() {
        assertNull(parseDecimalInput("1,2.3"))
        assertNull(parseDecimalInput("NaN"))
        assertNull(parseDecimalInput("Infinity"))
    }
}
