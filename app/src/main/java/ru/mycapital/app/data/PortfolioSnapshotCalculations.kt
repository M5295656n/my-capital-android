package ru.mycapital.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun portfolioSnapshotDateKey(
    timestampMillis: Long,
    zoneId: ZoneId = ZoneId.of("Europe/Moscow")
): String = Instant.ofEpochMilli(timestampMillis).atZone(zoneId).toLocalDate().toString()

internal fun calculateExternalFlow(operations: List<OperationEntity>): Double =
    operations.sumOf { operation ->
        when (operation.operationType) {
            "Пополнение" -> operation.price
            "Вывод" -> -operation.price
            else -> 0.0
        }
    }
