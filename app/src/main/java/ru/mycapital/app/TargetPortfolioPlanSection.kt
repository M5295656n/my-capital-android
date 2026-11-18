package ru.mycapital.app

import android.content.Context
import androidx.compose.foundation.background
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

@Composable
internal fun TargetPortfolioPlanSection(assets: List<AssetEntity>) {
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

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("План целевого портфеля", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Только отдельные акции. Фонды, облигации, золото и деньги исключены.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, contentDescription = "Добавить акцию") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Целевые веса", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format(Locale("ru", "RU"), "%.2f%% / 100%%", summary.targetWeightTotal),
                        fontWeight = FontWeight.Bold, color = if (validTotal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Стоимость всех акций", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${targetMoney(summary.equityValue)} ₽", fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.fillMaxWidth().height(7.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                Box(Modifier.fillMaxWidth((summary.targetWeightTotal / 100.0).toFloat().coerceIn(0f, 1f)).height(7.dp)
                    .background(if (validTotal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, CircleShape))
            }
            if (!validTotal) {
                val message = if (summary.targetWeightTotal < 100.0)
                    "Осталось распределить ${targetWeightText(100.0 - summary.targetWeightTotal)}. Сумма должна быть ровно 100%."
                else "Сумма целевых весов превышает 100%. Уменьшите веса."
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (plan.isEmpty()) {
                Text("Добавьте акции и задайте целевые веса.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Акция", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Цель / факт", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                summary.rows.forEachIndexed { index, row ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { editing = row.position }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(row.position.ticker, fontWeight = FontWeight.SemiBold)
                                Text(row.position.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${targetWeightText(row.position.targetWeight)} / ${targetWeightText(row.actualWeight)}",
                                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text((if (row.deltaPp > 0.005) "+" else "") + String.format(Locale("ru", "RU"), "%.2f п.п.", row.deltaPp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (row.deltaPp > 0.005) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { editing = row.position }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = "Изменить ${row.position.ticker}", modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = { deleting = row.position }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить ${row.position.ticker}", modifier = Modifier.size(17.dp))
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                            Box(Modifier.fillMaxWidth((row.position.targetWeight / 100.0).toFloat().coerceIn(0f, 1f))
                                .height(6.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
                            Box(Modifier.fillMaxWidth((row.actualWeight / 100.0).toFloat().coerceIn(0f, 1f))
                                .height(6.dp).background(if (row.deltaPp > 0.005) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, CircleShape))
                        }
                        when {
                            row.actualWeight <= 0.0001 -> Text("Позиции нет — фактический вес 0%.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            row.deltaPp < -0.005 -> Text("Не хватает ${targetWeightText(-row.deltaPp)} до цели.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            row.deltaPp > 0.005 -> Text("Превышение цели на ${targetWeightText(row.deltaPp)}.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (index != summary.rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Исполнение плана", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (validTotal) targetWeightText(summary.executionPercent) else "—",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Text(if (validTotal) "Доля выполненных целевых весов" else "После настройки суммы 100%",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                Box(Modifier.fillMaxWidth((summary.executionPercent / 100.0).toFloat().coerceIn(0f, 1f))
                    .height(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
            }
            Text("Фактический вес = стоимость акции / стоимость всех отдельных акций. Значения пересчитываются автоматически при изменении котировок и количества.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var validationError by remember { mutableStateOf<String?>(null) }
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
                OutlinedTextField(ticker, { ticker = it; validationError = null }, label = { Text("Тикер") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Название компании") }, singleLine = true)
                OutlinedTextField(weight, { weight = it; validationError = null }, label = { Text("Целевой вес, %") }, singleLine = true)
                if (duplicate) Text("Такая акция уже есть в плане.", color = MaterialTheme.colorScheme.error)
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
