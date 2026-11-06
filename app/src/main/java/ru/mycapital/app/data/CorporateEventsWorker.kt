package ru.mycapital.app.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun normalizeDividendTicker(value: String): String = value.trim().uppercase(java.util.Locale.ROOT)

class CorporateEventsWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = PortfolioDatabase.getInstance(applicationContext)
        val dao = database.portfolioDao()
        val assets = dao.getAssets().filter { it.quantity > 0.0 }
        val equities = assets.filterNot {
            it.assetType.contains("облиг", ignoreCase = true) ||
                it.assetType.contains("офз", ignoreCase = true) ||
                it.assetType.contains("bond", ignoreCase = true)
        }
        if (equities.isEmpty()) {
            return@withContext Result.success(
                Data.Builder().putString("summary", "Обновление дивидендов: в портфеле пока нет акций с положительным количеством.").build()
            )
        }

        var transientFailures = 0
        var importedDividends = 0
        var matchedDividends = 0
        var feedEvents = 0
        val errors = mutableListOf<String>()

        try {
            val events = LongShortDividendClient().getUpcomingDividends()
            // An unexpectedly empty feed is treated as a source error: preserve the last
            // good cache instead of deleting all dividends because of a temporary outage.
            if (events.isEmpty()) throw java.io.IOException("Источник вернул 0 предстоящих дивидендов")
            feedEvents = events.size
            val byTicker = equities.associateBy { normalizeDividendTicker(it.ticker) }
            val matched = events.filter { normalizeDividendTicker(it.ticker) in byTicker }
            matchedDividends = matched.size
            // Never erase the cache when the feed is valid but does not contain any
            // portfolio ticker: this can happen after a provider schema/ticker change.
            if (matched.isEmpty()) {
                val portfolioTickers = equities.joinToString(", ") { it.ticker }
                throw java.io.IOException(
                    "Выгрузка содержит $feedEvents событий, но ни одно не совпало с тикерами портфеля ($portfolioTickers). Старые данные сохранены."
                )
            }
            database.withTransaction {
                // Replace only the tickers for which the provider returned matching
                // events. A partial feed must not wipe forecasts for other holdings.
                val refreshedTickers = matched.map { normalizeDividendTicker(it.ticker) }.toSet()
                refreshedTickers.forEach { ticker ->
                    dao.deleteUnreceivedLongShortDividendEventsForTicker(ticker)
                }
                matched.forEach { event ->
                    val ticker = normalizeDividendTicker(event.ticker)
                    val asset = byTicker[ticker] ?: return@forEach
                    val payout = PayoutEntity(
                        assetId = asset.id,
                        ticker = asset.ticker,
                        payoutType = "Дивиденд",
                        amountPerUnit = event.amountPerUnit,
                        quantity = asset.quantity,
                        totalAmount = event.amountPerUnit * asset.quantity,
                        taxAmount = 0.0,
                        expectedDate = event.paymentDate,
                        actualDate = null,
                        status = event.status,
                        sourceKey = event.sourceKey,
                        recordDate = event.recordDate,
                        sourceName = "Long/Short"
                    )
                    val existing = dao.getPayoutBySourceKey(event.sourceKey)
                    if (existing == null) {
                        if (dao.insertPayout(payout) != -1L) importedDividends++
                    } else if (existing.status != "RECEIVED") {
                        dao.updatePayout(payout.copy(id = existing.id))
                        importedDividends++
                    }
                }
            }
        } catch (error: Exception) {
            transientFailures++
            errors += "Дивиденды: ${error.message ?: error.javaClass.simpleName}"
        }

        // Bond coupons remain sourced from MOEX ISS; the Long/Short feed is for equities.
        val bondClient = CorporateEventsClient()
        for (asset in assets.filter {
            it.assetType.contains("облигац", ignoreCase = true) ||
                it.assetType.contains("офз", ignoreCase = true) ||
                it.assetType.contains("bond", ignoreCase = true)
        }.distinctBy { it.ticker.uppercase() }) {
            try {
                val events = bondClient.getEvents(asset.ticker, asset.assetType)
                database.withTransaction {
                    events.forEach { event ->
                        val existing = dao.getPayoutBySourceKey(event.sourceKey)
                        if (existing == null) {
                            dao.insertPayout(
                                PayoutEntity(
                                    assetId = asset.id,
                                    ticker = event.ticker,
                                    payoutType = event.type,
                                    amountPerUnit = event.amountPerUnit,
                                    quantity = asset.quantity,
                                    totalAmount = event.amountPerUnit * asset.quantity,
                                    taxAmount = 0.0,
                                    expectedDate = event.eventDate,
                                    actualDate = null,
                                    status = "EXPECTED",
                                    sourceKey = event.sourceKey,
                                    sourceName = "MOEX ISS"
                                )
                            )
                        } else if (existing.status == "EXPECTED") {
                            dao.updatePayout(
                                existing.copy(
                                    assetId = asset.id,
                                    amountPerUnit = event.amountPerUnit,
                                    quantity = asset.quantity,
                                    totalAmount = event.amountPerUnit * asset.quantity,
                                    expectedDate = event.eventDate
                                )
                            )
                        }
                    }
                }
            } catch (_: UnsupportedCorporateEventsInstrumentException) {
                // Unsupported instruments are permanent skips, not retryable failures.
            } catch (error: Exception) {
                transientFailures++
                if (errors.size < 5) errors += "${asset.ticker}: ${error.message ?: error.javaClass.simpleName}"
            }
        }

        val summary = buildString {
            append("Дивидендный источник: событий получено $feedEvents; совпадений с акциями портфеля $matchedDividends; добавлено или обновлено записей $importedDividends.")
            if (errors.isNotEmpty()) append(" Ошибка: ").append(errors.joinToString("; "))
        }
        val output = Data.Builder()
            .putString("summary", summary)
            .putInt("feed_events", feedEvents)
            .putInt("matched_dividends", matchedDividends)
            .putInt("imported_dividends", importedDividends)
            .build()

        if (transientFailures > 0 && runAttemptCount < 3) Result.retry()
        else if (transientFailures > 0) Result.failure(output)
        else Result.success(output)
    }
}
