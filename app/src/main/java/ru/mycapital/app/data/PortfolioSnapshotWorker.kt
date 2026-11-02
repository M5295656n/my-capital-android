package ru.mycapital.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PortfolioSnapshotWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val dao = PortfolioDatabase.getInstance(applicationContext).portfolioDao()
            dao.ensureCashBalance(CashBalanceEntity())
            val previous = dao.getLatestPortfolioSnapshot()
            val assetsValue = dao.getAssets().sumOf { it.quantity * it.currentPrice }
            val cashValue = dao.getCashBalance() ?: 0.0
            val now = System.currentTimeMillis()
            val externalFlow = previous?.let { last ->
                dao.getExternalFlowsSince(last.capturedAt).sumOf { op ->
                    if (op.operationType == "Пополнение") op.price else -op.price
                }
            } ?: 0.0
            val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
            dao.insertPortfolioSnapshot(
                PortfolioSnapshotEntity(
                    dateKey = dateKey,
                    capturedAt = now,
                    assetsValue = assetsValue,
                    cashValue = cashValue,
                    totalValue = assetsValue + cashValue,
                    externalFlow = externalFlow
                )
            )
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
