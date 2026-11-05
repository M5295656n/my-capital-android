package ru.mycapital.app.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CorporateEventsWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = PortfolioDatabase.getInstance(applicationContext)
        val dao = database.portfolioDao()
        val assets = dao.getAssets().filter { it.quantity > 0.0 }
        if (assets.isEmpty()) return@withContext Result.success()

        var transientFailures = 0
        val equities = assets.filter {
            it.assetType.contains("акц", ignoreCase = true) ||
                it.assetType.contains("stock", ignoreCase = true)
        }
        if (equities.isNotEmpty()) {
            try {
                // One public daily CSV contains confirmed, board-recommended and forecast dividends.
                // Keep the previous good cache if the feed cannot be fetched or parsed.
                val events = LongShortDividendClient().getUpcomingDividends()
                val byTicker = equities.associateBy { it.ticker.uppercase() }
                database.withTransaction {
                    dao.deleteUnreceivedLongShortDividendEvents()
                    events.forEach { event ->
                        val asset = byTicker[event.ticker] ?: return@forEach
                        dao.insertPayout(
                            PayoutEntity(
                                assetId = asset.id,
                                ticker = event.ticker,
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
                        )
                    }
                }
            } catch (_: Exception) {
                transientFailures++
            }
        }

        // Bond coupons remain sourced from MOEX ISS; the Long/Short feed is for equities.
        val bondClient = CorporateEventsClient()
        for (asset in assets.filter {
            it.assetType.contains("облигац", ignoreCase = true) ||
                it.assetType.contains("офз", ignoreCase = true)
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
            } catch (_: Exception) {
                transientFailures++
            }
        }
        if (transientFailures > 0 && runAttemptCount < 3) Result.retry()
        else Result.success()
    }
}
