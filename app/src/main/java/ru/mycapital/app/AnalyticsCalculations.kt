package ru.mycapital.app

import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.PortfolioCompositionSnapshotEntity

internal enum class PortfolioAssetClass(val label: String, val targetShare: Double?) {
    EQUITIES("Акции и фонды роста", 0.65),
    FIXED_INCOME("Облигации и денежный рынок", 0.25),
    GOLD("Золото", 0.05),
    CASH("Кэш", 0.05),
    OTHER("Прочие активы", null)
}

internal data class PortfolioClassSlice(
    val assetClass: PortfolioAssetClass,
    val value: Double,
    val share: Double
)

internal data class PortfolioSectorSlice(
    val sector: String,
    val value: Double,
    val shareOfPortfolio: Double,
    val positions: List<AssetEntity>
)

internal data class PortfolioConcentration(
    val ticker: String,
    val name: String,
    val value: Double,
    val shareOfPortfolio: Double
)

internal fun classifyPortfolioAsset(asset: AssetEntity): PortfolioAssetClass {
    val ticker = asset.ticker.trim().uppercase()
    val type = asset.assetType.trim().lowercase()
    val name = asset.name.trim().lowercase()

    if (ticker in setOf("GOLD", "SBGD", "GLDRUB_TOM") ||
        type.contains("золот") || name.contains("золот")) {
        return PortfolioAssetClass.GOLD
    }
    if (ticker == "SBMM" || type.contains("облиг") || type.contains("офз") ||
        type.contains("bond") || name.contains("облигац") || name.contains("офз") ||
        type.contains("денежн") || name.contains("денежный рынок")) {
        return PortfolioAssetClass.FIXED_INCOME
    }
    if (ticker == "EQMX" || type.contains("акц") || type.contains("stock") ||
        type.contains("share") || name.contains("акци")) {
        return PortfolioAssetClass.EQUITIES
    }
    return PortfolioAssetClass.OTHER
}

internal fun calculatePortfolioClassSlices(
    assets: List<AssetEntity>,
    cashBalance: Double
): List<PortfolioClassSlice> {
    val values = PortfolioAssetClass.entries.associateWith { 0.0 }.toMutableMap()
    assets.forEach { asset ->
        val value = (asset.quantity * asset.currentPrice).coerceAtLeast(0.0)
        val assetClass = classifyPortfolioAsset(asset)
        values[assetClass] = (values[assetClass] ?: 0.0) + value
    }
    values[PortfolioAssetClass.CASH] = (values[PortfolioAssetClass.CASH] ?: 0.0) + cashBalance.coerceAtLeast(0.0)
    val total = values.values.sum()
    if (total <= 0.0) return emptyList()
    return PortfolioAssetClass.entries.mapNotNull { assetClass ->
        val value = values[assetClass] ?: 0.0
        if (value <= 0.0) null else PortfolioClassSlice(assetClass, value, value / total)
    }
}

internal fun sectorForAnalyticsTicker(ticker: String): String? = when (ticker.trim().uppercase()) {
    "SBER", "SBERP", "T", "VTBR", "MOEX", "BSPB", "CBOM" -> "Финансы"
    "LKOH", "ROSN", "TATN", "GAZP", "NVTK", "SNGS", "SNGSP", "BANE", "TRNFP" -> "Нефть и газ"
    "YDEX", "OZON", "MVID", "FIXP", "X5", "MGNT", "LENT" -> "Потребительский сектор"
    "GMKN", "NLMK", "CHMF", "MAGN", "RUAL", "ALRS" -> "Металлы и добыча"
    "MTSS", "RTKM", "VEON" -> "Телекоммуникации"
    "AFLT", "FLOT" -> "Транспорт"
    "PIKK", "SMLT", "LSRG" -> "Недвижимость"
    "IRAO", "HYDR", "FEES", "UPRO" -> "Электроэнергетика"
    else -> null
}

internal fun calculatePortfolioSectorSlices(
    assets: List<AssetEntity>,
    totalPortfolioValue: Double
): List<PortfolioSectorSlice> {
    if (totalPortfolioValue <= 0.0) return emptyList()
    return assets.asSequence()
        .filter { classifyPortfolioAsset(it) == PortfolioAssetClass.EQUITIES }
        .filterNot { it.assetType.contains("фонд", ignoreCase = true) }
        .mapNotNull { asset ->
            val sector = sectorForAnalyticsTicker(asset.ticker) ?: "Не распределено"
            val value = (asset.quantity * asset.currentPrice).coerceAtLeast(0.0)
            if (value <= 0.0) null else sector to (asset to value)
        }
        .groupBy({ it.first }, { it.second })
        .map { (sector, rows) ->
            val value = rows.sumOf { it.second }
            PortfolioSectorSlice(
                sector = sector,
                value = value,
                shareOfPortfolio = value / totalPortfolioValue,
                positions = rows.map { it.first }.sortedByDescending { it.quantity * it.currentPrice }
            )
        }
        .sortedByDescending { it.value }
}

internal fun analyticsIssuerName(asset: AssetEntity): String = when (asset.ticker.trim().uppercase()) {
    "SBER", "SBERP" -> "Сбербанк"
    "SNGS", "SNGSP" -> "Сургутнефтегаз"
    "TATN", "TATNP" -> "Татнефть"
    "BANE", "BANEP" -> "Башнефть"
    else -> asset.name.ifBlank { asset.ticker.uppercase() }
}

internal fun calculatePortfolioConcentration(
    assets: List<AssetEntity>,
    totalPortfolioValue: Double,
    limit: Int = 5
): List<PortfolioConcentration> {
    if (totalPortfolioValue <= 0.0 || limit <= 0) return emptyList()
    return assets.asSequence()
        .map { asset -> asset to (asset.quantity * asset.currentPrice).coerceAtLeast(0.0) }
        .filter { it.second > 0.0 }
        .groupBy { analyticsIssuerName(it.first) }
        .map { (issuer, rows) ->
            val tickers = rows.map { it.first.ticker.trim().uppercase() }.distinct().sorted().joinToString(" / ")
            val value = rows.sumOf { it.second }
            PortfolioConcentration(tickers, issuer, value, value / totalPortfolioValue)
        }
        .sortedByDescending { it.value }
        .take(limit)
}

internal fun buildPortfolioCompositionSnapshots(
    dateKey: String,
    accountId: String,
    assets: List<AssetEntity>,
    cashBalance: Double
): List<PortfolioCompositionSnapshotEntity> {
    val totalValue = assets.sumOf { (it.quantity * it.currentPrice).coerceAtLeast(0.0) } +
        cashBalance.coerceAtLeast(0.0)
    if (totalValue <= 0.0) return emptyList()
    val classes = calculatePortfolioClassSlices(assets, cashBalance).map { slice ->
        PortfolioCompositionSnapshotEntity(
            dateKey = dateKey,
            accountId = accountId,
            dimension = "CLASS",
            category = slice.assetClass.name,
            value = slice.value
        )
    }
    val sectors = calculatePortfolioSectorSlices(assets, totalValue).map { slice ->
        PortfolioCompositionSnapshotEntity(
            dateKey = dateKey,
            accountId = accountId,
            dimension = "SECTOR",
            category = slice.sector,
            value = slice.value
        )
    }
    return classes + sectors
}
