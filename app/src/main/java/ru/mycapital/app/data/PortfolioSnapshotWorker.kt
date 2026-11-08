package ru.mycapital.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.mycapital.app.buildPortfolioCompositionSnapshots

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
            val assets = dao.getAssets()
            val assetsValue = assets.sumOf { it.quantity * it.currentPrice }
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
            val compositionSnapshots = mutableListOf<PortfolioCompositionSnapshotEntity>()
            compositionSnapshots += buildPortfolioCompositionSnapshots(dateKey, "ALL", assets, cashValue)
            val accountPositions = dao.getAllBrokerAccountAssets()
            val accountCashRows = dao.getBrokerAccountCashAccounts()
            val assetsByTicker = assets.associateBy { it.ticker.trim().uppercase() }
            val accountIds = (accountPositions.map { it.accountId } + accountCashRows.map { it.accountId }).distinct()
            accountIds.forEach { accountId ->
                val accountAssets = accountPositions.asSequence()
                    .filter { it.accountId == accountId && it.quantity > 0.0 }
                    .mapNotNull { position ->
                        assetsByTicker[position.ticker.trim().uppercase()]?.copy(quantity = position.quantity)
                    }
                    .toList()
                val accountCashValue = accountCashRows.firstOrNull { it.accountId == accountId }?.balance ?: 0.0
                compositionSnapshots += buildPortfolioCompositionSnapshots(dateKey, accountId, accountAssets, accountCashValue)
            }
            if (compositionSnapshots.isNotEmpty()) {
                dao.upsertPortfolioCompositionSnapshots(compositionSnapshots)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
