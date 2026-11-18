package ru.mycapital.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.QuoteRefreshWorker
import ru.mycapital.app.data.CorporateEventsWorker
import ru.mycapital.app.data.MarketNewsWorker
import ru.mycapital.app.data.PortfolioSnapshotWorker
import java.util.concurrent.TimeUnit

internal data class MarketRefreshState(
    val isRefreshing: Boolean,
    val newsSyncMessage: String?,
    val eventSyncMessage: String,
    val refreshQuotes: () -> Unit,
    val refreshEvents: () -> Unit,
    val refreshNews: () -> Unit
) {
    val refreshAll: () -> Unit = {
        refreshQuotes()
        refreshEvents()
    }
}

@Composable
internal fun rememberMarketRefreshState(assets: List<AssetEntity>): MarketRefreshState {
    val context = LocalContext.current
    val quoteWorkInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow("moex_quote_refresh_manual")
        .collectAsState(initial = emptyList())
    val eventWorkInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow("moex_events_refresh_manual")
        .collectAsState(initial = emptyList())
    val newsWorkInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow("market_news_refresh_manual")
        .collectAsState(initial = emptyList())
    // Show activity only while a worker is actually executing. ENQUEUED/BLOCKED can
    // persist while Android waits for network constraints and must not look like a stuck spinner.
    val isRefreshingMarketData = quoteWorkInfos.any { it.state == androidx.work.WorkInfo.State.RUNNING } ||
        eventWorkInfos.any { it.state == androidx.work.WorkInfo.State.RUNNING }

    LaunchedEffect(Unit) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequestBuilder<QuoteRefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "moex_quote_refresh", ExistingPeriodicWorkPolicy.UPDATE, periodic
        )
        val eventsPeriodic = PeriodicWorkRequestBuilder<CorporateEventsWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "moex_corporate_events", ExistingPeriodicWorkPolicy.UPDATE, eventsPeriodic
        )
        val newsPeriodic = PeriodicWorkRequestBuilder<MarketNewsWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "market_news_refresh", ExistingPeriodicWorkPolicy.UPDATE, newsPeriodic
        )
        val newsRequest = OneTimeWorkRequestBuilder<MarketNewsWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "market_news_refresh_manual", ExistingWorkPolicy.KEEP, newsRequest
        )
        val snapshotPeriodic = PeriodicWorkRequestBuilder<PortfolioSnapshotWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "portfolio_daily_snapshots", ExistingPeriodicWorkPolicy.UPDATE, snapshotPeriodic
        )
        val todayKey = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow")).toString()
        val todaySnapshot = OneTimeWorkRequestBuilder<PortfolioSnapshotWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "portfolio_snapshot_$todayKey", ExistingWorkPolicy.KEEP, todaySnapshot
        )
    }

    val refreshQuotes: () -> Unit = {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<QuoteRefreshWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "moex_quote_refresh_manual", ExistingWorkPolicy.REPLACE, request
        )
    }
    val refreshEvents: () -> Unit = {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<CorporateEventsWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "moex_events_refresh_manual", ExistingWorkPolicy.REPLACE, request
        )
    }
    // Refresh dividend data immediately after the portfolio becomes available or changes.
    // The old periodic-only schedule could leave a freshly installed/imported portfolio empty for 24 hours.
    val eventRefreshSignature = assets
        .filter { it.quantity > 0.0 }
        .map { "${it.ticker}:${it.quantity}:${it.assetType}" }
        .sorted()
    LaunchedEffect(eventRefreshSignature) {
        if (eventRefreshSignature.isNotEmpty()) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<CorporateEventsWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "moex_events_refresh_manual", ExistingWorkPolicy.KEEP, request
            )
        }
    }
    val refreshNews: () -> Unit = {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<MarketNewsWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "market_news_refresh_manual", ExistingWorkPolicy.REPLACE, request
        )
    }
    val newsSyncMessage = (
        newsWorkInfos.firstOrNull { it.state == androidx.work.WorkInfo.State.RUNNING }
            ?: newsWorkInfos.firstOrNull { it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.BLOCKED }
            ?: newsWorkInfos.firstOrNull { it.state.isFinished }
        )?.let { info ->
            info.outputData.getString("summary") ?: info.progress.getString("summary")
                ?: when (info.state) {
                    androidx.work.WorkInfo.State.RUNNING -> "Обновление новостей выполняется…"
                    androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED -> "Обновление новостей ожидает подключения к сети…"
                    androidx.work.WorkInfo.State.FAILED -> "Не удалось обновить новости."
                    else -> null
                }
        }
    val eventSyncMessage = (
        eventWorkInfos.firstOrNull { it.state == androidx.work.WorkInfo.State.RUNNING }
            ?: eventWorkInfos.firstOrNull { it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.BLOCKED }
            ?: eventWorkInfos.firstOrNull { it.state.isFinished }
        )?.let { info ->
            info.outputData.getString("summary")
                ?: info.progress.getString("summary")
                ?: when (info.state) {
                    androidx.work.WorkInfo.State.RUNNING -> "Синхронизация выплат выполняется…"
                    androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED -> "Синхронизация выплат ожидает запуска Android…"
                    androidx.work.WorkInfo.State.FAILED -> "Синхронизация выплат завершилась ошибкой без подробностей."
                    androidx.work.WorkInfo.State.CANCELLED -> "Синхронизация выплат отменена."
                    else -> "Синхронизация выплат завершена без диагностического сообщения."
                }
        } ?: "Синхронизация выплат ещё не запускалась. Нажми кнопку обновления на этом экране."

    return MarketRefreshState(
        isRefreshing = isRefreshingMarketData,
        newsSyncMessage = newsSyncMessage,
        eventSyncMessage = eventSyncMessage,
        refreshQuotes = refreshQuotes,
        refreshEvents = refreshEvents,
        refreshNews = refreshNews
    )
}
