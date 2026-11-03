package ru.mycapital.app

import ru.mycapital.app.data.AssetEntity

internal data class PortfolioAllocationSlice(
    val label: String,
    val value: Double,
    val share: Double
)

internal fun calculatePortfolioAllocation(
    assets: List<AssetEntity>,
    cashBalance: Double
): List<PortfolioAllocationSlice> {
    val values = assets
        .groupBy { it.assetType.trim().ifBlank { "Прочее" } }
        .mapValues { (_, rows) -> rows.sumOf { (it.quantity * it.currentPrice).coerceAtLeast(0.0) } }
        .toMutableMap()
    if (cashBalance > 0.0) values["Денежные средства"] = cashBalance
    val total = values.values.sum()
    if (total <= 0.0) return emptyList()
    return values.entries
        .filter { it.value > 0.0 }
        .sortedByDescending { it.value }
        .map { (label, value) -> PortfolioAllocationSlice(label, value, value / total) }
}
