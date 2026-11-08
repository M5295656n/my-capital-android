package ru.mycapital.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class MarketNewsFeed(
    val url: String,
    val sourceName: String,
    val category: String
)

internal data class ParsedMarketNews(
    val sourceKey: String,
    val category: String,
    val publishedAt: Long,
    val eventDate: Long,
    val title: String,
    val summary: String,
    val sourceName: String,
    val sourceUrl: String,
    val relatedTickers: List<String>,
    val issuer: String? = null,
    val instrumentType: String? = null,
    val placementVolume: String? = null,
    val placementPrice: String? = null,
    val couponRate: String? = null,
    val applicationDates: String? = null,
    val tradingStartDate: String? = null,
    val isin: String? = null,
    val couponAmount: String? = null,
    val detailsSourceUrl: String? = null
)

internal val defaultMarketNewsFeeds = listOf(
    MarketNewsFeed("https://www.cbr.ru/rss/RssPress", "Банк России", "ECONOMY"),
    MarketNewsFeed("https://www.cbr.ru/rss/eventrss", "Банк России", "ECONOMY"),
    MarketNewsFeed("https://moex.com/export/news.aspx?cat=100", "Московская биржа", "COMPANIES")
)


internal data class ParsedMarketCalendarEvent(
    val sourceKey: String, val category: String, val eventDate: Long,
    val title: String, val summary: String, val sourceName: String, val sourceUrl: String
)

