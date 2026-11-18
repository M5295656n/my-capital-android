package ru.mycapital.app

import ru.mycapital.app.data.OperationEntity

internal const val ALL_HISTORY_ACCOUNTS = "ALL"
internal const val UNASSIGNED_HISTORY_ACCOUNT = "UNASSIGNED"

internal fun historyAccountId(operation: OperationEntity): String? =
    operation.accountId?.takeIf { it.isNotBlank() } ?: historyAccountId(operation.sourceKey)

internal fun historyAccountId(sourceKey: String?): String? {
    if (sourceKey.isNullOrBlank() || !sourceKey.startsWith("SBER:")) return null
    val parts = sourceKey.split(':', limit = 3)
    if (parts.size < 3) return null

    // Legacy imports used SBER:TRADE:... / SBER:CASH:... without an
    // account identifier. New imports use SBER:<accountId>:TRADE|CASH|PAYOUT:...
    // Do not mistake a legacy source category for a broker account ID.
    val secondSegment = parts[1]
    if (secondSegment.uppercase() in setOf("TRADE", "CASH", "PAYOUT")) return null
    return secondSegment.takeIf { it.isNotBlank() }
}
