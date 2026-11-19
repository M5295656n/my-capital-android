package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexIndexCandlesClientTest {
    @Test
    fun parsesOhlcCandlesByNamedColumnsAndOrdersThem() {
        val json = """{
            "candles": {
                "columns": ["begin", "open", "close", "high", "low"],
                "data": [
                    ["2026-11-18 10:00:00", 2500.0, 2520.0, 2530.0, 2490.0],
                    ["2026-11-17 10:00:00", 2480.0, 2500.0, 2510.0, 2470.0]
                ]
            }
        }"""
        val candles = MoexIndexCandlesClient.parseCandles(json)
        assertEquals(2, candles.size)
        assertEquals("2026-11-17 10:00:00", candles.first().begin)
        assertEquals(2480.0, candles.first().open, 0.0)
        assertEquals(2510.0, candles.first().high, 0.0)
        assertEquals(2470.0, candles.first().low, 0.0)
        assertEquals(2500.0, candles.first().close, 0.0)
    }

    @Test
    fun ignoresMalformedAndNonPositiveCandles() {
        val json = """{
            "candles": {
                "columns": ["begin", "open", "high", "low", "close"],
                "data": [
                    ["2026-11-18 10:00:00", 2500.0, 2530.0, 2490.0, 2520.0],
                    ["2026-11-18 10:10:00", 2500.0, 2480.0, 2490.0, 2520.0],
                    ["2026-11-18 10:20:00", null, 2530.0, 2490.0, 2520.0]
                ]
            }
        }"""
        val candles = MoexIndexCandlesClient.parseCandles(json)
        assertEquals(1, candles.size)
        assertTrue(candles.all { it.high >= it.low })
    }
}
