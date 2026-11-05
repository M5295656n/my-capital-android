package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MoexSecurityHistoryClientTest {
    @Test
    fun parsesDailyCloseAndSkipsNullOrInvalidRows() {
        val json = """{
            "candles": {
                "columns": ["begin", "close"],
                "data": [
                    ["2026-09-15 00:00:00", 128.3],
                    ["2026-09-16 00:00:00", null],
                    ["2026-09-17 00:00:00", 0],
                    ["2026-09-18 00:00:00", 131.5]
                ]
            }
        }"""

        val points = parseDailyCandles(json)

        assertEquals(2, points.size)
        assertEquals(LocalDate.parse("2026-09-15"), points[0].date)
        assertEquals(128.3, points[0].close, 0.000001)
        assertEquals(LocalDate.parse("2026-09-18"), points[1].date)
    }

    @Test
    fun emptyCandlePageReturnsEmptyList() {
        val json = """{"candles":{"columns":["begin","close"],"data":[]}}"""
        assertTrue(parseDailyCandles(json).isEmpty())
    }
}
