package ru.mycapital.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.room.withTransaction
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

        val client = CorporateEventsClient()
        var succeeded = 0
        var attempted = 0
        for (asset in assets.distinctBy { it.ticker.uppercase() }) {
            attempted++
            try {
                val events = client.getEvents(asset.ticker, asset.assetType)
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
                                    sourceKey = event.sourceKey
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
                succeeded++
            } catch (_: Exception) {
                // Continue syncing other holdings if one instrument is unsupported.
            }
        }
        if (succeeded == 0 && attempted > 0 && runAttemptCount < 3) Result.retry()
        else Result.success()
    }
}
