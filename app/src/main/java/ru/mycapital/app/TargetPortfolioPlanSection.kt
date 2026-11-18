package ru.mycapital.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import ru.mycapital.app.data.AssetEntity
import java.util.Locale

private const val TARGET_PLAN_PREFS = "mycapital_target_portfolio"
private const val TARGET_PLAN_KEY = "positions"

private fun readTargetPlan(context: Context): List<TargetPortfolioPosition> {
    val raw = context.getSharedPreferences(TARGET_PLAN_PREFS, Context.MODE_PRIVATE).getString(TARGET_PLAN_KEY, null) ?: return emptyList()
    return runCatching {
        val json = JSONArray(raw)
        buildList {
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)
                val ticker = item.optString("ticker").trim().uppercase()
                val name = item.optString("name").trim()
                val weight = item.optDouble("weight", Double.NaN)
                if (ticker.isNotBlank() && name.isNotBlank() && weight.isFinite() && weight > 0.0) {
                    add(TargetPortfolioPosition(ticker, name, weight))
                }
            }
        }
    }.getOrDefault(emptyList())
}

private fun saveTargetPlan(context: Context, plan: List<TargetPortfolioPosition>) {
    val json = JSONArray()
    plan.forEach { p -> json.put(JSONObject().put("ticker", p.ticker).put("name", p.name).put("weight", p.targetWeight)) }
    context.getSharedPreferences(TARGET_PLAN_PREFS, Context.MODE_PRIVATE).edit().putString(TARGET_PLAN_KEY, json.toString()).apply()
}

private fun targetWeightText(value: Double): String = String.format(Locale("ru", "RU"), "%.2f%%", value)


private val targetChartColors = listOf(
    Color(0xFF4B9BFF), Color(0xFF55C99A), Color(0xFFFFB454), Color(0xFFB58CFF),
    Color(0xFFFF718B), Color(0xFF45C4D8), Color(0xFF9AA7B8), Color(0xFFD6C45A),
    Color(0xFF8C9EFF), Color(0xFFDA8BFF)
)

