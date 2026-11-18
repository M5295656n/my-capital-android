package ru.mycapital.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.mycapital.app.data.AssetEntity

internal data class DashboardMarketDataState(
    val data: MarketOverviewData,
    val refresh: () -> Unit
)

/**
 * Keeps dashboard market loading above navigation destinations so changing tabs
 * does not cancel an in-flight request. A failed refresh retains the last values.
 */
@Composable
internal fun rememberDashboardMarketDataState(assets: List<AssetEntity>): DashboardMarketDataState {
    val heldTickers = remember(assets) {
        assets.filter { it.quantity > 0.0 }
            .map { it.ticker.trim().uppercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }
    var data by remember { mutableStateOf(MarketOverviewData()) }
    var refreshVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(heldTickers, refreshVersion) {
        val loaded = runCatching {
            withTimeout(35_000L) {
                withContext(Dispatchers.IO) {
                    MarketOverviewClient().load(heldTickers)
                }
            }
        }
        data = loaded.getOrElse { error ->
            data.copy(error = error.message ?: "Не удалось обновить рыночные данные")
        }
    }

    return DashboardMarketDataState(
        data = data,
        refresh = { refreshVersion++ }
    )
}
