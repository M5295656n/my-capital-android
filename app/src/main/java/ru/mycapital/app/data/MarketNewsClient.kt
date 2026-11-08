package ru.mycapital.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.io.ByteArrayInputStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
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
    val placementStartDate: String? = null,
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
    val placementStartDate: String? = null,
    val tradingStartDate: String? = null,
    val isin: String? = null,
    val couponAmount: String? = null
)

internal fun parsePlacementDetails(title: String, summary: String): ParsedPlacementDetails {
    val text = "$title $summary".replace(Regex("\\s+"), " ")
    fun capture(pattern: Regex): String? =
        pattern.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

    val issuer = capture(Regex(
        "(?:полное\\s+наименование\\s+эмитента|наименование\\s+эмитента|эмитент|компания)\\s*[:—-]\\s*([^.;,]{2,120})",
        RegexOption.IGNORE_CASE
    ))
    val instrumentType = when {
        Regex("\\bIPO\\b|первичн\\w* размещени\\w* акци", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Акции (IPO)"
        Regex("\\bSPO\\b|дополнительн\\w* эмисс", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Акции (SPO / допэмиссия)"
        Regex("облигац", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Облигации"
        Regex("акци", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Акции"
        else -> null
    }
    val amount = "[0-9][0-9\\s.,]*(?:млн|млрд|тыс)?\\s*(?:₽|руб(?:лей|ля)?|штук|шт\\.?|акций|облигаций)?"
    val volume = capture(Regex(
        "(?:об[ъь]?[её]м(?:\\s+(?:эмиссии|выпуска|размещения))?|общий\\s+об[ъь]?[её]м\\s+размещения|номинальный\\s+об[ъь]?[её]м|количество\\s+размещаемых\\s+(?:ценных\\s+бумаг|акций|облигаций)|количество\\s+(?:облигаций|акций))\\s*[:—-]?\\s*($amount)",
        RegexOption.IGNORE_CASE
    ))
    val price = capture(Regex(
        "(?:цена\\s+размещения(?:\\s+(?:одной\\s+)?(?:облигации|акции))?|цена\\s+предложения|ценовой\\s+диапазон|цена)\\s*[:—-]?\\s*([0-9][0-9\\s.,]*(?:[-–—][0-9][0-9\\s.,]*)?\\s*(?:₽|руб(?:лей|ля)?|%\\s*от\\s*номинала))",
        RegexOption.IGNORE_CASE
    ))
    val coupon = capture(Regex(
        "(?:купонная\\s+ставка|ставка\\s+купона|процентная\\s+ставка\\s+по\\s+купону|размер\\s+процентной\\s+ставки)\\s*[:—-]?\\s*([0-9]+(?:[.,][0-9]+)?\\s*%)",
        RegexOption.IGNORE_CASE
    ))
    val couponAmount = capture(Regex(
        "(?:размер\\s+купона|размер\\s+купонного\\s+дохода|купонный\\s+доход|сумма\\s+купона|сумма\\s+выплаты\\s+на\\s+одну\\s+облигацию|денежный\\s+размер\\s+купона|купон)\\s*[:—-]?\\s*([0-9][0-9\\s.,]*\\s*(?:₽|руб(?:лей|ля)?))",
        RegexOption.IGNORE_CASE
    ))
    val isin = Regex("\\b[A-Z]{2}[A-Z0-9]{10}\\b").find(text)?.value
    val explicitDate = "(?:\\d{1,2}[. ]\\d{1,2}(?:[. ]\\d{4})?|\\d{1,2}\\s+[а-яё]+(?:\\s+\\d{4})?)"
    val applicationDates = capture(Regex(
        "((?:дата\\s+сбора\\s+заявок|период\\s+букбилдинга|сбор\\s+заявок|при[её]м\\s+заявок|книга\\s+заявок)[^.;]{0,120}?$explicitDate)",
        RegexOption.IGNORE_CASE
    ))
    val placementStartDate = capture(Regex(
        "((?:дата\\s+начала\\s+размещения|размещение\\s+с)[^.;]{0,120}?$explicitDate)",
        RegexOption.IGNORE_CASE
    ))
    val tradingDate = capture(Regex(
        "(?:дата\\s+начала\\s+торгов|начало\\s+торгов|торги\\s+начнутся|допуск\\s+к\\s+торгам)\\s*[:—-]?\\s*($explicitDate)",
        RegexOption.IGNORE_CASE
    ))
    return ParsedPlacementDetails(issuer, instrumentType, volume, price, coupon, applicationDates, placementStartDate, tradingDate, isin, couponAmount)
}

internal fun parseExplicitPlacementEventDate(
    details: ParsedPlacementDetails,
    zone: ZoneId = ZoneId.of("Europe/Moscow")
): Long? {
    val candidates = listOfNotNull(details.applicationDates, details.placementStartDate, details.tradingStartDate)
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

internal fun extractPlacementTextFromPdf(bytes: ByteArray): String? {
    if (bytes.size < 5 || !bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) return null
    return runCatching {
        PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
            if (document.isEncrypted || document.numberOfPages <= 0) return@use ""
            val stripper = PDFTextStripper().apply {
                startPage = 1
                endPage = minOf(document.numberOfPages, 80)
            }
            stripper.getText(document).replace(Regex("\\s+"), " ").take(16000)
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

internal fun parsePlacementDetailsFromHtml(html: String, title: String): ParsedPlacementDetails {
    val document = Jsoup.parse(html)
    val labeledValues = mutableListOf<String>()
    val labelRules = listOf(
        Regex("эмитент|наименование эмитента|полное наименование компании|компания", RegexOption.IGNORE_CASE) to "Эмитент",
        Regex("об[ъь]?[её]м выпуска|об[ъь]?[её]м размещения|об[ъь]?[её]м эмиссии|общий об[ъь]?[её]м размещения|номинальный об[ъь]?[её]м|количество размещаемых|количество облигаций|количество акций", RegexOption.IGNORE_CASE) to "Объём выпуска",
        Regex("цена размещения|цена предложения|ценовой диапазон|цена одной облигации|цена одной акции", RegexOption.IGNORE_CASE) to "Цена размещения",
        Regex("купонная ставка|ставка купона|процентная ставка по купону|размер процентной ставки", RegexOption.IGNORE_CASE) to "Ставка купона",
        Regex("размер купона|размер купонного дохода|сумма купона|сумма выплаты на одну облигацию|денежный размер купона|купонный доход|величина купона", RegexOption.IGNORE_CASE) to "Размер купона",
        Regex("\\bisin\\b", RegexOption.IGNORE_CASE) to "ISIN",
        Regex("дата сбора заявок|период букбилдинга|сбор заявок|при[её]м заявок|книга заявок", RegexOption.IGNORE_CASE) to "Сбор заявок",
        Regex("дата начала размещения|размещение с", RegexOption.IGNORE_CASE) to "Начало размещения",
        Regex("дата начала торгов|начало торгов|дата допуска к торгам|допуск к торгам", RegexOption.IGNORE_CASE) to "Начало торгов"
    )
    fun canonicalLabel(label: String): String? {
        val clean = label.replace(Regex("\\s+"), " ").trim().trimEnd(':', '：')
        return labelRules.firstOrNull { it.first.containsMatchIn(clean) }?.second
    }
    fun addPair(label: String, value: String) {
        val canonical = canonicalLabel(label) ?: return
        val cleanValue = value.replace(Regex("\\s+"), " ").trim()
        if (cleanValue.isBlank() || cleanValue in setOf("—", "-", "не сообщено")) return
        labeledValues += "$canonical: $cleanValue"
    }

    // A row is interpreted only when a cell itself is a known parameter label.
    // This avoids treating a previous value as the label for the next cell.
    document.select("table tr").forEach { row ->
        val cells = row.children().filter { it.tagName() == "th" || it.tagName() == "td" }
        cells.forEachIndexed { index, cell ->
            if (canonicalLabel(cell.text()) != null) {
                val valueCell = cells.getOrNull(index + 1)
                if (valueCell != null) addPair(cell.text(), valueCell.text())
            }
        }
    }

    document.select("dl").forEach { list ->
        list.children().filter { it.tagName() == "dt" }.forEach { term ->
            val value = term.nextElementSibling()?.takeIf { it.tagName() == "dd" }?.text().orEmpty()
            addPair(term.text(), value)
        }
    }
    return parsePlacementDetails(title, labeledValues.distinct().joinToString(". "))
}

internal fun mergePlacementDetails(primary: ParsedPlacementDetails, secondary: ParsedPlacementDetails?): ParsedPlacementDetails {
    if (secondary == null) return primary
    return ParsedPlacementDetails(
        issuer = primary.issuer ?: secondary.issuer,
        instrumentType = primary.instrumentType ?: secondary.instrumentType,
        placementVolume = primary.placementVolume ?: secondary.placementVolume,
        placementPrice = primary.placementPrice ?: secondary.placementPrice,
        couponRate = primary.couponRate ?: secondary.couponRate,
        applicationDates = primary.applicationDates ?: secondary.applicationDates,
        placementStartDate = primary.placementStartDate ?: secondary.placementStartDate,
        tradingStartDate = primary.tradingStartDate ?: secondary.tradingStartDate,
        isin = primary.isin ?: secondary.isin,
        couponAmount = primary.couponAmount ?: secondary.couponAmount
    )
}

internal fun findOfficialPlacementDocumentUrl(html: String, baseUrl: String): String? =
    findOfficialPlacementDocumentUrls(html, baseUrl).firstOrNull()

internal fun findOfficialPlacementDocumentUrls(html: String, baseUrl: String): List<String> {
    val document = Jsoup.parse(html, baseUrl)
    val relevantLinkText = Regex(
        "проспект|решение о выпуске|документарн\\w*|раскрыти\\w* информаци|услови\\w* размещени|эмиссионн\\w* документ|документы выпуска|параметры выпуска|дополнительные материалы|скачать документ",
        RegexOption.IGNORE_CASE
    )
    return document.select("a[href]").mapNotNull { anchor ->
        val href = anchor.absUrl("href")
        val url = runCatching { okhttp3.HttpUrl.get(href) }.getOrNull() ?: return@mapNotNull null
        val hostAllowed = url.host == "e-disclosure.ru" || url.host.endsWith(".e-disclosure.ru")
        val pathLooksDocument = url.encodedPath.endsWith(".pdf", ignoreCase = true)
        if (url.scheme == "https" && hostAllowed &&
            (relevantLinkText.containsMatchIn(anchor.text()) || pathLooksDocument)
        ) url.toString() else null
    }.distinct().take(5)
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
            placementStartDate = placement.placementStartDate,
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

    private fun fetchOfficialPlacementDocument(url: String, title: String, allowNestedDocument: Boolean = true): ParsedPlacementDetails? {
        val parsed = runCatching { okhttp3.HttpUrl.get(url) }.getOrNull() ?: return null
        if (parsed.scheme != "https" || !(parsed.host == "e-disclosure.ru" || parsed.host.endsWith(".e-disclosure.ru"))) return null
        return runCatching {
            val request = Request.Builder().url(parsed).header("User-Agent", "MyCapital-Android/0.3").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val host = response.request.url.host
                if (response.request.url.scheme != "https" || !(host == "e-disclosure.ru" || host.endsWith(".e-disclosure.ru"))) return@use null
                val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                val isPdfContentType = contentType.startsWith("application/pdf")
                if (!isPdfContentType && !contentType.startsWith("text/html") && !contentType.startsWith("text/plain") && !contentType.startsWith("application/octet-stream")) return@use null
                val body = response.body ?: return@use null
                val maxBytes = if (isPdfContentType || contentType.startsWith("application/octet-stream")) 4L * 1024 * 1024 else 512L * 1024
                if (body.contentLength() > maxBytes) return@use null
                val bytes = body.source().readByteArray(maxBytes + 1L)
                if (bytes.size.toLong() > maxBytes) return@use null
                val hasPdfSignature = bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))
                val isPdf = isPdfContentType || hasPdfSignature
                if (contentType.startsWith("application/octet-stream") && !hasPdfSignature) return@use null
                if (isPdf && !hasPdfSignature) return@use null
                if (isPdf) {
                    val pdfText = extractPlacementTextFromPdf(bytes) ?: return@use null
                    parsePlacementDetails(title, pdfText)
                } else {
                    val html = String(bytes, Charsets.UTF_8)
                    val document = Jsoup.parse(html, url)
                    val text = document.text().replace(Regex("\\s+"), " ").take(16000)
                    val pageDetails = mergePlacementDetails(
                        parsePlacementDetails(title, text),
                        parsePlacementDetailsFromHtml(html, title)
                    )
                    if (allowNestedDocument) {
                        val nestedUrl = findOfficialPlacementDocumentUrls(html, url)
                            .firstOrNull { it != url }
                        val nestedDetails = nestedUrl?.let { fetchOfficialPlacementDocument(it, title, false) }
                        mergePlacementDetails(pageDetails, nestedDetails)
                    } else pageDetails
                }
            }
        }.getOrNull()
    }

    private fun enrichPlacementFromLinkedPage(item: ParsedMarketNews): ParsedMarketNews {
        if (item.category != "NEW_ISSUES") return item
        val parsedUrl = runCatching { okhttp3.HttpUrl.get(item.sourceUrl) }.getOrNull() ?: return item
        // Follow only the MOEX article URL supplied by the official MOEX RSS feed.
        if (parsedUrl.scheme != "https" || (parsedUrl.host != "moex.com" && !parsedUrl.host.endsWith(".moex.com"))) return item
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
                val pageHtml = String(bytes, Charsets.UTF_8)
                val pageDocument = Jsoup.parse(pageHtml, item.sourceUrl)
                val pageText = pageDocument.text().replace(Regex("\\s+"), " ").take(12000)
                val pageDetails = mergePlacementDetails(
                    parsePlacementDetails(item.title, pageText),
                    parsePlacementDetailsFromHtml(pageHtml, item.title)
                )
                val documentUrl = findOfficialPlacementDocumentUrl(pageHtml, item.sourceUrl)
                val documentDetails = documentUrl?.let { fetchOfficialPlacementDocument(it, item.title) }
                val bestDetails = mergePlacementDetails(pageDetails, documentDetails)
                item.copy(
                    issuer = item.issuer ?: bestDetails.issuer,
                    instrumentType = item.instrumentType ?: bestDetails.instrumentType,
                    placementVolume = item.placementVolume ?: bestDetails.placementVolume,
                    placementPrice = item.placementPrice ?: bestDetails.placementPrice,
                    couponRate = item.couponRate ?: bestDetails.couponRate,
                    applicationDates = item.applicationDates ?: bestDetails.applicationDates,
                    placementStartDate = item.placementStartDate ?: bestDetails.placementStartDate,
                    tradingStartDate = item.tradingStartDate ?: bestDetails.tradingStartDate,
                    eventDate = parseExplicitPlacementEventDate(bestDetails) ?: item.eventDate,
                    isin = item.isin ?: bestDetails.isin,
                    couponAmount = item.couponAmount ?: bestDetails.couponAmount,
                    detailsSourceUrl = documentUrl ?: item.sourceUrl
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
