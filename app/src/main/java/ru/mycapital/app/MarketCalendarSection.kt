package ru.mycapital.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.mycapital.app.data.MarketCalendarItemEntity
import ru.mycapital.app.data.PayoutEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class CalendarDisplayItem(
    val key: String,
    val date: LocalDate,
    val category: String,
    val title: String,
    val description: String,
    val source: String,
    val url: String?,
    val tickers: String,
    val isPayout: Boolean
)

@Composable
internal fun MarketCalendarSection(
    news: List<MarketCalendarItemEntity>,
    payouts: List<PayoutEntity>,
    onRefreshNews: () -> Unit,
    newsSyncMessage: String?,
    modifier: Modifier = Modifier
) {
    val zone = remember { ZoneId.of("Europe/Moscow") }
    val today = remember { LocalDate.now(zone) }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDate by remember { mutableStateOf(today) }
    var selectedFilter by remember { mutableStateOf("Все") }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru", "RU")) }
    val newsItems = remember(news) {
        news.map {
            CalendarDisplayItem(
                key = it.sourceKey,
                date = java.time.Instant.ofEpochMilli(it.eventDate).atZone(zone).toLocalDate(),
                category = if (it.category == "ECONOMY") "Экономика" else "Компании",
                title = it.title,
                description = it.summary,
                source = it.sourceName,
                url = it.sourceUrl,
                tickers = it.relatedTickers,
                isPayout = false
            )
        }
    }
    val payoutItems = remember(payouts) {
        payouts.mapNotNull { payout ->
            val timestamp = payout.expectedDate ?: payout.actualDate ?: return@mapNotNull null
            CalendarDisplayItem(
                key = payout.sourceKey ?: "payout:${payout.id}",
                date = java.time.Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate(),
                category = "Выплаты",
                title = "${payout.payoutType}: ${payout.ticker}",
                description = "Ожидаемая сумма по текущей позиции: ${String.format(Locale("ru", "RU"), "%.2f", payout.totalAmount)} ₽. Статус: ${payout.status}.",
                source = payout.sourceName ?: "Портфель",
                url = null,
                tickers = payout.ticker,
                isPayout = true
            )
        }
    }
    val allItems = remember(newsItems, payoutItems) { (newsItems + payoutItems).sortedBy { it.date } }
    val filteredItems = remember(allItems, selectedFilter) {
        if (selectedFilter == "Все") allItems else allItems.filter { it.category == selectedFilter }
    }
    val monthItems = remember(filteredItems, month) { filteredItems.filter { YearMonth.from(it.date) == month } }
    val selectedItems = remember(filteredItems, selectedDate) { filteredItems.filter { it.date == selectedDate } }
    val firstDay = month.atDay(1)
    val offset = firstDay.dayOfWeek.value - 1
    val cellCount = ((offset + month.lengthOfMonth() + 6) / 7) * 7
    val days = (0 until cellCount).map { index ->
        val day = index - offset + 1
        if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
    }
    val uriHandler = LocalUriHandler.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("События и новости", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Календарь рынка и события по портфелю", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefreshNews) {
                    Icon(Icons.Default.Refresh, contentDescription = "Обновить новости")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Все", "Экономика", "Компании", "Выплаты").forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter, maxLines = 1) }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1); selectedDate = month.atDay(1) }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Предыдущий месяц")
                }
                Text(
                    month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru", "RU")))
                        .replaceFirstChar { it.titlecase(Locale("ru", "RU")) },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = { month = month.plusMonths(1); selectedDate = month.atDay(1) }) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Следующий месяц")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach {
                    Text(it, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            days.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    week.forEach { date ->
                        val count = date?.let { d -> monthItems.count { it.date == d } } ?: 0
                        val isSelected = date == selectedDate
                        val isToday = date == today
                        Box(
                            Modifier.weight(1f).aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                .clickable(enabled = date != null) { date?.let { selectedDate = it } },
                            contentAlignment = Alignment.Center
                        ) {
                            if (date != null) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        date.dayOfMonth.toString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (count > 0) {
                                        Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                                    } else {
                                        Spacer(Modifier.size(5.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text("${selectedDate.format(dateFormatter)} · ${selectedItems.size} событий",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (selectedItems.isEmpty()) {
                Text("На выбранную дату событий нет. Дни с событиями отмечены точкой.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                selectedItems.forEach { item ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.category.uppercase(Locale("ru", "RU")), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        if (item.description.isNotBlank()) Text(item.description, style = MaterialTheme.typography.bodySmall)
                        if (item.tickers.isNotBlank()) Text("Связанные бумаги: ${item.tickers}", style = MaterialTheme.typography.labelSmall)
                        Text("Источник: ${item.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!item.url.isNullOrBlank()) {
                            Row(Modifier.clickable { runCatching { uriHandler.openUri(item.url) } }, verticalAlignment = Alignment.CenterVertically) {
                                Text("Открыть первоисточник", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (!item.isPayout) {
                            Text("Оценка влияния будет добавлена после анализа содержания новости; автоматический прогноз цены не формируется.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (!newsSyncMessage.isNullOrBlank()) {
                Text(newsSyncMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
