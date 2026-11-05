package ru.mycapital.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
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
        if (assets.isEmpty()) {
            return@withContext Result.success(Data.Builder().putString("summary", "В портфеле нет активов для обновления.").build())
        }

        val client = MoexIssClient()
        var updated = 0
        var attempted = 0
        var failed = 0
        var unavailable = 0
        var unsupported = 0
        val errors = mutableListOf<String>()

        for (asset in assets) {
            if (asset.ticker.isBlank()) continue
            attempted++
            try {
                val quote = client.getQuote(asset.ticker, asset.assetType)
                if (quote != null) {
                    dao.updateAssetPrice(asset.id, quote.price, System.currentTimeMillis())
                    updated++
                } else {
                    unavailable++
                    if (errors.size < 5) errors += "${asset.ticker}: MOEX ISS не вернул котировку на выбранной торговой доске."
                }
            } catch (error: UnsupportedMoexInstrumentException) {
                unsupported++
                if (errors.size < 5) errors += "${asset.ticker}: ${error.message ?: "инструмент не поддерживается"}"
            } catch (error: Exception) {
                failed++
                if (errors.size < 5) errors += "${asset.ticker}: ${error.message ?: error.javaClass.simpleName}"
            }
        }

        if (failed > 0 && runAttemptCount < 2) {
            Result.retry()
        } else {
            val summary = buildString {
                append("Обновлено: $updated из $attempted.")
                if (unavailable > 0) append(" Без котировки: $unavailable.")
                if (unsupported > 0) append(" Не поддерживается: $unsupported.")
                if (failed > 0) append(" Ошибок: $failed.")
                if (errors.isNotEmpty()) append("\n").append(errors.joinToString("\n"))
            }
            Result.success(
                Data.Builder()
                    .putString("summary", summary)
                    .putInt("updated", updated)
                    .putInt("attempted", attempted)
                    .putInt("failed", failed)
                    .build()
            )
        }
    }
}
