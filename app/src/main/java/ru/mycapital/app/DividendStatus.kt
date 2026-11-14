package ru.mycapital.app

import java.util.Locale

internal fun dividendStatusPriority(status: String): Int = when (status.uppercase(Locale.ROOT)) {
    "ANNOUNCED" -> 0
    "RECOMMENDED" -> 1
    "FORECAST" -> 2
    "EXPECTED" -> 3
    "RECEIVED" -> 4
    else -> 5
}

internal fun isUpcomingDividendStatus(status: String): Boolean =
    status.uppercase(Locale.ROOT) in setOf("ANNOUNCED", "RECOMMENDED", "FORECAST")

internal fun dividendStatusLabel(status: String): String = when (status.uppercase(Locale.ROOT)) {
    "ANNOUNCED" -> "Объявлено"
    "RECOMMENDED" -> "Рекомендовано советом директоров"
    "FORECAST" -> "Прогноз"
    "EXPECTED" -> "Ожидается"
    "RECEIVED" -> "Получено"
    else -> "Статус не указан"
}