@Composable
internal fun TargetPortfolioPlanSection(
    assets: List<AssetEntity>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var plan by remember(context) { mutableStateOf(readTargetPlan(context)) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TargetPortfolioPosition?>(null) }
    var deleting by remember { mutableStateOf<TargetPortfolioPosition?>(null) }
    val summary = remember(assets, plan) { calculateTargetPortfolioSummary(assets, plan) }
    val validTotal = isTargetPortfolioWeightTotalValid(summary.targetWeightTotal)
    fun persist(next: List<TargetPortfolioPosition>) {
        plan = next
        saveTargetPlan(context, next)
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Целевой портфель", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Структура и исполнение инвестиционного плана", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledIconButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, contentDescription = "Добавить позицию")
            }
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("РАСПРЕДЕЛЕНИЕ ЦЕЛЕВЫХ ДОЛЕЙ", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Box(Modifier.size(224.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = Stroke(width = 25.dp.toPx(), cap = StrokeCap.Round)
                        if (summary.rows.isEmpty() || summary.targetWeightTotal <= 0.0) {
                            drawArc(color = Color(0xFF34445B), startAngle = -90f, sweepAngle = 360f,
                                useCenter = false, style = stroke)
                        } else {
                            var angle = -90f
                            summary.rows.forEachIndexed { index, row ->
                                val sweep = (row.position.targetWeight / summary.targetWeightTotal * 360.0).toFloat()
                                if (sweep > 0.8f) {
                                    drawArc(color = targetChartColors[index % targetChartColors.size],
                                        startAngle = angle, sweepAngle = (sweep - 1.2f).coerceAtLeast(0.5f),
                                        useCenter = false, style = stroke)
                                }
                                angle += sweep
                            }
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("ИСПОЛНЕНИЕ", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                        Text(if (validTotal) targetWeightText(summary.executionPercent) else "—",
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                        Text("плана", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Целевой вес", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(targetWeightText(summary.targetWeightTotal), fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.width(1.dp).height(34.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Рыночная стоимость", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${targetMoney(summary.equityValue)} ₽", fontWeight = FontWeight.Bold)
                    }
                }
                if (!validTotal) {
                    val message = if (summary.targetWeightTotal < 100.0)
                        "Распределено ${targetWeightText(summary.targetWeightTotal)} — осталось ${targetWeightText(100.0 - summary.targetWeightTotal)}."
                    else "Сумма целевых долей больше 100%. Скорректируйте веса."
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Состав портфеля", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Text("${plan.size} поз.", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (summary.rows.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.PieChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp))
                        Text("Пока нет позиций", fontWeight = FontWeight.SemiBold)
                        Text("Добавьте компании и распределите между ними 100% целевого веса.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { adding = true }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Добавить первую акцию")
                        }
                    }
                } else {
                    summary.rows.forEachIndexed { index, row ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(11.dp).background(targetChartColors[index % targetChartColors.size], CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f).clickable { editing = row.position },
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(row.position.ticker, fontWeight = FontWeight.Bold)
                                Text(row.position.name, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(targetWeightText(row.position.targetWeight), fontWeight = FontWeight.SemiBold)
                                Text("факт ${targetWeightText(row.actualWeight)}", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { editing = row.position }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = "Изменить ${row.position.ticker}", modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = { deleting = row.position }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить ${row.position.ticker}", modifier = Modifier.size(17.dp))
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(7.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                            Box(Modifier.fillMaxWidth((row.position.targetWeight / 100.0).toFloat().coerceIn(0f, 1f))
                                .height(7.dp).background(targetChartColors[index % targetChartColors.size].copy(alpha = 0.38f), CircleShape))
                            Box(Modifier.fillMaxWidth((row.actualWeight / 100.0).toFloat().coerceIn(0f, 1f))
                                .height(7.dp).background(targetChartColors[index % targetChartColors.size], CircleShape))
                        }
                        val difference = row.deltaPp
                        Text(when {
                            row.actualWeight <= 0.0001 -> "Позиция ещё не сформирована"
                            difference < -0.005 -> "До целевого веса: ${targetWeightText(-difference)}"
                            difference > 0.005 -> "Выше цели на ${targetWeightText(difference)}"
                            else -> "Целевой вес достигнут"
                        }, style = MaterialTheme.typography.labelSmall,
                            color = if (difference > 0.005) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (index != summary.rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Фактические доли обновляются автоматически при изменении состава портфеля и котировок.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (adding || editing != null) {
        val initial = editing
        TargetPortfolioPositionDialog(initial, plan, onDismiss = { adding = false; editing = null }) { position ->
            val updated = if (initial == null) plan + position else plan.map { if (it.ticker == initial.ticker) position else it }
            persist(updated)
            adding = false
            editing = null
        }
    }
    deleting?.let { position ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить ${position.ticker}?") },
            text = { Text("Позиция будет удалена из целевого плана. Данные портфеля не изменятся.") },
            confirmButton = { TextButton(onClick = {
                persist(plan.filterNot { it.ticker == position.ticker }); deleting = null
            }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } })
    }
}

@Composable
private fun TargetPortfolioPositionDialog(
    initial: TargetPortfolioPosition?,
    existing: List<TargetPortfolioPosition>,
    onDismiss: () -> Unit,
    onSave: (TargetPortfolioPosition) -> Unit
) {
    var ticker by remember(initial?.ticker) { mutableStateOf(initial?.ticker.orEmpty()) }
    var name by remember(initial?.ticker) { mutableStateOf(initial?.name.orEmpty()) }
    var weight by remember(initial?.ticker) { mutableStateOf(initial?.targetWeight?.let(::targetWeightText)?.replace("%", "") ?: "") }
    val normalizedTicker = ticker.trim().uppercase()
    val parsedWeight = parseDecimalInput(weight)
    val duplicate = existing.any { it.ticker != initial?.ticker && it.ticker.equals(normalizedTicker, true) }
    val valid = normalizedTicker.isNotBlank() && name.isNotBlank() && parsedWeight != null &&
        parsedWeight > 0.0 && parsedWeight <= 100.0 && !duplicate
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Добавить акцию" else "Изменить ${initial.ticker}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ticker, { ticker = it }, label = { Text("Тикер") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Название компании") }, singleLine = true)
                OutlinedTextField(weight, { weight = it }, label = { Text("Целевой вес, %") }, singleLine = true)
                if (duplicate) Text("Такая акция уже есть в плане.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(TargetPortfolioPosition(normalizedTicker, name.trim(), parsedWeight!!))
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun targetMoney(value: Double): String = String.format(Locale("ru", "RU"), "%,.0f", value)
