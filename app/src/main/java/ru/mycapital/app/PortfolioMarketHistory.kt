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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal data class PortfolioMarketHistoryPoint(
    val dateKey: String,
    val totalValue: Double,
    val investedCapital: Double,
    val nav: Double
)

internal data class PortfolioMarketHistoryResult(
    val points: List<PortfolioMarketHistoryPoint>,
    val firstPurchaseDate: LocalDate?,
    val unavailableTickers: List<String>
)

/**
 * Rebuilds historical portfolio value from the trade ledger and MOEX daily closes.
 * Holdings and cash are reverse-reconciled from today's broker snapshot, so a
 * second daily portfolio snapshot is not required. External deposits/withdrawals
 * are kept separate from investment return.
 *
 * Bonds are intentionally excluded until historical clean price + accrued coupon
 * valuation is implemented; the result reports excluded tickers explicitly.
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
    val boughtTickers = operations.asSequence()
        .filter { it.operationType == "Покупка" && it.quantity > 0.0 }
        .mapNotNull { it.ticker?.trim()?.uppercase()?.takeIf(String::isNotBlank) }
        .toSet()
    val relevant = (assetsByTicker.keys + boughtTickers).mapNotNull { ticker ->
        assetsByTicker[ticker]?.let { ticker to it }
    }
    val supported = relevant.filter { (_, asset) ->
        val type = asset.assetType.lowercase()
        type.contains("акци") || type.contains("фонд") || type.contains("пай")
    }
    val unavailable = relevant.map { it.first }.filter { ticker ->
        supported.none { it.first == ticker }
    }.toMutableSet()
    val unavailableConcurrent = ConcurrentHashMap.newKeySet<String>().also { it.addAll(unavailable) }

    if (supported.isEmpty()) {
        return PortfolioMarketHistoryResult(emptyList(), firstPurchase, unavailableConcurrent.toList().sorted())
    }

    val pricesByTicker = coroutineScope {
        supported.map { (ticker, asset) ->
            async(Dispatchers.IO) {
                val closes = runCatching {
                    priceLoader(ticker, asset.assetType, firstPurchase, throughDate)
                }.getOrElse {
                    unavailableConcurrent.add(ticker)
                    emptyList()
                }
                if (closes.isEmpty()) unavailableConcurrent.add(ticker)
                ticker to closes.associate { it.date to it.close }.toSortedMap()
            }
        }.awaitAll().toMap()
    }

    val dates = pricesByTicker.values.flatMap { it.keys }.distinct().sorted()
    if (dates.isEmpty()) {
        return PortfolioMarketHistoryResult(emptyList(), firstPurchase, unavailable.sorted())
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
                if (historicalPrice != null) marketValue += quantityAtClose * historicalPrice
            }
        }

        val futureCashEffects = orderedOperations.asSequence()
            .filter { it.operationDate >= endOfDay }
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
            nav = nav
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

private fun portfolioOperationCashEffect(operation: OperationEntity): Double {
    val amount = operation.quantity * operation.price
    return when (operation.operationType) {
        "Пополнение" -> amount
        "Вывод" -> -amount
        "Покупка" -> -(amount + operation.commission)
        "Продажа" -> amount - operation.commission
        "Дивиденд", "Купон" -> amount - operation.commission
        else -> 0.0
    }
}

