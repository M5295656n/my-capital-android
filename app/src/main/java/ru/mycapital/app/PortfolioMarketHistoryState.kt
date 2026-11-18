package ru.mycapital.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.BrokerAccountEntity
import ru.mycapital.app.data.OperationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class PortfolioMarketHistoryState(
    val selectedRange: PortfolioHistoryRange,
    val onRangeChange: (PortfolioHistoryRange) -> Unit,
    val result: PortfolioMarketHistoryResult,
    val isLoading: Boolean,
    val error: String?,
    val refresh: () -> Unit
)

/**
 * Owns the portfolio chart's range, loading lifecycle and retry behavior.
 * Kept above navigation destinations so switching tabs does not cancel requests
 * or reset the chart state.
 */
@Composable
internal fun rememberPortfolioMarketHistoryState(
    assets: List<AssetEntity>,
    operations: List<OperationEntity>,
    currentCash: Double,
    brokerAccounts: List<BrokerAccountEntity>
): PortfolioMarketHistoryState {
    var selectedRange by remember { mutableStateOf(PortfolioHistoryRange.ALL) }
    var result by remember {
        mutableStateOf(PortfolioMarketHistoryResult(emptyList(), null, emptyList()))
    }
    var isLoading by remember { mutableStateOf(false) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val assetsKey = remember(assets) {
        assets.map { Triple(it.ticker.uppercase(), it.quantity, it.assetType) }
    }
    val brokerAccountIds = remember(brokerAccounts) {
        brokerAccounts.map { it.accountId }.toSet()
    }

    LaunchedEffect(assetsKey, operations, currentCash, brokerAccountIds, selectedRange, refreshVersion) {
        isLoading = true
        loadError = null
        var latestSuccessful: PortfolioMarketHistoryResult? = null
        var lastError: Throwable? = null
        for (attempt in 0..2) {
            try {
                val loaded = withTimeout(90_000L) {
                    withContext(Dispatchers.IO) {
                        buildPortfolioMarketHistory(
                            assets,
                            operations,
                            currentCash,
                            selectedRange,
                            includedBrokerAccountIds = brokerAccountIds
                        )
                    }
                }
                latestSuccessful = loaded
                if (loaded.points.isNotEmpty() && loaded.unavailableTickers.isEmpty()) break
                if (attempt < 2) kotlinx.coroutines.delay(1_000L * (attempt + 1))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                if (attempt < 2) kotlinx.coroutines.delay(1_000L * (attempt + 1))
            }
        }
        val loaded = latestSuccessful
        if (loaded != null && loaded.points.isNotEmpty()) {
            result = loaded
            loadError = if (loaded.unavailableTickers.isNotEmpty()) {
                "Нет истории для: ${loaded.unavailableTickers.joinToString()}"
            } else null
        } else if (result.points.isEmpty()) {
            loadError = lastError?.message
                ?: "Исторические котировки пока не получены. Повторите обновление."
        }
        isLoading = false
    }

    return PortfolioMarketHistoryState(
        selectedRange = selectedRange,
        onRangeChange = { selectedRange = it },
        result = result,
        isLoading = isLoading,
        error = loadError,
        refresh = { refreshVersion++ }
    )
}
