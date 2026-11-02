package ru.mycapital.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class PortfolioSnapshotWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val dao = PortfolioDatabase.getInstance(applicationContext).portfolioDao()
            dao.ensureCashBalance(CashBalanceEntity())
            val now = System.currentTimeMillis()
            val dateKey = portfolioSnapshotDateKey(now)
            val previous = dao.getLatestPortfolioSnapshotBefore(dateKey)
            val assetsValue = dao.getAssets().sumOf { it.quantity * it.currentPrice }
            val cashValue = dao.getCashBalance() ?: 0.0
            val externalFlow = previous?.let { last ->
                calculateExternalFlow(dao.getExternalFlowsSince(last.capturedAt))
            } ?: 0.0
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
