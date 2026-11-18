package ru.mycapital.app

import ru.mycapital.app.data.AssetEntity

internal data class TargetPortfolioPosition(val ticker: String, val name: String, val targetWeight: Double)
internal data class TargetPortfolioRow(val position: TargetPortfolioPosition, val marketValue: Double, val actualWeight: Double) {
    val deltaPp: Double get() = actualWeight - position.targetWeight
}
internal data class TargetPortfolioSummary(
    val equityValue: Double,
    val targetWeightTotal: Double,
    val executionPercent: Double,
    val rows: List<TargetPortfolioRow>
)

internal fun isIndividualEquity(asset: AssetEntity): Boolean {
    val ticker = asset.ticker.trim().uppercase()
    val type = asset.assetType.trim().lowercase()
    return classifyPortfolioAsset(asset) == PortfolioAssetClass.EQUITIES &&
        ticker != "EQMX" && !type.contains("фонд") && !type.contains("etf") &&
        asset.quantity > 0.0 && asset.currentPrice > 0.0
}

internal fun calculateTargetPortfolioSummary(
    assets: List<AssetEntity>,
    plan: List<TargetPortfolioPosition>
): TargetPortfolioSummary {
    val values = assets.asSequence().filter(::isIndividualEquity)
        .groupBy { it.ticker.trim().uppercase() }
        .mapValues { (_, rows) -> rows.sumOf { it.quantity * it.currentPrice } }
    val total = values.values.sum().coerceAtLeast(0.0)
    val rows = plan.sortedByDescending { it.targetWeight }.map { position ->
        val value = values[position.ticker.uppercase()] ?: 0.0
        TargetPortfolioRow(position, value, if (total > 0.0) value / total * 100.0 else 0.0)
    }
    val targetTotal = plan.sumOf { it.targetWeight }
    val execution = rows.sumOf { minOf(it.actualWeight, it.position.targetWeight).coerceAtLeast(0.0) }
    return TargetPortfolioSummary(total, targetTotal, execution, rows)
}

internal fun isTargetPortfolioWeightTotalValid(total: Double): Boolean = total in 99.999..100.001
