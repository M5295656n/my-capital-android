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
