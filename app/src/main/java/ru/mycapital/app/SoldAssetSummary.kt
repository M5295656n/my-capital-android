package ru.mycapital.app

internal data class SoldAssetSummary(
    val ticker: String,
    val name: String,
    val quantity: Double,
    val saleCount: Int,
    val realizedProfit: Double,
    val matchedQuantity: Double,
    val unmatchedQuantity: Double,
    val hasCompleteCostBasis: Boolean
)
