package ru.mycapital.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.BrokerAccountAssetEntity
import ru.mycapital.app.data.BrokerAccountCashEntity
import ru.mycapital.app.data.BrokerAccountEntity
import ru.mycapital.app.data.MarketCalendarItemEntity
import ru.mycapital.app.data.PayoutEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventsScreen(
    assets: List<AssetEntity>,
    cashBalance: Double,
    payouts: List<PayoutEntity>,
    marketCalendarItems: List<MarketCalendarItemEntity>,
    accounts: List<BrokerAccountEntity>,
    accountAssets: List<BrokerAccountAssetEntity>,
    accountCash: List<BrokerAccountCashEntity>,
    onRefreshNews: () -> Unit,
    newsSyncMessage: String?,
    modifier: Modifier = Modifier
) {
    var selectedAccountId by remember { mutableStateOf(ALL_HISTORY_ACCOUNTS) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    val assetsByTicker = remember(assets) { assets.associateBy { it.ticker.trim().uppercase() } }
    val selectedAssets = remember(assets, accountAssets, selectedAccountId) {
        if (selectedAccountId == ALL_HISTORY_ACCOUNTS) assets
        else accountAssets.asSequence()
            .filter { it.accountId == selectedAccountId && it.quantity > 0.0 }
            .mapNotNull { position -> assetsByTicker[position.ticker.trim().uppercase()]?.copy(quantity = position.quantity) }
            .sortedBy { it.assetType + it.ticker }
            .toList()
    }
    val selectedCash = if (selectedAccountId == ALL_HISTORY_ACCOUNTS) cashBalance
        else accountCash.firstOrNull { it.accountId == selectedAccountId }?.balance ?: 0.0
    val selectedTickers = remember(accountAssets, selectedAccountId) {
        if (selectedAccountId == ALL_HISTORY_ACCOUNTS) assets.filter { it.quantity > 0.0 }.map { it.ticker.uppercase() }.toSet()
        else accountAssets.filter { it.accountId == selectedAccountId && it.quantity > 0.0 }.map { it.ticker.uppercase() }.toSet()
    }
    val selectedPayouts = remember(payouts, selectedAccountId, selectedTickers) {
        if (selectedAccountId == ALL_HISTORY_ACCOUNTS) payouts
        else payouts.filter { payout ->
            val payoutAccountId = historyAccountId(payout.sourceKey)
            if (payoutAccountId != null) payoutAccountId == selectedAccountId
            else false
        }
    }
    val selectedNews = remember(marketCalendarItems, selectedAccountId, selectedTickers) {
        if (selectedAccountId == ALL_HISTORY_ACCOUNTS) marketCalendarItems
        else marketCalendarItems.filter { item ->
            item.category == "ECONOMY" || item.relatedTickers.isBlank() ||
                item.relatedTickers.split(",").any { it.trim().uppercase() in selectedTickers }
        }
    }
    val selectedAccountLabel = if (selectedAccountId == ALL_HISTORY_ACCOUNTS) "Все счета"
        else accounts.firstOrNull { it.accountId == selectedAccountId }?.displayName ?: "Счёт"
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("События и новости", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (accounts.isNotEmpty()) {
                    ExposedDropdownMenuBox(expanded = accountMenuExpanded, onExpandedChange = { accountMenuExpanded = it }) {
                        OutlinedTextField(
                            value = selectedAccountLabel, onValueChange = {}, readOnly = true,
                            label = { Text("Брокерский счёт") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountMenuExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = accountMenuExpanded, onDismissRequest = { accountMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text("Все счета") }, onClick = {
                                selectedAccountId = ALL_HISTORY_ACCOUNTS
                                accountMenuExpanded = false
                            })
                            accounts.forEach { account ->
                                DropdownMenuItem(text = { Text(account.displayName) }, onClick = {
                                    selectedAccountId = account.accountId
                                    accountMenuExpanded = false
                                })
                            }
                        }
                    }
                }
            }
        }
        item {
            MarketCalendarSection(
                news = selectedNews,
                payouts = selectedPayouts,
                onRefreshNews = onRefreshNews,
                newsSyncMessage = newsSyncMessage,
                portfolioAssets = selectedAssets,
                portfolioCash = selectedCash
            )
        }
    }
}
