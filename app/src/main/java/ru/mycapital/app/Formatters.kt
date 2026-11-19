package ru.mycapital.app

internal fun money(value: Double): String = String.format(java.util.Locale("ru", "RU"), "%,.2f", value)

internal fun quantity(value: Double): String = String.format(java.util.Locale("ru", "RU"), "%.6f", value).trimEnd('0').trimEnd(',')
