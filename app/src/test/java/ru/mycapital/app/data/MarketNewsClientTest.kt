package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MarketNewsClientTest {
    @Test
    fun parsesOfficialRssAndMatchesOnlyPortfolioTickers() {
        val now = Instant.parse("2026-11-08T12:00:00Z").toEpochMilli()
        val xml = """
            <rss version="2.0"><channel><item>
              <title>Сбербанк опубликовал финансовые результаты</title>
              <description>Компания SBER представила результаты за квартал.</description>
              <link>https://example.test/news/1</link>
              <guid>news-1</guid>
              <pubDate>Sun, 08 Nov 2026 10:00:00 GMT</pubDate>
            </item></channel></rss>
        """.trimIndent()
        val parsed = parseMarketNewsRss(xml, "Тестовый источник", "COMPANIES", setOf("SBER", "LKOH"), now)
        assertEquals(1, parsed.size)
        assertEquals("Сбербанк опубликовал финансовые результаты", parsed.single().title)
        assertEquals(listOf("SBER"), parsed.single().relatedTickers)
        assertEquals("https://example.test/news/1", parsed.single().sourceUrl)
    }

    @Test
    fun parsesFutureCbrKeyRateMeetingFromOfficialCalendar() {
        val html = """
            <html><body>
              <h2>2026 год</h2>
              <div>18 декабря 2026 года Заседание Совета директоров Банка России по ключевой ставке</div>
              <div>30 декабря 2026 года Резюме обсуждения ключевой ставки</div>
            </body></html>
        """.trimIndent()
        val events = parseCbrKeyRateCalendar(html, java.time.LocalDate.parse("2026-11-08"))
        assertEquals(1, events.size)
        assertEquals("CBR:KEY_RATE_MEETING:2026-12-18", events.single().sourceKey)
        assertEquals("ECONOMY", events.single().category)
        assertTrue(events.single().summary.contains("13:30"))
    }

    @Test
    fun ignoresPastCbrKeyRateMeetings() {
        val html = """<div>23 октября 2026 года Заседание Совета директоров Банка России по ключевой ставке</div>"""
        assertTrue(parseCbrKeyRateCalendar(html, java.time.LocalDate.parse("2026-11-08")).isEmpty())
    }

    @Test
    fun classifiesEquityAndBondPlacementsSeparatelyFromGeneralNews() {
        val placementCases = listOf(
            "Компания объявила IPO" to "",
            "Старт сбора заявок на облигации" to "Новый выпуск биржевых облигаций",
            "Цена размещения акций определена" to "",
            "Начало торгов акциями" to "",
            "Объявлена дополнительная эмиссия" to ""
        )
        placementCases.forEach { (title, summary) ->
            assertEquals("NEW_ISSUES", classifyMarketNews(title, summary, "COMPANIES"))
        }
        assertEquals(
            "COMPANIES",
            classifyMarketNews("Совет директоров утвердил дивиденды", "", "COMPANIES")
        )
    }

    @Test
    fun extractsOnlyExplicitPlacementTerms() {
        val details = parsePlacementDetails(
            "Новый выпуск облигаций",
            "Эмитент: ТестФинанс. Объём выпуска: 2 млрд руб. Цена размещения: 1000 руб. Купонная ставка: 12,5%. Сбор заявок: 12 ноября 2026."
        )
        assertEquals("ТестФинанс", details.issuer)
        assertEquals("Облигации", details.instrumentType)
        assertEquals("2 млрд руб", details.placementVolume)
        assertEquals("1000 руб", details.placementPrice)
        assertEquals("12,5%", details.couponRate)
        assertTrue(details.applicationDates!!.contains("12 ноября 2026"))
        val unknown = parsePlacementDetails("Объявлено размещение", "Подробности будут позднее.")
        assertEquals(null, unknown.placementVolume)
        assertEquals(null, unknown.placementPrice)
        assertEquals(null, unknown.couponRate)
    }

    @Test
    fun doesNotMatchTickerInsideAnotherWord() {
        assertTrue(matchPortfolioTickers("SBERBANK and LKOH", setOf("SBER", "LKOH")) == listOf("LKOH"))
    }

    @Test
    fun skipsFutureDatedAndMalformedRssItems() {
        val now = Instant.parse("2026-11-08T12:00:00Z").toEpochMilli()
        val xml = """
            <rss><channel>
              <item><title>Будущая новость</title><link>https://example.test/future</link><pubDate>Mon, 09 Nov 2026 10:00:00 GMT</pubDate></item>
              <item><title>Без даты</title><link>https://example.test/no-date</link></item>
            </channel></rss>
        """.trimIndent()
        assertTrue(parseMarketNewsRss(xml, "Тест", "ECONOMY", emptySet(), now).isEmpty())
    }
}
