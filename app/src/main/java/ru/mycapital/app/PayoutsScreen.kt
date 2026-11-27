package ru.mycapital.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.BrokerAccountAssetEntity
import ru.mycapital.app.data.OperationEntity
import ru.mycapital.app.data.PayoutEntity
import ru.mycapital.app.data.payoutDisplayTimestamp
import ru.mycapital.app.data.isIncomePayoutType

@Composable
internal fun PayoutsScreen(
    operations: List<OperationEntity>,
    payouts: List<PayoutEntity>,
    assets: List<AssetEntity>,
    accounts: List<ru.mycapital.app.data.BrokerAccountEntity>,
    accountAssets: List<ru.mycapital.app.data.BrokerAccountAssetEntity>,
    selectedAccountId: String,
    onAccountSelected: (String) -> Unit,
    onConfirmReceipt: (PayoutEntity) -> Unit,
    onRefreshEvents: () -> Unit,
    eventSyncMessage: String?,
    modifier: Modifier = Modifier
) {
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val currentYear = today.year
    // Keep years in chronological order: older years are to the left of the current year,
    // newer years are to the right. The initial viewport is anchored on the current year.
    val periods = remember(currentYear) {
        buildList {
            (1..5).reversed().forEach { offset ->
                if (currentYear - offset >= 2000) add("year:${currentYear - offset}")
            }
            add("year:$currentYear")
            (1..6).forEach { add("year:${currentYear + it}") }
        }
    }
    val periodListState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = periods.indexOf("year:$currentYear").coerceAtLeast(0)
    )
    var selectedPeriod by remember(currentYear) { mutableStateOf("year:$currentYear") }
    var statusFilter by remember { mutableStateOf("Все") }
    var typeFilter by remember { mutableStateOf("Все") }
    var expandedPayoutId by remember { mutableStateOf<Long?>(null) }
    val visiblePayouts = remember(payouts, selectedAccountId, accountAssets, assets) {
        payouts.filter { payout ->
            shouldShowPayoutForCurrentHoldings(
                payout = payout,
                assets = assets,
                accountAssets = accountAssets,
                selectedAccountId = selectedAccountId
            )
        }
    }
    val periodDates = remember(selectedPeriod, today) {
        val year = selectedPeriod.substringAfter("year:").toIntOrNull() ?: currentYear
        java.time.LocalDate.of(year, 1, 1) to java.time.LocalDate.of(year, 12, 31)
    }
    val filtered = remember(visiblePayouts, periodDates, statusFilter, typeFilter) {
        visiblePayouts.filter { payout ->
            val millis = payoutDisplayTimestamp(payout) ?: return@filter false
            val date = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            val inPeriod = !date.isBefore(periodDates.first) && !date.isAfter(periodDates.second)
            val statusOk = statusFilter == "Все" || when (statusFilter) {
                "Объявлены" -> payout.status == "ANNOUNCED"
                "Рекомендованы" -> payout.status == "RECOMMENDED"
                "Прогноз" -> payout.status == "FORECAST" || payout.status == "EXPECTED"
                "Получены" -> payout.status == "RECEIVED"
                else -> true
            }
            val typeOk = typeFilter == "Все" ||
                (typeFilter == "Дивиденды" && payout.payoutType.contains("Дивиденд", true)) ||
                (typeFilter == "Купоны" && payout.payoutType.contains("Купон", true)) ||
                (typeFilter == "Погашения" && (payout.payoutType.contains("Погаш", true) || payout.payoutType.contains("Амортизац", true)))
            inPeriod && statusOk && typeOk
        }.sortedWith(compareBy<PayoutEntity> { payoutDisplayTimestamp(it) ?: Long.MAX_VALUE }.thenBy { it.ticker })
    }
    fun payoutAmount(p: PayoutEntity): Double =
        if (p.status == "RECEIVED") (p.reportedNetAmount ?: (p.totalAmount - p.taxAmount)).coerceAtLeast(0.0)
        else p.totalAmount.coerceAtLeast(0.0)
    // Principal returned at bond amortization/maturity is capital, not income.
    // Keep redemption rows visible below, but exclude them from income metrics.
    val totalAmount = filtered.filter { isIncomePayoutType(it.payoutType) }.sumOf(::payoutAmount)
    val monthly = filtered.groupBy { payout ->
        java.time.YearMonth.from(java.time.Instant.ofEpochMilli(payoutDisplayTimestamp(payout)!!).atZone(zone).toLocalDate())
    }.toSortedMap()
    val monthlyTotals = monthly.mapValues { (_, items) -> items.sumOf(::payoutAmount) }
    val monthAverage = if (monthly.isNotEmpty()) totalAmount / monthly.size else 0.0
    val dayAverage = if (periodDates.second.toEpochDay() >= periodDates.first.toEpochDay()) {
        totalAmount / (periodDates.second.toEpochDay() - periodDates.first.toEpochDay() + 1)
    } else 0.0
    val portfolioValue = assets.sumOf { it.quantity.coerceAtLeast(0.0) * it.currentPrice.coerceAtLeast(0.0) }
    val payoutYield = if (portfolioValue > 0.0) totalAmount / portfolioValue * 100.0 else 0.0
    // Calendar chart follows the selected calendar year: January through December.
    val chartMonths = remember(selectedPeriod) {
        val year = selectedPeriod.substringAfter("year:").toIntOrNull() ?: currentYear
        val first = java.time.YearMonth.of(year, 1)
        (0 until 12).map { first.plusMonths(it.toLong()) }
    }
    val chartPayouts = visiblePayouts.filter { payout ->
        val dateMillis = payoutDisplayTimestamp(payout) ?: return@filter false
        val date = java.time.Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
        val month = java.time.YearMonth.from(date)
        val inChartPeriod = !month.isBefore(chartMonths.first()) && !month.isAfter(chartMonths.last())
        val typeOk = typeFilter == "Все" ||
            (typeFilter == "Дивиденды" && payout.payoutType.contains("Дивиденд", true)) ||
            (typeFilter == "Купоны" && payout.payoutType.contains("Купон", true)) ||
            (typeFilter == "Погашения" && (payout.payoutType.contains("Погаш", true) || payout.payoutType.contains("Амортизац", true)))
        inChartPeriod && typeOk && isIncomePayoutType(payout.payoutType)
    }
    val chartMonthly = chartPayouts.groupBy { payout ->
        java.time.YearMonth.from(java.time.Instant.ofEpochMilli(payoutDisplayTimestamp(payout)!!).atZone(zone).toLocalDate())
    }
    val receivedByMonth = chartMonths.associateWith { month ->
        chartMonthly[month].orEmpty().filter { it.status == "RECEIVED" }.sumOf(::payoutAmount)
    }
    val announcedByMonth = chartMonths.associateWith { month ->
        chartMonthly[month].orEmpty().filter { it.status == "ANNOUNCED" }.sumOf(::payoutAmount)
    }
    // The chart has only three states: received, announced and forecast.
    // Board recommendations are still forecasts until formally announced.
    val currentMonth = java.time.YearMonth.from(today)
    val forecastByMonth = chartMonths.associateWith { month ->
        if (month.isBefore(currentMonth)) 0.0
        else chartMonthly[month].orEmpty()
            .filter { it.status == "RECOMMENDED" || it.status == "FORECAST" || it.status == "EXPECTED" }
            .sumOf(::payoutAmount)
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Выплаты", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                AccountHistoryFilter(accounts, selectedAccountId, onSelect = onAccountSelected)
            }
            item {
                androidx.compose.foundation.lazy.LazyRow(
                    state = periodListState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(periods) { period ->
                        val label = when (period) {
                            else -> period.substringAfter("year:")
                        }
                        FilterChip(
                            selected = selectedPeriod == period,
                            onClick = { selectedPeriod = period },
                            label = { Text(label, maxLines = 1) }
                        )
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Доход за ${selectedPeriod.substringAfter("year:")}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${money(totalAmount)} ₽", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("В месяц", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${money(monthAverage)} ₽", fontWeight = FontWeight.SemiBold)
                        }
                        Column {
                            Text("В день", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${money(dayAverage)} ₽", fontWeight = FontWeight.SemiBold)
                        }
                        Column {
                            Text("Доходность", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${String.format(java.util.Locale("ru", "RU"), "%.2f", payoutYield)}%", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item {
                PayoutCalendarChart(
                    months = chartMonths,
                    received = receivedByMonth,
                    announced = announcedByMonth,
                    forecast = forecastByMonth
                )
            }
            item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("Все", "Объявлены", "Рекомендованы", "Прогноз", "Получены")) { filter ->
                        FilterChip(selected = statusFilter == filter, onClick = { statusFilter = filter }, label = { Text(filter) })
                    }
                }
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("Все", "Дивиденды", "Купоны", "Погашения")) { filter ->
                        FilterChip(selected = typeFilter == filter, onClick = { typeFilter = filter }, label = { Text(filter) })
                    }
                }
            }
            if (monthly.isEmpty()) {
                item {
                    Text("Нет выплат по выбранным фильтрам.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp))
                }
            } else {
                monthly.forEach { (month, itemsForMonth) ->
                    item(key = "month-$month") {
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                month.format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", java.util.Locale("ru", "RU")))
                                    .replaceFirstChar { it.titlecase(java.util.Locale("ru", "RU")) },
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text("+${money(itemsForMonth.filter { isIncomePayoutType(it.payoutType) }.sumOf(::payoutAmount))} ₽",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    color = Color(0xFF55C99A), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    val byDate = itemsForMonth.groupBy { java.time.Instant.ofEpochMilli(payoutDisplayTimestamp(it)!!).atZone(zone).toLocalDate() }.toSortedMap()
                    byDate.forEach { (date, dayItems) ->
                        item(key = "date-$date") {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CalendarToday, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    Text(
                                        date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale("ru", "RU"))) +
                                            ", " + date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale("ru", "RU")),
                                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                dayItems.forEach { payout ->
                                    val asset = assets.firstOrNull { it.id == payout.assetId }
                                        ?: assets.firstOrNull { it.ticker.equals(payout.ticker, true) }
                                    val expanded = expandedPayoutId == payout.id
                                    Card(
                                        modifier = Modifier.fillMaxWidth().clickable { expandedPayoutId = if (expanded) null else payout.id },
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                AssetTypeIcon(
                                                    asset?.assetType ?: if (payout.payoutType.contains("купон", true)) "Облигация" else "Акция",
                                                    payout.ticker
                                                )
                                                Text(
                                                    "${payout.ticker}  ${asset?.name ?: payout.payoutType}",
                                                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 2
                                                )
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("+${money(payoutAmount(payout))} ₽",
                                                        color = Color(0xFF55C99A), fontWeight = FontWeight.Bold)
                                                    val yield = if (asset != null && asset.currentPrice > 0.0 && payout.amountPerUnit > 0.0)
                                                        payout.amountPerUnit / asset.currentPrice * 100.0 else null
                                                    if (yield != null) Text(
                                                        String.format(java.util.Locale("ru", "RU"), "%.2f%%", yield),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                                Text(
                                                    when (payout.status) {
                                                        "RECEIVED" -> "Получено"
                                                        "ANNOUNCED" -> "Объявлено"
                                                        "RECOMMENDED" -> "Рекомендовано советом директоров"
                                                        "FORECAST", "EXPECTED" -> "Прогноз"
                                                        else -> "Прогноз"
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    color = Color(0xFF69AFFF),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                                    maxLines = 2
                                                )
                                            }
                                            Text(
                                                (if (payout.payoutType.contains("Купон", true)) "Купон" else payout.payoutType) +
                                                    " · " + (if (payout.quantity > 0.0) "${money(payout.amountPerUnit)} ₽ на бумагу" else "сумма выплаты"),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            if (expanded) {
                                                HorizontalDivider()
                                                payout.recordDate?.let {
                                                    val recordLabel = if (payout.sourceName == "MyCapital · история MOEX" && payout.status == "FORECAST")
                                                        "Оценка даты отсечки по истории" else "Дата отсечки"
                                                    Text(recordLabel + ": " + java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                                                }
                                                payout.expectedDate?.let { Text("Ожидаемая дата выплаты: " + java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))) }
                                                payout.actualDate?.let { Text("Получено: " + java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))) }
                                                if (payout.status != "RECEIVED" && (isUpcomingDividendStatus(payout.status) || payout.status == "EXPECTED")) {
                                                    TextButton(onClick = { onConfirmReceipt(payout) }) { Text("Подтвердить получение") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

