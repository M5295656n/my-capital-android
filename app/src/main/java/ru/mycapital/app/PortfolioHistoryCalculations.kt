package ru.mycapital.app

import ru.mycapital.app.data.PortfolioSnapshotEntity
import java.time.LocalDate

internal enum class PortfolioHistoryRange(val label: String, val months: Long?) {
    ONE_MONTH("1М", 1),
    THREE_MONTHS("3М", 3),
    SIX_MONTHS("6М", 6),
    ONE_YEAR("1Г", 12),
    ALL("Всё", null)
}

internal data class PortfolioHistoryPoint(
    val dateKey: String,
    val totalValue: Double,
    val nav: Double
)

/**
 * Builds a chronological portfolio history. NAV starts at 100 for the first
 * point in the selected range and excludes recorded external cash flows.
 * Flows are approximated as end-of-day events, matching the snapshot model.
 */
internal fun calculatePortfolioHistoryPoints(
    snapshots: List<PortfolioSnapshotEntity>,
    range: PortfolioHistoryRange
): List<PortfolioHistoryPoint> {
    val ordered = snapshots.sortedBy { it.dateKey }
    if (ordered.isEmpty()) return emptyList()

    val latestDate = LocalDate.parse(ordered.last().dateKey)
    val filtered = range.months?.let { months ->
        val firstDate = latestDate.minusMonths(months)
        ordered.filter { !LocalDate.parse(it.dateKey).isBefore(firstDate) }
    } ?: ordered

    var nav = 100.0
    return filtered.mapIndexed { index, snapshot ->
        if (index > 0) {
            val previousValue = filtered[index - 1].totalValue
            val valueBeforeEndOfDayFlow = snapshot.totalValue - snapshot.externalFlow
            if (previousValue > 0.0 && valueBeforeEndOfDayFlow > 0.0) {
                nav *= valueBeforeEndOfDayFlow / previousValue
            }
        }
        PortfolioHistoryPoint(snapshot.dateKey, snapshot.totalValue, nav)
    }
}
