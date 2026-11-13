package ru.mycapital.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class OperationHistoryRange(val label: String, val days: Long?) {
    ALL("Всё", null),
    LAST_30_DAYS("30 дней", 30),
    LAST_90_DAYS("90 дней", 90),
    LAST_YEAR("Год", 365)
}

internal fun isOperationWithinHistoryRange(
    operationTimestamp: Long,
    range: OperationHistoryRange,
    today: LocalDate = LocalDate.now(),
    zoneId: ZoneId = ZoneId.systemDefault()
): Boolean {
    val days = range.days ?: return true
    val operationDate = Instant.ofEpochMilli(operationTimestamp).atZone(zoneId).toLocalDate()
    val firstDate = today.minusDays(days - 1)
    return !operationDate.isBefore(firstDate) && !operationDate.isAfter(today)
}
