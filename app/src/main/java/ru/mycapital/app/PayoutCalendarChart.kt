package ru.mycapital.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.YearMonth

@Composable
internal fun PayoutCalendarChart(
    months: List<java.time.YearMonth>,
    received: Map<java.time.YearMonth, Double>,
    announced: Map<java.time.YearMonth, Double>,
    forecast: Map<java.time.YearMonth, Double>
) {
    if (months.isEmpty()) return
    val maxValue = months.maxOfOrNull {
        (received[it] ?: 0.0) + (announced[it] ?: 0.0) + (forecast[it] ?: 0.0)
    }?.coerceAtLeast(1.0) ?: 1.0
    val receivedColor = Color(0xFF55C99A)
    val announcedColor = Color(0xFF48A9E8)
    val forecastColor = Color(0xFF087BAA).copy(alpha = 0.28f)
    val locale = java.util.Locale("ru", "RU")
    fun compactAmount(value: Double): String = when {
        value >= 1_000_000.0 -> String.format(locale, "%.1fм", value / 1_000_000.0)
        value >= 10_000.0 -> String.format(locale, "%.0fк", value / 1_000.0)
        value >= 1_000.0 -> String.format(locale, "%.1fк", value / 1_000.0)
        else -> String.format(locale, "%.0f", value)
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            months.forEach { month ->
                val receivedValue = received[month] ?: 0.0
                val announcedValue = announced[month] ?: 0.0
                val forecastValue = forecast[month] ?: 0.0
                val total = receivedValue + announcedValue + forecastValue
                val barHeightDp = (total / maxValue * 112.0).coerceAtLeast(0.0)
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(Modifier.fillMaxWidth().height(132.dp)) {
                        Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(112.dp).padding(horizontal = 1.dp)) {
                            val plotHeight = size.height - 2.dp.toPx()
                            val receivedHeight = (receivedValue / maxValue * plotHeight).toFloat()
                            val announcedHeight = (announcedValue / maxValue * plotHeight).toFloat()
                            val forecastHeight = (forecastValue / maxValue * plotHeight).toFloat()
                            val barWidth = size.width * 0.88f
                            val left = (size.width - barWidth) / 2f
                            val bottom = size.height
                            if (receivedHeight > 0.5f) {
                                drawRoundRect(
                                    receivedColor,
                                    Offset(left, bottom - receivedHeight),
                                    androidx.compose.ui.geometry.Size(barWidth, receivedHeight),
                                    androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                                )
                            }
                            if (announcedHeight > 0.5f) {
                                drawRoundRect(
                                    announcedColor,
                                    Offset(left, bottom - receivedHeight - announcedHeight),
                                    androidx.compose.ui.geometry.Size(barWidth, announcedHeight),
                                    androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                                )
                            }
                            if (forecastHeight > 0.5f) {
                                drawRoundRect(
                                    forecastColor,
                                    Offset(left, bottom - receivedHeight - announcedHeight - forecastHeight),
                                    androidx.compose.ui.geometry.Size(barWidth, forecastHeight),
                                    androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                                )
                            }
                        }
                        Text(
                            if (total > 0.0) compactAmount(total) else "—",
                            modifier = Modifier.align(Alignment.BottomCenter).offset(y = -(barHeightDp.dp + 2.dp)),
                            fontSize = 8.sp,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        month.month.getDisplayName(java.time.format.TextStyle.SHORT, locale)
                            .replace(".", "").take(3).lowercase(locale),
                        fontSize = 9.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 5.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(receivedColor))
                    Text("Получено", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(announcedColor))
                    Text("Объявлено", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(forecastColor))
                    Text("Прогноз", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
