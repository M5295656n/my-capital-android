package ru.mycapital.app.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongShortDividendClientTest {
    private val today = LocalDate.of(2026, 11, 5)

    @Test
    fun parsesAnnouncedRecommendedAndForecastDividends() {
        val csv = """secid,company,record_date,last_buy_date,value,currency,yield_pct,status,payment_date
SBER,"Сбербанк",2026-11-20,2026-11-19,12.5,RUB,4.2,confirmed,2026-12-05
LKOH,"ЛУКОЙЛ",2026-12-10,2026-12-09,535,RUB,9.1,recommended,2026-12-25
X5,"X5",2027-01-10,2027-01-09,98,RUB,5.0,forecast,2027-01-25
"""
        val events = parseLongShortDividendCsv(csv, today)
        assertEquals(3, events.size)
        assertEquals("ANNOUNCED", events[0].status)
        assertEquals("RECOMMENDED", events[1].status)
        assertEquals("FORECAST", events[2].status)
        assertEquals(535.0, events[1].amountPerUnit, 0.000001)
    }

    @Test
    fun strongestStatusWinsForSameDividendEvent() {
        val csv = """secid,company,record_date,last_buy_date,value,currency,yield_pct,status,payment_date
SBER,"Сбербанк",2026-11-20,2026-11-19,12.5,RUB,4.2,forecast,2026-12-05
SBER,"Сбербанк",2026-11-20,2026-11-19,12.5,RUB,4.2,recommended,2026-12-05
SBER,"Сбербанк",2026-11-20,2026-11-19,12.5,RUB,4.2,confirmed,2026-12-05
"""
        val events = parseLongShortDividendCsv(csv, today)
        assertEquals(1, events.size)
        assertEquals("ANNOUNCED", events.single().status)
    }

    @Test
    fun ignoresPastRowsAndNonRubles() {
        val csv = """secid,company,record_date,last_buy_date,value,currency,yield_pct,status,payment_date
SBER,"Сбербанк",2026-10-20,2026-10-19,12.5,RUB,4.2,confirmed,2026-10-25
LKOH,"ЛУКОЙЛ",2026-12-10,2026-12-09,535,USD,9.1,recommended,2026-12-25
"""
        assertTrue(parseLongShortDividendCsv(csv, today).isEmpty())
    }

    @Test
    fun quotedCommasAreParsed() {
        val csv = """secid,company,record_date,last_buy_date,value,currency,yield_pct,status,payment_date
SBER,"Банк, Сбербанк",2026-11-20,,12.5,RUB,,confirmed,2026-12-05
"""
        assertEquals(1, parseLongShortDividendCsv(csv, today).size)
    }
}