internal fun parseCbrKeyRateCalendar(
    html: String,
    today: LocalDate = LocalDate.now(ZoneId.of("Europe/Moscow"))
): List<ParsedMarketCalendarEvent> {
    val text = Jsoup.parse(html).text().replace(Regex("\\s+"), " ")
    val monthNumbers = mapOf(
        "января" to 1, "февраля" to 2, "марта" to 3, "апреля" to 4,
        "мая" to 5, "июня" to 6, "июля" to 7, "августа" to 8,
        "сентября" to 9, "октября" to 10, "ноября" to 11, "декабря" to 12
    )
    val pattern = Regex(
        "(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+(\\d{4})\\s+года\\s+Заседание Совета директоров Банка России по ключевой ставке",
        RegexOption.IGNORE_CASE
    )
    return pattern.findAll(text).mapNotNull { match ->
        val date = runCatching {
            LocalDate.of(match.groupValues[3].toInt(), monthNumbers.getValue(match.groupValues[2].lowercase(Locale.ROOT)), match.groupValues[1].toInt())
        }.getOrNull() ?: return@mapNotNull null
        if (date.isBefore(today)) return@mapNotNull null
        ParsedMarketCalendarEvent(
            sourceKey = "CBR:KEY_RATE_MEETING:$date",
            category = "ECONOMY",
            eventDate = date.atStartOfDay(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli(),
            title = "Заседание Совета директоров Банка России по ключевой ставке",
            summary = "Ожидаемое время публикации пресс-релиза — 13:30 МСК; пресс-конференции — 15:00 МСК. Время указано Банком России как предполагаемое.",
            sourceName = "Банк России",
            sourceUrl = "https://www.cbr.ru/dkp/cal_mp"
        )
    }.distinctBy { it.sourceKey }.sortedBy { it.eventDate }
}



internal fun parseCbrStatisticsCalendar(
    html: String,
    today: LocalDate = LocalDate.now(ZoneId.of("Europe/Moscow"))
): List<ParsedMarketCalendarEvent> {
    val document = Jsoup.parse(html)
    val datePattern = Regex("""(\d{2})\.(\d{2})\.(\d{4})""")
    val relevant = listOf(
        "инфляц", "денежн.*баз", "международн.*резерв", "внешн.*торгов",
        "платежн.*баланс", "внешн.*долг", "рынк.*акци", "ключев.*ставк",
        "кредит", "денежн.*агрегат", "ruonia", "ставк.*по кредит"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    return document.select("tr").mapNotNull { row ->
        val cells = row.select("th, td")
        if (cells.size < 2) return@mapNotNull null
        val dateMatch = datePattern.find(cells.first().text()) ?: return@mapNotNull null
        val date = runCatching {
            LocalDate.of(dateMatch.groupValues[3].toInt(), dateMatch.groupValues[2].toInt(), dateMatch.groupValues[1].toInt())
        }.getOrNull() ?: return@mapNotNull null
        if (date.isBefore(today)) return@mapNotNull null

        val title = cells.last().text().replace(Regex("\\s+"), " ").trim()
        if (title.isBlank() || relevant.none { it.containsMatchIn(title) }) return@mapNotNull null
        val time = cells.getOrNull(1)?.text()?.trim().orEmpty()
        val period = cells.drop(2).joinToString(" · ") { it.text().trim() }.trim(' ', '·')
        val details = buildList {
            if (time.isNotBlank() && time != "—" && time != "-") add("Время публикации: " + time)
            if (period.isNotBlank() && period != title) add("Период данных: " + period)
        }.joinToString(". ")
        ParsedMarketCalendarEvent(
            sourceKey = "CBR:STAT:" + date + ":" + title.lowercase(Locale.ROOT).hashCode(),
            category = "ECONOMY",
            eventDate = date.atStartOfDay(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli(),
            title = title,
            summary = "Запланированная публикация официальной статистики Банка России. " + details,
            sourceName = "Банк России",
            sourceUrl = "https://www.cbr.ru/statistics/indcalendar/"
        )
    }.distinctBy { it.sourceKey }.sortedBy { it.eventDate }
}

internal data class ParsedPlacementDetails(
    val issuer: String? = null,
    val instrumentType: String? = null,
    val placementVolume: String? = null,
    val placementPrice: String? = null,
    val couponRate: String? = null,
    val applicationDates: String? = null,
    val tradingStartDate: String? = null,
    val isin: String? = null,
    val couponAmount: String? = null
)

internal fun parsePlacementDetails(title: String, summary: String): ParsedPlacementDetails {
    val text = "$title $summary".replace(Regex("\\s+"), " ")
    fun capture(pattern: Regex): String? = pattern.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    val issuer = capture(Regex("(?:эмитент|компания)\\s*[:—-]\\s*([^.;,]{2,100})", RegexOption.IGNORE_CASE))
    val instrumentType = when {
        Regex("\\bIPO\\b|первичн\\w* размещени\\w* акци", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Акции (IPO)"
        Regex("\\bSPO\\b|дополнительн\\w* эмисс", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Акции (SPO / допэмиссия)"
        Regex("облигац", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Облигации"
        Regex("акци", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Акции"
        else -> null
    }
    val volume = capture(Regex("объ[её]м(?: выпуска| размещения)?\\s*[:—-]?\\s*([0-9][0-9\\s.,]*(?:млн|млрд|тыс)?\\s*(?:₽|руб(?:лей)?|шт\\.?|акций|облигаций)?)", RegexOption.IGNORE_CASE))
    val price = capture(Regex("(?:цена размещения|цена предложения|ценовой диапазон|цена)\\s*[:—-]?\\s*([0-9][0-9\\s.,]*(?:[-–—][0-9][0-9\\s.,]*)?\\s*(?:₽|руб(?:лей)?))", RegexOption.IGNORE_CASE))
    val coupon = capture(Regex("(?:купонная ставка|ставка купона|доходность купона)\\s*[:—-]?\\s*([0-9]+(?:[.,][0-9]+)?\\s*%)", RegexOption.IGNORE_CASE))
    val couponAmount = capture(Regex("(?:размер купона|купонный доход|сумма купона|купон)\\s*[:—-]?\\s*([0-9][0-9\\s.,]*\\s*(?:₽|руб(?:лей)?))", RegexOption.IGNORE_CASE))
    val isin = Regex("\\b[A-Z]{2}[A-Z0-9]{10}\\b").find(text)?.value
    val applicationDates = capture(Regex("((?:сбор заявок|приём заявок|прием заявок|книга заявок)[^.;]{0,120}(?:\\d{1,2}[. ]\\d{1,2}(?:[. ]\\d{2,4})?|\\d{1,2}\\s+[а-яё]+(?:\\s+\\d{4})?))", RegexOption.IGNORE_CASE))
    val tradingDate = capture(Regex("(?:начало торгов|первые торги|торги начнутся)\\s*[:—-]?\\s*(\\d{1,2}[. ]\\d{1,2}(?:[. ]\\d{2,4})?|\\d{1,2}\\s+[а-яё]+(?:\\s+\\d{4})?)", RegexOption.IGNORE_CASE))
    return ParsedPlacementDetails(issuer, instrumentType, volume, price, coupon, applicationDates, tradingDate, isin, couponAmount)
}


internal fun parseExplicitPlacementEventDate(
    details: ParsedPlacementDetails,
    zone: ZoneId = ZoneId.of("Europe/Moscow")
): Long? {
    val candidates = listOfNotNull(details.applicationDates, details.tradingStartDate)
    val monthNumbers = mapOf(
        "января" to 1, "февраля" to 2, "марта" to 3, "апреля" to 4,
        "мая" to 5, "июня" to 6, "июля" to 7, "августа" to 8,
        "сентября" to 9, "октября" to 10, "ноября" to 11, "декабря" to 12
    )
    for (candidate in candidates) {
        val numeric = Regex("""(\d{1,2})[. ](\d{1,2})[. ](\d{4})""").find(candidate)
        val textual = Regex("""(\d{1,2})\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\s+(\d{4})""", RegexOption.IGNORE_CASE).find(candidate)
        val date = when {
            numeric != null -> runCatching {
                LocalDate.of(numeric.groupValues[3].toInt(), numeric.groupValues[2].toInt(), numeric.groupValues[1].toInt())
            }.getOrNull()
            textual != null -> runCatching {
                LocalDate.of(
                    textual.groupValues[3].toInt(),
                    monthNumbers.getValue(textual.groupValues[2].lowercase(Locale.ROOT)),
                    textual.groupValues[1].toInt()
                )
            }.getOrNull()
            else -> null
        }
        if (date != null) return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }
    return null
}

internal fun classifyMarketNews(title: String, summary: String, fallbackCategory: String): String {
    val text = "$title $summary".lowercase(Locale.ROOT)
    val issuancePatterns = listOf(
        Regex("\\bipo\\b", RegexOption.IGNORE_CASE),
        Regex("\\bspo\\b", RegexOption.IGNORE_CASE),
        Regex("первичн\\w* размещен"),
        Regex("нов\\w* выпуск\\w* облигац"),
        Regex("размещени\\w* облигац"),
        Regex("сбор\\w* заявок"),
        Regex("книг\\w* заявок"),
        Regex("дополнительн\\w* эмисс"),
        Regex("начал\\w* торгов\\w* (акци\\w*|облигац)"),
        Regex("дата размещени"),
        Regex("цена размещени"),
        Regex("объем размещени"),
        Regex("биржев\\w* облигац")
    )
    return if (issuancePatterns.any { it.containsMatchIn(text) }) "NEW_ISSUES" else fallbackCategory
}

internal fun matchPortfolioTickers(text: String, tickers: Set<String>): List<String> {
    val normalized = text.uppercase(Locale.ROOT)
    return tickers.map { it.trim().uppercase(Locale.ROOT) }
        .filter { ticker ->
            ticker.isNotBlank() &&
                Regex("(?<![A-ZА-ЯЁ0-9])" + Regex.escape(ticker) + "(?![A-ZА-ЯЁ0-9])")
                    .containsMatchIn(normalized)
        }
        .distinct()
        .sorted()
}

internal fun parseMarketNewsRss(
    xml: String,
    sourceName: String,
    category: String,
    portfolioTickers: Set<String>,
    now: Long = System.currentTimeMillis()
): List<ParsedMarketNews> {
    val document = Jsoup.parse(xml, "", Parser.xmlParser())
    val items = document.select("item")
    if (items.isEmpty() && document.select("entry").isEmpty()) {
        throw IOException("RSS-лента $sourceName не содержит элементов item/entry")
    }
    val nodes = if (items.isNotEmpty()) items else document.select("entry")
    return nodes.mapNotNull { item ->
        val title = item.selectFirst("title")?.text()?.trim().orEmpty()
        val url = item.selectFirst("link")?.let { link ->
            link.attr("href").ifBlank { link.text() }
        }?.trim().orEmpty()
        val description = item.selectFirst("description")?.text()?.trim()
            ?.ifBlank { null }
            ?: item.selectFirst("summary")?.text()?.trim().orEmpty()
        val dateText = item.selectFirst("pubDate")?.text()?.trim()
            ?.ifBlank { null }
            ?: item.selectFirst("published")?.text()?.trim()
            ?: item.selectFirst("updated")?.text()?.trim().orEmpty()
        val publishedAt = parseRssDate(dateText) ?: return@mapNotNull null
        if (title.isBlank() || url.isBlank() || publishedAt > now + 5 * 60_000L) return@mapNotNull null
        val guid = item.selectFirst("guid")?.text()?.trim().orEmpty().ifBlank { url }
        val key = "$sourceName:$guid"
        val placement = parsePlacementDetails(title, description)
        ParsedMarketNews(
            sourceKey = key,
            category = classifyMarketNews(title, description, category),
            publishedAt = publishedAt,
            eventDate = parseExplicitPlacementEventDate(placement) ?: publishedAt,
            title = title,
            summary = description.take(1200),
            sourceName = sourceName,
            sourceUrl = url,
            relatedTickers = matchPortfolioTickers("$title $description", portfolioTickers),
            issuer = placement.issuer,
            instrumentType = placement.instrumentType,
            placementVolume = placement.placementVolume,
            placementPrice = placement.placementPrice,
            couponRate = placement.couponRate,
            applicationDates = placement.applicationDates,
            tradingStartDate = placement.tradingStartDate,
            isin = placement.isin,
            couponAmount = placement.couponAmount
        )
    }.distinctBy { it.sourceKey }.sortedByDescending { it.publishedAt }
}

private fun parseRssDate(value: String): Long? {
    if (value.isBlank()) return null
    return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

class MarketNewsClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(22, TimeUnit.SECONDS)
        .build()

    fun getUpcomingKeyRateEvents(today: LocalDate = LocalDate.now(ZoneId.of("Europe/Moscow"))): List<ParsedMarketCalendarEvent> {
        val request = Request.Builder().url("https://www.cbr.ru/dkp/cal_mp")
            .header("User-Agent", "MyCapital-Android/0.3")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Банк России: HTTP ${response.code}")
            val html = response.body?.string() ?: throw IOException("Банк России вернул пустой календарь")
            return parseCbrKeyRateCalendar(html, today)
        }
    }


    fun getUpcomingStatisticsEvents(today: LocalDate = LocalDate.now(ZoneId.of("Europe/Moscow"))): List<ParsedMarketCalendarEvent> {
        val request = Request.Builder().url("https://www.cbr.ru/statistics/indcalendar/")
            .header("User-Agent", "MyCapital-Android/0.3")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Календарь статистики Банка России: HTTP " + response.code)
            val html = response.body?.string() ?: throw IOException("Банк России вернул пустой календарь статистики")
            return parseCbrStatisticsCalendar(html, today)
        }
    }

    private fun enrichPlacementFromLinkedPage(item: ParsedMarketNews): ParsedMarketNews {
        if (item.category != "NEW_ISSUES") return item
        val parsedUrl = runCatching { okhttp3.HttpUrl.get(item.sourceUrl) }.getOrNull() ?: return item
        // Follow only the MOEX article URL supplied by the official MOEX RSS feed.
        if (parsedUrl.host != "moex.com" && !parsedUrl.host.endsWith(".moex.com")) return item
        return runCatching {
            val request = Request.Builder().url(parsedUrl).header("User-Agent", "MyCapital-Android/0.3").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use item
                val finalHost = response.request.url.host
                if (finalHost != "moex.com" && !finalHost.endsWith(".moex.com")) return@use item
                val body = response.body ?: return@use item
                if (body.contentLength() > 512 * 1024L) return@use item
                val bytes = body.source().readByteArray(512 * 1024L + 1L)
                if (bytes.size > 512 * 1024) return@use item
                val pageText = Jsoup.parse(String(bytes, Charsets.UTF_8), item.sourceUrl).text()
                    .replace(Regex("\\s+"), " ").take(12000)
                val pageDetails = parsePlacementDetails(item.title, pageText)
                item.copy(
                    issuer = item.issuer ?: pageDetails.issuer,
                    instrumentType = item.instrumentType ?: pageDetails.instrumentType,
                    placementVolume = item.placementVolume ?: pageDetails.placementVolume,
                    placementPrice = item.placementPrice ?: pageDetails.placementPrice,
                    couponRate = item.couponRate ?: pageDetails.couponRate,
                    applicationDates = item.applicationDates ?: pageDetails.applicationDates,
                    tradingStartDate = item.tradingStartDate ?: pageDetails.tradingStartDate,
                    eventDate = parseExplicitPlacementEventDate(pageDetails) ?: item.eventDate,
                    isin = item.isin ?: pageDetails.isin,
                    couponAmount = pageDetails.couponAmount,
                    detailsSourceUrl = item.sourceUrl
                )
            }
        }.getOrDefault(item)
    }

    fun getLatestNews(portfolioTickers: Set<String>): List<ParsedMarketNews> {
        val result = mutableListOf<ParsedMarketNews>()
        val errors = mutableListOf<String>()
        defaultMarketNewsFeeds.forEach { feed ->
            try {
                val request = Request.Builder().url(feed.url)
                    .header("Accept", "application/rss+xml, application/xml, text/xml")
                    .header("User-Agent", "MyCapital-Android/0.3")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body?.string() ?: throw IOException("Пустой RSS-ответ")
                    result += parseMarketNewsRss(body, feed.sourceName, feed.category, portfolioTickers)
                }
            } catch (error: Exception) {
                errors += "${feed.sourceName}: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        if (result.isEmpty() && errors.isNotEmpty()) {
            throw IOException("Не удалось загрузить новостные ленты: ${errors.joinToString("; ")}")
        }
        return result.distinctBy { it.sourceKey }.map { enrichPlacementFromLinkedPage(it) }
            .sortedByDescending { it.publishedAt }
    }
}
