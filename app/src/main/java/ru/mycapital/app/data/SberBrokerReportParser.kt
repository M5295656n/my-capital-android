package ru.mycapital.app.data

import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class BrokerReportAsset(
    val name: String,
    val ticker: String,
    val isin: String,
    val openingQuantity: Double,
    val closingQuantity: Double,
    val closingPrice: Double,
    val closingValue: Double
)

data class BrokerReportCashFlow(
    val date: LocalDate,
    val description: String,
    val credit: Double,
    val debit: Double,
    val rowKey: String
)

data class BrokerReportTrade(
    val tradeDate: LocalDate,
    val settlementDate: LocalDate,
    val time: String,
    val name: String,
    val ticker: String,
    val side: String,
    val quantity: Double,
    val price: Double,
    val grossAmount: Double,
    val brokerFee: Double,
    val exchangeFee: Double,
    val dealNumber: String,
    val status: String
) {
    val totalFee: Double get() = brokerFee + exchangeFee
    val sourceKey: String get() = if (dealNumber.isNotBlank()) "SBER:$dealNumber"
        else "SBER:${tradeDate}:$time:$ticker:$side:$quantity:$price"
}

data class SberBrokerReport(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val openingCash: Double,
    val cashCredits: Double,
    val cashDebits: Double,
    val closingCash: Double,
    val assets: List<BrokerReportAsset>,
    val cashFlows: List<BrokerReportCashFlow>,
    val trades: List<BrokerReportTrade>
)

object SberBrokerReportParser {
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT)

    fun parse(html: String): SberBrokerReport {
        val document = Jsoup.parse(html)
        val tables = document.select("table")
        require(tables.size >= 10) { "Отчёт неполный или имеет неизвестный формат: не найдены все необходимые таблицы." }

        val reportText = document.text()
        val periodMatch = Regex("за период с (\\d{2}\\.\\d{2}\\.\\d{4}) по (\\d{2}\\.\\d{2}\\.\\d{4})")
            .find(reportText) ?: error("Не удалось определить период отчёта.")
        val periodStart = LocalDate.parse(periodMatch.groupValues[1], dateFormat)
        val periodEnd = LocalDate.parse(periodMatch.groupValues[2], dateFormat)
        require(!periodEnd.isBefore(periodStart)) { "В отчёте неверно указан период." }

        fun cells(tableIndex: Int): List<List<String>> = tables[tableIndex].select("tr").map { row ->
            row.select("th,td").map { it.text().replace('\u00a0', ' ').trim() }
        }

        fun number(value: String): Double {
            val normalized = value.replace(" ", "").replace("\u00a0", "").replace(",", ".")
            return normalized.toDoubleOrNull() ?: 0.0
        }

        fun requiredNumber(value: String?, label: String): Double {
            require(!value.isNullOrBlank()) { "В отчёте отсутствует сумма: $label." }
            val normalized = value.replace(" ", "").replace(",", ".")
            return normalized.toDoubleOrNull()
                ?.takeIf { it.isFinite() }
                ?: error("Не удалось прочитать сумму «$label» в брокерском отчёте.")
        }

        val cashRows = cells(3)
        val cashAccountRow = cashRows.firstOrNull {
            it.size >= 6 && it[0].contains("Торговый счет") && it[1] == "RUB"
        } ?: error("В отчёте не найден рублёвый денежный остаток.")
        val openingCash = requiredNumber(cashAccountRow.getOrNull(3), "остаток на начало")
        val closingCash = requiredNumber(cashAccountRow.getOrNull(5), "остаток на конец")
        require(openingCash >= 0.0 && closingCash >= 0.0) { "Денежный остаток в отчёте не может быть отрицательным." }

        val cashFlows = cells(4).mapIndexedNotNull { index, row ->
            if (row.size < 6 || !row[0].matches(Regex("\\d{2}\\.\\d{2}\\.\\d{4}"))) return@mapIndexedNotNull null
            val credit = requiredNumber(row[4], "поступление по денежной операции")
            val debit = requiredNumber(row[5], "списание по денежной операции")
            require(credit >= 0.0 && debit >= 0.0) { "В отчёте найдена отрицательная сумма денежной операции." }
            if (row[3] != "RUB" || (credit == 0.0 && debit == 0.0)) return@mapIndexedNotNull null
            BrokerReportCashFlow(
                date = LocalDate.parse(row[0], dateFormat),
                description = row[2],
                credit = credit,
                debit = debit,
                rowKey = "SBER:CASH:${row[0]}:${row[2]}:$credit:$debit:$index"
            )
        }

        val securityRows = cells(9)
        val tickersByIsin = securityRows.mapNotNull { row ->
            if (row.size >= 3 && row[2].startsWith("RU")) row[2] to row[1] else null
        }.toMap()

        val assets = cells(2).mapNotNull { row ->
            if (row.size < 18 || !row[1].startsWith("RU")) return@mapNotNull null
            BrokerReportAsset(
                name = row[0],
                ticker = tickersByIsin[row[1]].orEmpty(),
                isin = row[1],
                openingQuantity = requiredNumber(row[3], "количество на начало по ${row[0]}"),
                closingQuantity = requiredNumber(row[8], "количество на конец по ${row[0]}"),
                closingPrice = requiredNumber(row[10], "цена на конец по ${row[0]}"),
                closingValue = requiredNumber(row[11], "стоимость позиции ${row[0]}")
            )
        }

        val trades = cells(5).mapNotNull { row ->
            if (row.size < 16 || !row[0].matches(Regex("\\d{2}\\.\\d{2}\\.\\d{4}"))) return@mapNotNull null
            if (row[5] != "RUB") return@mapNotNull null
            BrokerReportTrade(
                tradeDate = LocalDate.parse(row[0], dateFormat),
                settlementDate = LocalDate.parse(row[1], dateFormat),
                time = row[2],
                name = row[3],
                ticker = row[4],
                side = row[6],
                quantity = requiredNumber(row[7], "количество в сделке"),
                price = requiredNumber(row[8], "цена сделки"),
                grossAmount = requiredNumber(row[9], "сумма сделки"),
                brokerFee = requiredNumber(row[11], "комиссия брокера"),
                exchangeFee = requiredNumber(row[12], "биржевая комиссия"),
                dealNumber = row[13],
                status = row[15]
            )
        }

        require(assets.isNotEmpty()) { "В отчёте не найдены позиции ценных бумаг." }
        require(trades.all { it.quantity > 0.0 && it.price >= 0.0 && it.grossAmount >= 0.0 && it.brokerFee >= 0.0 && it.exchangeFee >= 0.0 }) {
            "В отчёте обнаружены отрицательные или некорректные данные по сделкам."
        }
        require(assets.all { it.openingQuantity >= 0.0 && it.closingQuantity >= 0.0 && it.closingPrice >= 0.0 && it.closingValue >= 0.0 }) {
            "В отчёте обнаружены отрицательные или некорректные данные по позициям."
        }
        val flowCredits = cashFlows.sumOf { it.credit }
        val flowDebits = cashFlows.sumOf { it.debit }
        return SberBrokerReport(
            periodStart = periodStart,
            periodEnd = periodEnd,
            openingCash = openingCash,
            cashCredits = flowCredits,
            cashDebits = flowDebits,
            closingCash = closingCash,
            assets = assets,
            cashFlows = cashFlows,
            trades = trades
        )
    }
}
