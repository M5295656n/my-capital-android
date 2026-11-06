package ru.mycapital.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class DividendCalendarEvent(
    val ticker: String,
    val amountPerUnit: Double,
    val status: String,
    val recordDate: Long?,
    val paymentDate: Long?,
    val sourceKey: String
)

internal fun parseLongShortDividendCsv(
    csv: String,
    today: LocalDate = LocalDate.now(ZoneId.of("Europe/Moscow"))
): List<DividendCalendarEvent> {
    val rows = parseCsvRows(csv)
    if (rows.isEmpty()) throw IOException("Пустая выгрузка дивидендов Long/Short")
    val headers = rows.first().mapIndexed { index, value ->
        value.trim().removePrefix("\uFEFF").removeSurrounding("\"").lowercase(Locale.ROOT) to index
    }.toMap()
    fun index(name: String): Int = headers[name] ?: throw IOException("В CSV дивидендов отсутствует столбец $name")
    val tickerIndex = index("secid")
    val amountIndex = index("value")
    val currencyIndex = index("currency")
    val statusIndex = index("status")
    val recordIndex = index("record_date")
    val paymentIndex = index("payment_date")

    val parsed = rows.drop(1).mapNotNull { row ->
        fun cell(i: Int): String = row.getOrNull(i)?.trim().orEmpty()
        val ticker = cell(tickerIndex).uppercase(Locale.ROOT)
        if (ticker.isBlank()) return@mapNotNull null
        val currency = cell(currencyIndex).uppercase(Locale.ROOT)
        if (currency.isNotBlank() && currency != "RUB") return@mapNotNull null
        val amount = cell(amountIndex).replace(" ", "").replace(',', '.').toDoubleOrNull()
            ?: return@mapNotNull null
        if (!amount.isFinite() || amount <= 0.0) return@mapNotNull null
        val status = when (cell(statusIndex).lowercase(Locale.ROOT)) {
            "confirmed", "announced" -> "ANNOUNCED"
            "recommended" -> "RECOMMENDED"
            "forecast" -> "FORECAST"
            else -> return@mapNotNull null
        }
        val recordDate = parseDividendDate(cell(recordIndex))
        val paymentDate = parseDividendDate(cell(paymentIndex))
        if (recordDate == null && paymentDate == null) return@mapNotNull null
        if ((recordDate == null || recordDate.isBefore(today)) &&
            (paymentDate == null || paymentDate.isBefore(today))) return@mapNotNull null

        val eventDate = recordDate ?: paymentDate!!
        val sourceKey = "LONGSHORT:$ticker:$eventDate:${paymentDate ?: "NO_PAYMENT"}"
        DividendCalendarEvent(
            ticker = ticker,
            amountPerUnit = amount,
            status = status,
            recordDate = recordDate?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli(),
            paymentDate = paymentDate?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli(),
            sourceKey = sourceKey
        )
    }

    return parsed.groupBy { it.sourceKey }.values
        .map { rowsForEvent -> rowsForEvent.minBy { ru.mycapital.app.dividendStatusPriority(it.status) } }
        .sortedWith(compareBy<DividendCalendarEvent> { ru.mycapital.app.dividendStatusPriority(it.status) }
            .thenBy { it.recordDate ?: it.paymentDate ?: Long.MAX_VALUE }
            .thenBy { it.ticker })
}

private fun parseDividendDate(value: String): LocalDate? {
    if (value.isBlank()) return null
    return runCatching { LocalDate.parse(value) }.getOrNull()
        ?: runCatching {
            LocalDate.parse(value, java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        }.getOrNull()
}

private fun parseCsvRows(rawText: String): List<List<String>> {
    // The publisher may serve CSV with comma, semicolon (locale export), or tab delimiters.
    // Detect the delimiter from the header rather than silently parsing every line as one cell.
    // Some CSV exporters prepend an Excel delimiter directive (sep=, / sep=; / sep=\\t).
    // Strip its entire line so it cannot be mistaken for the CSV header.
    val withoutBom = rawText.removePrefix("\uFEFF")
    val firstLine = withoutBom.lineSequence().firstOrNull()?.trim()
    val text = if (firstLine?.startsWith("sep=") == true) {
        val newline = withoutBom.indexOf('\\n')
        if (newline < 0) "" else withoutBom.substring(newline + 1).trimStart('\\r', '\\n')
    } else {
        withoutBom
    }
    // The publisher may serve CSV with comma, semicolon (locale export), or tab delimiters.
    // Detect the delimiter from the header rather than silently parsing every line as one cell.
    // Some CSV exporters prepend an Excel delimiter directive (sep=, / sep=; / sep=\\t).
    // Remove the whole directive line; removing only its characters leaves an empty first row,
    // which makes the header parser miss every required column.
    val withoutBom = rawText.removePrefix("\uFEFF")
    val firstLine = withoutBom.lineSequence().firstOrNull()?.trim()
    val text = if (firstLine in setOf("sep=,", "sep=;", "sep=\\t", "sep=\\\\t")) {
        withoutBom.substringAfterFirstLine()
    } else {
        withoutBom
    }
    val header = text.lineSequence().firstOrNull().orEmpty()
    val delimiter = listOf(',', ';', '\t').maxByOrNull { candidate ->
        header.count { it == candidate }
    } ?: ','
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
        val char = text[i]
        when {
            char == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> {
                field.append('"')
                i++
            }
            char == '"' -> quoted = !quoted
            char == delimiter && !quoted -> {
                row += field.toString()
                field.setLength(0)
            }
            (char == '\n' || char == '\r') && !quoted -> {
                if (char == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                row += field.toString()
                field.setLength(0)
                if (row.any { it.isNotBlank() }) rows += row
                row = mutableListOf()
            }
            else -> field.append(char)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) {
        row += field.toString()
        if (row.any { it.isNotBlank() }) rows += row
    }
    if (quoted) throw IOException("Незакрытая кавычка в CSV дивидендов")
    return rows
}

internal class LongShortDividendClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getUpcomingDividends(today: LocalDate = LocalDate.now(ZoneId.of("Europe/Moscow"))): List<DividendCalendarEvent> {
        val request = Request.Builder()
            .url("https://long-short.ru/data/dividends-ru.csv")
            .header("Accept", "text/csv,*/*")
            .header("User-Agent", "MyCapital-Android/0.3.13")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Long/Short вернул HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Пустой CSV дивидендов Long/Short")
            return parseLongShortDividendCsv(body, today)
        }
    }
}
