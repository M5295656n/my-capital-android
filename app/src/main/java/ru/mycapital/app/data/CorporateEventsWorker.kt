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
        setProgress(Data.Builder().putString("summary", "Синхронизация выплат: запускаю проверку портфеля…").build())
        val database = PortfolioDatabase.getInstance(applicationContext)
        val dao = database.portfolioDao()
        val assets = dao.getAssets().filter { it.quantity > 0.0 }
        val equities = assets.filterNot {
            isBondInstrument(it.ticker, it.name, it.assetType)
        }
        // Bond coupon synchronization must still run when the portfolio has no equities.
        var transientFailures = 0
        var importedDividends = 0
        var matchedDividends = 0
        var feedEvents = 0
        val errors = mutableListOf<String>()

        if (equities.isNotEmpty()) {
        try {
            setProgress(Data.Builder().putString("summary", "Синхронизация выплат: загружаю календарь Long/Short…").build())
            val fetchedEvents = LongShortDividendClient().getUpcomingDividends()
            // An unexpectedly empty feed is treated as a source error: preserve the last
            // good cache instead of deleting all dividends because of a temporary outage.
            if (fetchedEvents.isEmpty()) throw java.io.IOException("Источник вернул 0 предстоящих дивидендов")
            val today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow"))
            // Add issuer-confirmed announcements that may not yet be present in the public feed.
            val events = mergeOfficialDividendAnnouncements(fetchedEvents, today)
            feedEvents = events.size
            setProgress(Data.Builder().putString("summary", "Long/Short: получено событий $feedEvents; проверяю совпадения с тикерами портфеля…").build())
            val byTicker = equities.associateBy { normalizeDividendTicker(it.ticker) }
            // Keep provider forecasts for the next two years so the next calendar
            // year's expected dividends (not only the current year's) remain visible.
            val matched = events.filter { event ->
                normalizeDividendTicker(event.ticker) in byTicker &&
                    isWithinDividendForecastHorizon(event, today)
            }
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
                        expectedDate = event.paymentDate ?: event.recordDate,
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
            val message = "Дивиденды: ${error.message ?: error.javaClass.simpleName}"
            errors += message
            setProgress(Data.Builder()
                .putString("summary", "Синхронизация выплат не завершена. $message Попытка ${runAttemptCount + 1} из 3.")
                .putInt("feed_events", feedEvents)
                .putInt("matched_dividends", matchedDividends)
                .build())
        }

        }

        // Bond coupons remain sourced from MOEX ISS; the Long/Short feed is for equities.
        val bondAssets = assets.filter {
            isBondInstrument(it.ticker, it.name, it.assetType)
        }.distinctBy { it.ticker.uppercase() }
        val bondClient = CorporateEventsClient()
        var bondEventsReceived = 0
        var bondPayoutsSaved = 0
        var unsupportedBonds = 0
        for (asset in bondAssets) {
            try {
                // Normalize older imported positions that still carry the generic type "Другое".
                if (!asset.assetType.contains("облигац", ignoreCase = true)) {
                    dao.updateAsset(asset.copy(assetType = "Облигация"))
                }
                val events = bondClient.getEvents(asset.ticker, "Облигация")
                if (events.isEmpty()) {
                    throw java.io.IOException("MOEX ISS не вернул будущие купоны, амортизации или погашение для ${asset.ticker}; проверьте тикер и календарь MOEX.")
                }
                bondEventsReceived += events.size
                database.withTransaction {
                    events.forEach { event ->
                        val existing = dao.getPayoutBySourceKey(event.sourceKey)
                        if (existing == null) {
                            val inserted = dao.insertPayout(
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
                                    status = "ANNOUNCED",
                                    sourceKey = event.sourceKey,
                                    sourceName = "MOEX ISS"
                                )
                            )
                            if (inserted != -1L) bondPayoutsSaved++
                        } else if (existing.status == "EXPECTED" || existing.status == "ANNOUNCED") {
                            dao.updatePayout(existing.copy(
                                assetId = asset.id,
                                amountPerUnit = event.amountPerUnit,
                                quantity = asset.quantity,
                                totalAmount = event.amountPerUnit * asset.quantity,
                                expectedDate = event.eventDate,
                                status = "ANNOUNCED",
                                sourceName = "MOEX ISS"
                            ))
                            bondPayoutsSaved++
                        }
                    }
                }
            } catch (_: UnsupportedCorporateEventsInstrumentException) {
                unsupportedBonds++
            } catch (error: Exception) {
                transientFailures++
                if (errors.size < 8) errors += "Купоны ${asset.ticker}: ${error.message ?: error.javaClass.simpleName}"
            }
        }

        val summary = buildString {
            append("Акции: получено событий $feedEvents, совпадений $matchedDividends, записей $importedDividends. ")
            append("Облигации: найдено ${bondAssets.size}, получено будущих событий выплат (купоны, амортизации, погашения) $bondEventsReceived, записей добавлено/обновлено $bondPayoutsSaved, неподдерживаемых $unsupportedBonds.")
            if (errors.isNotEmpty()) append(" Ошибки: ").append(errors.joinToString("; "))
        }
        val output = Data.Builder()
            .putString("summary", summary)
            .putInt("feed_events", feedEvents)
            .putInt("matched_dividends", matchedDividends)
            .putInt("imported_dividends", importedDividends)
            .putInt("bond_assets", bondAssets.size)
            .putInt("bond_events", bondEventsReceived)
            .putInt("bond_payouts_saved", bondPayoutsSaved)
            .build()

        setProgress(Data.Builder()
            .putString("summary", summary)
            .putInt("feed_events", feedEvents)
            .putInt("matched_dividends", matchedDividends)
            .putInt("imported_dividends", importedDividends)
            .putInt("bond_assets", bondAssets.size)
            .putInt("bond_events", bondEventsReceived)
            .putInt("bond_payouts_saved", bondPayoutsSaved)
            .build())
        if (transientFailures > 0 && runAttemptCount < 2) Result.retry()
        else if (transientFailures > 0) Result.failure(output)
        else Result.success(output)
    }
}
