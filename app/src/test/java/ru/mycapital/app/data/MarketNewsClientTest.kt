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
    fun parsesOnlyFutureRelevantRowsFromCbrStatisticsCalendar() {
        val html = """
            <table>
              <tr><th>Дата публикации</th><th>Время</th><th>Информация</th></tr>
              <tr><td>19.11.2026</td><td>16:00</td><td>Общий объем международных резервных активов</td></tr>
              <tr><td>27.11.2026</td><td>11:00</td><td>Денежная база в узком определении</td></tr>
              <tr><td>02.11.2026</td><td>—</td><td>Инфляция в России</td></tr>
              <tr><td>20.11.2026</td><td>—</td><td>Сведения о страховщиках</td></tr>
            </table>
        """.trimIndent()
        val events = parseCbrStatisticsCalendar(html, java.time.LocalDate.parse("2026-11-08"))
        assertEquals(2, events.size)
        assertTrue(events.all { it.sourceUrl == "https://www.cbr.ru/statistics/indcalendar/" })
        assertTrue(events.any { it.title.contains("международных резервных") })
        assertTrue(events.any { it.title.contains("Денежная база") })
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
            "Эмитент: ТестФинанс. Объём выпуска: 2 млрд руб. Цена размещения: 1000 руб. Купонная ставка: 12,5%. Размер купона: 100 руб. ISIN: RU000A10ABC1. Сбор заявок: 12 ноября 2026."
        )
        assertEquals("ТестФинанс", details.issuer)
        assertEquals("Облигации", details.instrumentType)
        assertEquals("2 млрд руб", details.placementVolume)
        assertEquals("1000 руб", details.placementPrice)
        assertEquals("12,5%", details.couponRate)
        assertEquals("100 руб", details.couponAmount)
        assertEquals("RU000A10ABC1", details.isin)
        assertTrue(details.applicationDates!!.contains("12 ноября 2026"))
        val unknown = parsePlacementDetails("Объявлено размещение", "Подробности будут позднее.")
        assertEquals(null, unknown.placementVolume)
        assertEquals(null, unknown.placementPrice)
        assertEquals(null, unknown.couponRate)
    }


    @Test
    fun usesExplicitPlacementApplicationDateAsCalendarDate() {
        val details = parsePlacementDetails(
            "Новый выпуск облигаций",
            "Сбор заявок: 12 ноября 2026. Цена размещения будет объявлена позднее."
        )
        val eventDate = parseExplicitPlacementEventDate(details)
        assertEquals(
            Instant.parse("2026-11-11T21:00:00Z").toEpochMilli(),
            eventDate
        )
    }

    @Test
    fun doesNotGuessYearForPlacementDatesWithoutYear() {
        val details = parsePlacementDetails(
            "Новый выпуск облигаций",
            "Сбор заявок: 12 ноября."
        )
        assertEquals(null, parseExplicitPlacementEventDate(details))
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
