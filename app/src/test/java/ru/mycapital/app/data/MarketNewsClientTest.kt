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
    fun findsOnlyExplicitLinksToTrustedDisclosureDocuments() {
        val html = """
            <a href="https://example.com/prospectus.pdf">Проспект ценных бумаг</a>
            <a href="https://e-disclosure.ru/portal/files.aspx?id=123">Документы раскрытия информации</a>
            <a href="https://e-disclosure.ru.attacker.test/file">Решение о выпуске</a>
            <a href="http://e-disclosure.ru/file">Проспект</a>
        """.trimIndent()
        assertEquals(
            "https://e-disclosure.ru/portal/files.aspx?id=123",
            findOfficialPlacementDocumentUrl(html, "https://moex.com/news/123")
        )
        assertEquals(
            null,
            findOfficialPlacementDocumentUrl(
                """<a href="https://example.com/file.pdf">Проспект ценных бумаг</a>""",
                "https://moex.com/news/123"
            )
        )
    }

    @Test
    fun mergesPlacementDetailsWithoutOverwritingKnownAnnouncementValues() {
        val announcement = ParsedPlacementDetails(
            placementVolume = "2 млрд руб",
            couponRate = "12,5%"
        )
        val disclosure = ParsedPlacementDetails(
            placementVolume = "3 млрд руб",
            placementPrice = "1000 руб",
            couponRate = "13%",
            isin = "RU000A10ABC1"
        )
        val merged = mergePlacementDetails(announcement, disclosure)
        assertEquals("2 млрд руб", merged.placementVolume)
        assertEquals("12,5%", merged.couponRate)
        assertEquals("1000 руб", merged.placementPrice)
        assertEquals("RU000A10ABC1", merged.isin)
    }

    @Test
    fun discoversPdfBehindDisclosureLandingPage() {
        val html = """
            <html><body>
              <a href="https://e-disclosure.ru/portal/files/file.pdf">Скачать проспект эмиссии</a>
              <a href="https://example.com/file.pdf">Проспект выпуска</a>
              <a href="http://e-disclosure.ru/portal/files/terms.pdf">Условия выпуска</a>
              <a href="https://e-disclosure.ru/portal/other">Главная</a>
            </body></html>
        """.trimIndent()
        val urls = findOfficialPlacementDocumentUrls(html, "https://e-disclosure.ru/portal/disclosure")
        assertEquals(listOf("https://e-disclosure.ru/portal/files/file.pdf"), urls)
    }

    @Test
    fun findsExplicitDocumentPageLinksOnDisclosurePage() {
        val html = """
            <a href="/portal/files/decision">Решение о выпуске</a>
            <a href="/portal/files/terms">Условия размещения</a>
        """.trimIndent()
        val urls = findOfficialPlacementDocumentUrls(html, "https://e-disclosure.ru/portal/issuer")
        assertEquals(2, urls.size)
        assertTrue(urls.all { it.startsWith("https://e-disclosure.ru/") })
    }

    @Test
    fun rejectsNonPdfBytesBeforeCallingPdfParser() {
        assertEquals(null, extractPlacementTextFromPdf("not a pdf".toByteArray()))
    }

    @Test
    fun extractsPlacementTermsFromExplicitHtmlTableLabels() {
        val html = """
            <table>
              <tr><th>Эмитент</th><td>ООО ТестФинанс</td></tr>
              <tr><td>Объём выпуска</td><td>5 млрд руб.</td></tr>
              <tr><td>Цена размещения</td><td>100% от номинала</td></tr>
              <tr><td>Ставка купона</td><td>14,2% годовых</td></tr>
              <tr><td>Размер купона</td><td>35,50 руб.</td></tr>
              <tr><td>ISIN</td><td>RU000A10ABC1</td></tr>
              <tr><td>Дата начала торгов</td><td>20.11.2026</td></tr>
            </table>
        """.trimIndent()
        val details = parsePlacementDetailsFromHtml(html, "Новый выпуск облигаций")
        assertEquals("ООО ТестФинанс", details.issuer)
        assertEquals("5 млрд руб.", details.placementVolume)
        assertEquals("100% от номинала", details.placementPrice)
        assertEquals("14,2%", details.couponRate)
        assertEquals("35,50 руб.", details.couponAmount)
        assertEquals("RU000A10ABC1", details.isin)
        assertTrue(details.tradingStartDate!!.contains("20.11.2026"))
    }

    @Test
    fun doesNotInferCouponRateFromCouponAmountInHtmlTable() {
        val html = """
            <table><tr><td>Размер купона</td><td>42 руб.</td></tr></table>
        """.trimIndent()
        val details = parsePlacementDetailsFromHtml(html, "Облигации")
        assertEquals("42 руб.", details.couponAmount)
        assertEquals(null, details.couponRate)
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
    fun keepsPlacementStartAndTradingStartAsSeparateCalendarDates() {
        val details = parsePlacementDetails(
            "Новый выпуск облигаций",
            "Дата начала размещения: 19.11.2026. Начало торгов: 23.11.2026."
        )
        assertEquals(null, details.applicationDates)
        assertTrue(details.placementStartDate!!.contains("19.11.2026"))
        assertTrue(details.tradingStartDate!!.contains("23.11.2026"))
        assertEquals(
            Instant.parse("2026-11-18T21:00:00Z").toEpochMilli(),
            parseExplicitPlacementEventDate(details)
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


    @Test
    fun recognizesPlacementTermAliasesOnlyWhenExplicitlyLabeled() {
        val details = parsePlacementDetails(
            "Новый выпуск облигаций",
            "Полное наименование эмитента: АО Тест. Общий объём размещения: 3 млрд руб. " +
                "Цена размещения одной облигации: 1000 руб. " +
                "Процентная ставка по купону: 13,4%. " +
                "Размер купонного дохода: 42,50 руб. " +
                "Дата сбора заявок: 15 ноября 2026. " +
                "Допуск к торгам: 20 ноября 2026."
        )
        assertEquals("АО Тест", details.issuer)
        assertEquals("3 млрд руб", details.placementVolume)
        assertEquals("1000 руб", details.placementPrice)
        assertEquals("13,4%", details.couponRate)
        assertEquals("42,50 руб", details.couponAmount)
        assertTrue(details.applicationDates!!.contains("15 ноября 2026"))
        assertTrue(details.tradingStartDate!!.contains("20 ноября 2026"))
    }

    @Test
    fun recognizesHtmlDisclosureLabelAliases() {
        val html = """
            <table>
              <tr><th>Полное наименование эмитента</th><td>ПАО Пример</td></tr>
              <tr><th>Количество размещаемых ценных бумаг</th><td>1 000 000 шт.</td></tr>
              <tr><th>Цена предложения</th><td>99,5% от номинала</td></tr>
              <tr><th>Процентная ставка по купону</th><td>11,8% годовых</td></tr>
              <tr><th>Сумма выплаты на одну облигацию</th><td>29,45 руб.</td></tr>
              <tr><th>Период букбилдинга</th><td>16 ноября 2026</td></tr>
              <tr><th>Дата допуска к торгам</th><td>23 ноября 2026</td></tr>
            </table>
        """.trimIndent()
        val details = parsePlacementDetailsFromHtml(html, "Размещение облигаций")
        assertEquals("ПАО Пример", details.issuer)
        assertEquals("1 000 000 шт.", details.placementVolume)
        assertEquals("99,5% от номинала", details.placementPrice)
        assertEquals("11,8%", details.couponRate)
        assertEquals("29,45 руб.", details.couponAmount)
        assertTrue(details.applicationDates!!.contains("16 ноября 2026"))
        assertTrue(details.placementStartDate!!.contains("19 ноября 2026"))
        assertTrue(details.tradingStartDate!!.contains("23 ноября 2026"))
    }

    @Test
    fun doesNotInferMissingPlacementValuesOrDatesFromNarrative() {
        val details = parsePlacementDetails(
            "Компания готовит размещение облигаций",
            "Объём выпуска составит значительную сумму. Цена будет объявлена позднее. " +
                "Купон может быть привлекательным. Сбор заявок ожидается в ноябре."
        )
        assertEquals(null, details.placementVolume)
        assertEquals(null, details.placementPrice)
        assertEquals(null, details.couponRate)
        assertEquals(null, details.couponAmount)
        assertEquals(null, details.applicationDates)
        assertEquals(null, details.tradingStartDate)
    }


    @Test
    fun recognizesExplicitPlacementStartDateLabels() {
        val byDate = parsePlacementDetails("Облигации", "Дата начала размещения: 17.11.2026.")
        val byFrom = parsePlacementDetails("Облигации", "Размещение с 18.11.2026.")
        val applications = parsePlacementDetails("Облигации", "Сбор заявок: 16.11.2026.")
        assertTrue(byDate.placementStartDate!!.contains("17.11.2026"))
        assertTrue(byFrom.placementStartDate!!.contains("18.11.2026"))
        assertTrue(applications.applicationDates!!.contains("16.11.2026"))
        assertEquals(null, applications.placementStartDate)
    }


    @Test
    fun parsesDisclosureRowsWithTdLabelsAndIgnoresUnrelatedAdjacentCells() {
        val html = """
            <table>
              <tr><td>Сведения о выпуске</td><td>Общие данные</td><td>Без параметра</td></tr>
              <tr><td>Объём эмиссии</td><td>750 млн руб.</td></tr>
              <tr><td>ISIN</td><td>RU000A10XYZ9</td></tr>
              <tr><td>Дата начала размещения</td><td>19.11.2026</td></tr>
            </table>
            <dl>
              <dt>Цена размещения</dt><dd>1000 руб.</dd>
              <dt>Ставка купона</dt><dd>12,7% годовых</dd>
            </dl>
        """.trimIndent()
        val details = parsePlacementDetailsFromHtml(html, "Выпуск облигаций")
        assertEquals("750 млн руб.", details.placementVolume)
        assertEquals("RU000A10XYZ9", details.isin)
        assertTrue(details.placementStartDate!!.contains("19.11.2026"))
        assertEquals("1000 руб", details.placementPrice)
        assertEquals("12,7%", details.couponRate)
    }

    @Test
    fun doesNotUseUnrelatedTableValueAsAnotherParameterLabel() {
        val html = """
            <table>
              <tr><td>Цена размещения</td><td>1000 руб.</td><td>Ставка купона</td><td>Не сообщено</td></tr>
            </table>
        """.trimIndent()
        val details = parsePlacementDetailsFromHtml(html, "Размещение")
        assertEquals("1000 руб", details.placementPrice)
        assertEquals(null, details.couponRate)
    }
}
