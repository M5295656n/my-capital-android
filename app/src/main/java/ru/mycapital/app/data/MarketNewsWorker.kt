package ru.mycapital.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MarketNewsWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(applicationContext)
        val dao = PortfolioDatabase.getInstance(applicationContext).portfolioDao()
        val tickers = dao.getAssets().filter { it.quantity > 0.0 }
            .map { it.ticker.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        setProgress(Data.Builder().putString("summary", "Загружаю новости Банка России и Московской биржи…").build())
        try {
            val client = MarketNewsClient()
            val keyRateEvents = runCatching { client.getUpcomingKeyRateEvents() }
                .getOrElse { error ->
                    setProgress(Data.Builder().putString("summary", "Не удалось загрузить календарь Банка России: ${error.message}").build())
                    emptyList()
                }
            val statisticsEvents = runCatching { client.getUpcomingStatisticsEvents() }
                .getOrElse { error ->
                    setProgress(Data.Builder().putString("summary", "Не удалось загрузить календарь статистики ЦБ: ${error.message}").build())
                    emptyList()
                }
            val news = runCatching { client.getLatestNews(tickers) }
                .getOrElse { error ->
                    setProgress(Data.Builder().putString("summary", "Не удалось загрузить RSS-новости: ${error.message}").build())
                    emptyList()
                }
            if (news.isEmpty() && keyRateEvents.isEmpty() && statisticsEvents.isEmpty()) {
                throw java.io.IOException("Источники не вернули новостей и будущих событий")
            }
            val now = System.currentTimeMillis()
            val newsEntities = news.map { item ->
                MarketCalendarItemEntity(
                    sourceKey = item.sourceKey,
                    itemType = "NEWS",
                    category = item.category,
                    eventDate = item.eventDate,
                    publishedAt = item.publishedAt,
                    title = item.title,
                    summary = item.summary,
                    sourceName = item.sourceName,
                    sourceUrl = item.sourceUrl,
                    relatedTickers = item.relatedTickers.joinToString(","),
                    importance = "NORMAL",
                    impactAssessment = "",
                    impactHorizon = "",
                    confidence = "LOW",
                    updatedAt = now,
                    issuer = item.issuer,
                    instrumentType = item.instrumentType,
                    placementVolume = item.placementVolume,
                    placementPrice = item.placementPrice,
                    couponRate = item.couponRate,
                    applicationDates = item.applicationDates,
                    placementStartDate = item.placementStartDate,
                    tradingStartDate = item.tradingStartDate,
                    isin = item.isin,
                    couponAmount = item.couponAmount,
                    detailsSourceUrl = item.detailsSourceUrl
                )
            }
            val eventEntities = keyRateEvents.map { item ->
                MarketCalendarItemEntity(
                    sourceKey = item.sourceKey,
                    itemType = "EVENT",
                    category = item.category,
                    eventDate = item.eventDate,
                    publishedAt = null,
                    title = item.title,
                    summary = item.summary,
                    sourceName = item.sourceName,
                    sourceUrl = item.sourceUrl,
                    relatedTickers = "",
                    importance = "HIGH",
                    impactAssessment = "",
                    impactHorizon = "EVENT",
                    confidence = "HIGH",
                    updatedAt = now
                )
            }
            val statisticsEntities = statisticsEvents.map { item ->
                MarketCalendarItemEntity(
                    sourceKey = item.sourceKey,
                    itemType = "EVENT",
                    category = item.category,
                    eventDate = item.eventDate,
                    publishedAt = null,
                    title = item.title,
                    summary = item.summary,
                    sourceName = item.sourceName,
                    sourceUrl = item.sourceUrl,
                    relatedTickers = "",
                    importance = "NORMAL",
                    impactAssessment = "",
                    impactHorizon = "PUBLICATION",
                    confidence = "HIGH",
                    updatedAt = now
                )
            }
            val entities = newsEntities + eventEntities + statisticsEntities
            dao.upsertMarketCalendarItems(entities)
            // Keep 180 days of news and preserve upcoming scheduled events.
            dao.deleteStaleMarketCalendarItems(now - 180L * 24 * 60 * 60 * 1000)
            val summary = "Загружено новостей: " + newsEntities.size +
                "; будущих заседаний ЦБ: " + eventEntities.size +
                "; статистических публикаций ЦБ: " + statisticsEntities.size +
                "; связанных с бумагами портфеля: " + newsEntities.count { it.relatedTickers.isNotBlank() } + "."
            setProgress(Data.Builder().putString("summary", summary).build())
            Result.success(Data.Builder().putString("summary", summary).build())
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            setProgress(Data.Builder().putString("summary", "Не удалось обновить новости: $message").build())
            if (runAttemptCount < 2) Result.retry()
            else Result.failure(Data.Builder().putString("summary", message).build())
        }
    }
}
