package ru.mycapital.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.mycapital.app.data.MoexIndexHistoryClient
import ru.mycapital.app.data.IndexHistoryEntity
import ru.mycapital.app.data.PortfolioDao
import ru.mycapital.app.data.MoexIndexPoint
import ru.mycapital.app.data.PortfolioSnapshotEntity
import java.util.Locale
import java.time.LocalDate

private data class IndexBenchmarkCacheEntry(
    val imoex: List<MoexIndexPoint>,
    val mcftr: List<MoexIndexPoint>,
    val error: String?
)

private object IndexBenchmarkMemoryCache {
    private val entries = mutableMapOf<String, IndexBenchmarkCacheEntry>()

    @Synchronized
    fun get(key: String): IndexBenchmarkCacheEntry? = entries[key]

    @Synchronized
    fun put(key: String, entry: IndexBenchmarkCacheEntry) {
        entries[key] = entry
    }
}

@Composable
internal fun IndexBenchmarkSection(history: List<PortfolioMarketHistoryPoint>, dao: PortfolioDao) {
    val ordered = remember(history) { history.sortedBy { it.dateKey } }
    val from = ordered.firstOrNull()?.dateKey
    val till = ordered.lastOrNull()?.dateKey
    val cacheKey = if (from != null && till != null) "$from|$till" else null
    var imoex by remember(cacheKey) {
        mutableStateOf(cacheKey?.let { IndexBenchmarkMemoryCache.get(it)?.imoex }.orEmpty())
    }
    var mcftr by remember(cacheKey) {
        mutableStateOf(cacheKey?.let { IndexBenchmarkMemoryCache.get(it)?.mcftr }.orEmpty())
    }
    var loading by remember { mutableStateOf(false) }
    var error by remember(cacheKey) {
        mutableStateOf(cacheKey?.let { IndexBenchmarkMemoryCache.get(it)?.error })
    }
    var reloadKey by remember { mutableStateOf(0) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(from, till, reloadKey) {
        val key = cacheKey
        if (key != null && from != null && till != null && ordered.size >= 2) {
            if (reloadKey == 0) {
                val memoryCached = IndexBenchmarkMemoryCache.get(key)
                if (memoryCached != null) {
                    imoex = memoryCached.imoex
                    mcftr = memoryCached.mcftr
                    error = memoryCached.error
                    loading = false
                    return@LaunchedEffect
                }
            }
            loading = true
            error = null
            val forceReload = reloadKey > 0
            try {
                val cached = withContext(Dispatchers.IO) {
                    dao.getIndexHistory("IMOEX", from, till) to dao.getIndexHistory("MCFTR", from, till)
                }
                imoex = cached.first.map { MoexIndexPoint(it.dateKey, it.close) }
                mcftr = cached.second.map { MoexIndexPoint(it.dateKey, it.close) }
                if (imoex.isNotEmpty() && mcftr.isNotEmpty()) {
                    IndexBenchmarkMemoryCache.put(key, IndexBenchmarkCacheEntry(imoex, mcftr, null))
                    loading = false
                }
                try {
                    val fresh = withContext(Dispatchers.IO) {
                        val client = MoexIndexHistoryClient()
                        val latestPriceDate = dao.getLatestIndexHistoryDate("IMOEX")
                        val latestTotalDate = dao.getLatestIndexHistoryDate("MCFTR")
                        fun startDate(latest: String?): String {
                            if (forceReload || latest == null) return from
                            if (latest >= till) return till
                            // Re-fetch a short overlap in case MOEX corrected recent sessions.
                            return maxOf(from, LocalDate.parse(latest).minusDays(5).toString())
                        }
                        val price = if (latestPriceDate != null && latestPriceDate >= till && !forceReload) {
                            emptyList()
                        } else client.getHistory("IMOEX", startDate(latestPriceDate), till)
                        val totalReturn = if (latestTotalDate != null && latestTotalDate >= till && reloadKey == 0) {
                            emptyList()
                        } else client.getHistory("MCFTR", startDate(latestTotalDate), till)
                        dao.upsertIndexHistory(
                            price.map { IndexHistoryEntity("IMOEX", it.date, it.value) } +
                            totalReturn.map { IndexHistoryEntity("MCFTR", it.date, it.value) }
                        )
                        val allPrice = dao.getIndexHistory("IMOEX", from, till).map { MoexIndexPoint(it.dateKey, it.close) }
                        val allTotal = dao.getIndexHistory("MCFTR", from, till).map { MoexIndexPoint(it.dateKey, it.close) }
                        allPrice to allTotal
                    }
                    if (fresh.first.isNotEmpty()) imoex = fresh.first
                    if (fresh.second.isNotEmpty()) mcftr = fresh.second
                    if (imoex.isEmpty() || mcftr.isEmpty()) {
                        val missing = buildList {
                            if (imoex.isEmpty()) add("IMOEX")
                            if (mcftr.isEmpty()) add("MCFTR")
                        }
                        error = "MOEX ISS не вернул исторические значения: ${missing.joinToString()}."
                    }
                } catch (networkError: Exception) {
                    if (imoex.isEmpty() || mcftr.isEmpty()) throw networkError
                    error = "Показана сохранённая история индексов. Обновление недоступно: " +
                        (networkError.message ?: "ошибка сети")
                }
            } catch (exception: Exception) {
                error = exception.message ?: "Не удалось загрузить историю индексов."
            } finally {
                if (imoex.isNotEmpty() && mcftr.isNotEmpty()) {
                    IndexBenchmarkMemoryCache.put(key, IndexBenchmarkCacheEntry(imoex, mcftr, error))
                }
                loading = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Сравнение с индексами Мосбиржи", style = MaterialTheme.typography.titleLarge)
        when {
            ordered.size < 2 -> Text(
                "Сравнение появится после восстановления как минимум двух торговых дней истории операций и котировок.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Загружаю исторические данные MOEX…", style = MaterialTheme.typography.bodySmall)
            }
            imoex.isNotEmpty() && mcftr.isNotEmpty() -> BenchmarkChart(ordered, imoex, mcftr)
            else -> Text(error ?: "Исторические ряды пока недоступны.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "IMOEX — ценовой индекс без дивидендов. MCFTR — полная доходность «брутто» с учётом дивидендов. Ряды нормированы к 100 на первой общей дате.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Источник: Московская биржа · ISS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp)
        )
        androidx.compose.foundation.text.ClickableText(
            text = androidx.compose.ui.text.AnnotatedString("Методология и значения MCFTR на сайте MOEX"),
            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary),
            onClick = { uriHandler.openUri("https://www.moex.com/ru/index/totalreturn/MCFTR") }
        )
        androidx.compose.material3.TextButton(
            onClick = { reloadKey++ },
            enabled = !loading && ordered.size >= 2
        ) { Text("Обновить индексы") }
    }
}

@Composable
private fun BenchmarkChart(
    history: List<PortfolioMarketHistoryPoint>,
    imoex: List<MoexIndexPoint>,
    mcftr: List<MoexIndexPoint>
) {
    // Compare only dates present in all three series. Carrying an older index
    // close forward can create misleading flat segments when an index quote is missing.
    val priceByDate = imoex.associate { it.date to it.value }
    val totalByDate = mcftr.associate { it.date to it.value }
    val navByDate = history.associate { LocalDate.parse(it.dateKey) to it.nav }
    val dates = navByDate.keys.intersect(priceByDate.keys).intersect(totalByDate.keys).sorted()
    if (dates.size < 2) {
        Text("Недостаточно совпадающих торговых дат для корректного сравнения портфеля и индексов.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val first = dates.first()
    val p0 = navByDate[first] ?: return
    val i0 = priceByDate[first] ?: return
    val t0 = totalByDate[first] ?: return
    val portfolio = dates.map { (navByDate.getValue(it) / p0 * 100.0).toFloat() }
    val price = dates.map { (priceByDate.getValue(it) / i0 * 100.0).toFloat() }
    val total = dates.map { (totalByDate.getValue(it) / t0 * 100.0).toFloat() }
    val values = portfolio + price + total
    val min = values.minOrNull() ?: 100f
    val max = values.maxOrNull() ?: 100f
    val range = (max - min).takeIf { it > 0.0001f } ?: 1f
    val portfolioColor = MaterialTheme.colorScheme.primary
    val priceColor = Color(0xFFFFB74D) // Amber: clearly distinct from the blue portfolio series
    val totalColor = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple("Весь портфель", portfolio.last(), portfolioColor),
            Triple("IMOEX · цена", price.last(), priceColor),
            Triple("MCFTR · с дивидендами", total.last(), totalColor)
        ).forEach { (label, value, color) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(color, CircleShape))
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    String.format(Locale("ru", "RU"), "%+.2f%%", value - 100f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            val left = 8f
            val right = size.width - 8f
            val top = 8f
            val bottom = size.height - 8f
            for (i in 0..4) {
                val y = top + (bottom - top) * i / 4f
                drawLine(grid, androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(right, y), 1f)
            }
            fun drawSeries(values: List<Float>, color: Color) {
                if (values.size < 2) return
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = left + (right - left) * index / (values.size - 1).toFloat()
                    val y = bottom - ((value - min) / range) * (bottom - top)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 3.5f))
            }
            drawSeries(portfolio, portfolioColor)
            drawSeries(price, priceColor)
            drawSeries(total, totalColor)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(first, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(dates.last(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "«Весь портфель» включает акции, фонды, облигации, золото и денежный остаток. Его доходность — оценка TWR по восстановленным операциям; пополнения и выводы учитываются на конец дня. Для сравнения взяты только даты, присутствующие во всех трёх рядах; каждый ряд нормирован к 100 на первой общей дате.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
