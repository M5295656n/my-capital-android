package ru.mycapital.aibenchmark

/** Pure SHA-256 comparison logic, kept separate for deterministic unit tests. */
internal object Sha256Verifier {
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    fun isValid(expected: String): Boolean = sha256Pattern.matches(expected)

    fun matches(expected: String, actual: String?): Boolean =
        actual != null && isValid(expected) && expected == actual
}
