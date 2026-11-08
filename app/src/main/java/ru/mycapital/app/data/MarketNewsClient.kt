package ru.mycapital.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.time.Instant
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
    val title: String,
    val summary: String,
    val sourceName: String,
    val sourceUrl: String,
    val relatedTickers: List<String>
)

internal val defaultMarketNewsFeeds = listOf(
    MarketNewsFeed("https://www.cbr.ru/rss/RssPress", "Банк России", "ECONOMY"),
    MarketNewsFeed("https://www.cbr.ru/rss/eventrss", "Банк России", "ECONOMY"),
    MarketNewsFeed("https://moex.com/export/news.aspx?cat=100", "Московская биржа", "COMPANIES")
)

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
        ParsedMarketNews(
            sourceKey = key,
            category = classifyMarketNews(title, description, category),
            publishedAt = publishedAt,
            title = title,
            summary = description.take(1200),
            sourceName = sourceName,
            sourceUrl = url,
            relatedTickers = matchPortfolioTickers("$title $description", portfolioTickers)
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
        return result.distinctBy { it.sourceKey }.sortedByDescending { it.publishedAt }
    }
}
