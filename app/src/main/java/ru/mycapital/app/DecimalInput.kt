package ru.mycapital.app

/**
 * Parses user-entered decimal values using either Russian or invariant decimal separators.
 * Grouping whitespace is ignored; malformed and non-finite values are rejected.
 */
internal fun parseDecimalInput(value: String): Double? =
    value.trim()
        .filterNot(Char::isWhitespace)
        .replace(',', '.')
        .toDoubleOrNull()
        ?.takeIf { it.isFinite() }
