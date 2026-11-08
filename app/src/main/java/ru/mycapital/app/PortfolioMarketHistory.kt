package ru.mycapital.app

import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.MoexSecurityHistoryClient
import ru.mycapital.app.data.OperationEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal data class PortfolioMarketHistoryPoint(
    val dateKey: String,
    val totalValue: Double,
    val investedCapital: Double,
    val nav: Double,
    val cashValue: Double = 0.0,
    val quantities: Map<String, Double> = emptyMap(),
    val assetValues: Map<String, Double> = emptyMap()
)

internal data class PortfolioMarketHistoryResult(
    val points: List<PortfolioMarketHistoryPoint>,
    val firstPurchaseDate: LocalDate?,
    val unavailableTickers: List<String>
)

/**
 * Rebuilds historical portfolio value from the trade ledger and MOEX daily closes.
 * Shares/funds use RUB closes; bonds use dirty RUB prices per bond (clean quote
 * converted from percent of face value plus accrued coupon interest).
 */
internal suspend fun buildPortfolioMarketHistory(
    assets: List<AssetEntity>,
    operations: List<OperationEntity>,
    currentCash: Double,
    range: PortfolioHistoryRange,
    throughDate: LocalDate = LocalDate.now(ZoneId.of("Europe/Moscow")),
    priceLoader: suspend (String, String, LocalDate, LocalDate) -> List<ru.mycapital.app.data.MoexDailyClose> = { ticker, type, from, till ->
        MoexSecurityHistoryClient().getDailyCloses(ticker, type, from, till)
    }
): PortfolioMarketHistoryResult {
    val firstPurchase = operations.asSequence()
        .filter { it.operationType == "Покупка" && it.quantity > 0.0 && !it.ticker.isNullOrBlank() }
        .map { Instant.ofEpochMilli(it.operationDate).atZone(ZoneOffset.UTC).toLocalDate() }
        .minOrNull()
        ?: return PortfolioMarketHistoryResult(emptyList(), null, emptyList())

    if (firstPurchase.isAfter(throughDate)) {
        return PortfolioMarketHistoryResult(emptyList(), firstPurchase, emptyList())
    }

    val assetsByTicker = assets.groupBy { it.ticker.trim().uppercase() }
        .mapValues { (_, rows) ->
            rows.first().copy(
                quantity = rows.sumOf { it.quantity },
                currentPrice = rows.first().currentPrice
            )
        }
    // Historical holdings must include instruments that were fully sold and
    // therefore no longer exist in the current assets table. Otherwise their
    // pre-sale market value disappears while the reverse cash ledger also removes
    // the future sale proceeds, creating an artificial NAV collapse.
    val tradedTickers = operations.asSequence()
        .filter { it.operationType == "Покупка" || it.operationType == "Продажа" }
        .filter { it.quantity > 0.0 }
        .mapNotNull { it.ticker?.trim()?.uppercase()?.takeIf(String::isNotBlank) }
        .toSet()
    val relevantTickers = assetsByTicker.keys + tradedTickers
    val relevant = relevantTickers.map { ticker ->
        ticker to (assetsByTicker[ticker] ?: AssetEntity(
            ticker = ticker,
            name = ticker,
            // Broker history stores no asset type on operations. MOEX bond tickers
            // use the SU...RMFS... convention; other supported traded instruments
            // are quoted by MOEX in the shares market (including exchange funds).
            assetType = if (ticker.startsWith("SU") && ticker.contains("RMFS")) "Облигация" else "Акция",
            quantity = 0.0,
            averagePrice = 0.0,
            currentPrice = 0.0
        ))
    }
    val supported = relevant.filter { (_, asset) ->
        val type = asset.assetType.lowercase()
        type.contains("акци") || type.contains("фонд") || type.contains("пай") ||
            type.contains("облиг") || type.contains("офз") ||
            type.contains("золот") || type.contains("драгмет")
    }
    val unavailable = relevant.map { it.first }.filter { ticker ->
        supported.none { it.first == ticker }
    }.toMutableSet()
    val unavailableConcurrent = ConcurrentHashMap.newKeySet<String>().also { it.addAll(unavailable) }

    if (supported.isEmpty()) {
        return PortfolioMarketHistoryResult(emptyList(), firstPurchase, unavailableConcurrent.toList().sorted())
    }

    // Keep all holdings loading concurrently. The dashboard has a 90-second
    // overall deadline; throttling to two requests while retrying every empty result
    // made a portfolio with several instruments exceed that deadline and lose the
    // entire chart, even when individual MOEX endpoints were healthy.
    val pricesByTicker = coroutineScope {
        supported.map { (ticker, asset) ->
            async(Dispatchers.IO) {
                var closes: List<ru.mycapital.app.data.MoexDailyClose> = emptyList()
                for (attempt in 0..1) {
                    try {
                        closes = priceLoader(ticker, asset.assetType, firstPurchase, throughDate)
                        if (closes.isNotEmpty()) break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Retry once; a failed instrument must not cancel other histories.
                    }
                    if (attempt == 0) delay(350L)
                }
                if (closes.isEmpty()) unavailableConcurrent.add(ticker)
                ticker to closes.associate { it.date to it.close }.toSortedMap()
            }
        }.awaitAll().toMap()
    }

    val dates = pricesByTicker.values.flatMap { it.keys }.distinct().sorted()
    if (dates.isEmpty()) {
        return PortfolioMarketHistoryResult(emptyList(), firstPurchase, unavailableConcurrent.toList().sorted())
    }

    val operationsByTicker = operations.groupBy { it.ticker?.trim()?.uppercase().orEmpty() }
    val orderedOperations = operations.sortedBy { it.operationDate }
    val zone = ZoneOffset.UTC
    val points = mutableListOf<PortfolioMarketHistoryPoint>()
    var nav = 100.0
    var previousValue: Double? = null
    var previousEndOfDay: Long? = null

    dates.forEach { date ->
        val endOfDay = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        var marketValue = 0.0
        val quantitiesAtClose = linkedMapOf<String, Double>()
        val valuesAtClose = linkedMapOf<String, Double>()

        supported.forEach { (ticker, asset) ->
            val futureTradeDelta = operationsByTicker[ticker].orEmpty().asSequence()
                .filter { it.operationDate >= endOfDay }
                .sumOf { operation ->
                    when (operation.operationType) {
                        "Покупка" -> operation.quantity
                        "Продажа" -> -operation.quantity
                        else -> 0.0
                    }
                }
            val quantityAtClose = asset.quantity - futureTradeDelta
            if (quantityAtClose < -0.0000001) {
                unavailableConcurrent.add(ticker)
            }
            if (quantityAtClose > 0.0000001) {
                val historicalPrice = pricesByTicker[ticker]?.entries?.lastOrNull { !it.key.isAfter(date) }?.value
                if (historicalPrice != null) {
                    val positionValue = quantityAtClose * historicalPrice
                    marketValue += positionValue
                    quantitiesAtClose[ticker] = quantityAtClose
                    valuesAtClose[ticker] = positionValue
                }
            }
        }

        // Holdings change on trade date, but broker cash changes on settlement
        // date. For imported trades use T+ settlement; manual/legacy operations
        // retain operationDate as their cash-effective date.
        val futureCashEffects = orderedOperations.asSequence()
            .filter { cashEffectiveTimestamp(it) >= endOfDay }
            .sumOf(::portfolioOperationCashEffect)
        val cashAtClose = currentCash - futureCashEffects
        val totalValue = marketValue + cashAtClose
        val externalFlow = orderedOperations.asSequence()
            .filter { it.operationDate < endOfDay }
            .sumOf { operation ->
                when (operation.operationType) {
                    "Пополнение" -> operation.quantity * operation.price
                    "Вывод" -> -operation.quantity * operation.price
                    else -> 0.0
                }
            }

        val priorValue = previousValue
        if (priorValue != null && priorValue > 0.0) {
            val periodFlow = orderedOperations.asSequence()
                .filter { it.operationDate >= (previousEndOfDay ?: Long.MIN_VALUE) && it.operationDate < endOfDay }
                .sumOf {
                    when (it.operationType) {
                        "Пополнение" -> it.quantity * it.price
                        "Вывод" -> -it.quantity * it.price
                        else -> 0.0
                    }
                }
            val valueBeforeFlow = totalValue - periodFlow
            if (valueBeforeFlow > 0.0) nav *= valueBeforeFlow / priorValue
        }
        points += PortfolioMarketHistoryPoint(
            dateKey = date.toString(),
            totalValue = totalValue,
            investedCapital = externalFlow,
            nav = nav,
            cashValue = cashAtClose,
            quantities = quantitiesAtClose.toMap(),
            assetValues = valuesAtClose.toMap()
        )
        previousValue = totalValue
        previousEndOfDay = endOfDay
    }

    val latestDate = points.lastOrNull()?.let { LocalDate.parse(it.dateKey) }
    val filtered = if (range.months == null || latestDate == null) points else {
        val from = latestDate.minusMonths(range.months)
        points.filter { !LocalDate.parse(it.dateKey).isBefore(from) }
    }
    val normalized = if (filtered.isEmpty()) filtered else {
        val base = filtered.first().nav
        if (base > 0.0) filtered.map { it.copy(nav = it.nav / base * 100.0) } else filtered
    }
    return PortfolioMarketHistoryResult(normalized, firstPurchase, unavailableConcurrent.toList().sorted())
}

private fun cashEffectiveTimestamp(operation: OperationEntity): Long =
    if (operation.operationType == "Покупка" || operation.operationType == "Продажа") {
        operation.settlementDate ?: operation.operationDate
    } else {
        operation.operationDate
    }

private fun portfolioOperationCashEffect(operation: OperationEntity): Double {
    val amount = operation.quantity * operation.price
    return when (operation.operationType) {
        "Пополнение" -> amount
        "Вывод" -> -amount
        "Покупка" -> -(amount + operation.commission)
        "Продажа" -> amount - operation.commission
        "Дивиденд", "Купон", "Амортизация", "Погашение" -> amount - operation.commission
        else -> 0.0
    }
}
