package ru.mycapital.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class QuoteRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dao = PortfolioDatabase.getInstance(applicationContext).portfolioDao()
        val assets = dao.getAssets()
        if (assets.isEmpty()) return@withContext Result.success()

        val client = MoexIssClient()
        var updated = 0
        var attempted = 0
        var failed = 0
        for (asset in assets) {
            if (asset.ticker.isBlank()) continue
            attempted++
            try {
                val quote = client.getQuote(asset.ticker, asset.assetType)
                if (quote != null) {
                    dao.updateAssetPrice(asset.id, quote.price, System.currentTimeMillis())
                    updated++
                }
                // Missing market data is not necessarily a transport failure.
            } catch (_: UnsupportedMoexInstrumentException) {
                // Permanent instrument errors are skipped without retrying the whole batch.
            } catch (_: Exception) {
                // Retry only when an actual request or response processing failed.
                failed++
            }
        }
        if (failed > 0 && attempted > 0 && runAttemptCount < 3) Result.retry()
        else Result.success()
    }
}
