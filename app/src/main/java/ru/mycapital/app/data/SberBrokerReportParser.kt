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
        require(tables.size >= 6) { "Это не похоже на полный брокерский отчёт Сбербанка: не найдены основные таблицы." }

        val reportText = document.text()
        val periodMatch = Regex("за период с (\\d{2}\\.\\d{2}\\.\\d{4}) по (\\d{2}\\.\\d{2}\\.\\d{4})")
            .find(reportText) ?: error("Не удалось определить период отчёта.")
        val periodStart = LocalDate.parse(periodMatch.groupValues[1], dateFormat)
        val periodEnd = LocalDate.parse(periodMatch.groupValues[2], dateFormat)

        fun cells(tableIndex: Int): List<List<String>> = tables[tableIndex].select("tr").map { row ->
            row.select("th,td").map { it.text().replace('\u00a0', ' ').trim() }
        }

        fun number(value: String): Double {
            val normalized = value.replace(" ", "").replace("\u00a0", "").replace(",", ".")
            return normalized.toDoubleOrNull() ?: 0.0
        }

        val cashRows = cells(3)
        val openingCash = cashRows.firstOrNull { it.size >= 6 && it[0].contains("Торговый счет") && it[1] == "RUB" }
            ?.getOrNull(3)?.let(::number) ?: 0.0
        val closingCash = cashRows.firstOrNull { it.size >= 6 && it[0].contains("Торговый счет") && it[1] == "RUB" }
            ?.getOrNull(5)?.let(::number) ?: 0.0

        val cashFlows = cells(4).mapIndexedNotNull { index, row ->
            if (row.size < 6 || !row[0].matches(Regex("\\d{2}\\.\\d{2}\\.\\d{4}"))) return@mapIndexedNotNull null
            val credit = number(row[4])
            val debit = number(row[5])
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
                openingQuantity = number(row[3]),
                closingQuantity = number(row[8]),
                closingPrice = number(row[10]),
                closingValue = number(row[11])
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
                quantity = number(row[7]),
                price = number(row[8]),
                grossAmount = number(row[9]),
                brokerFee = number(row[11]),
                exchangeFee = number(row[12]),
                dealNumber = row[13],
                status = row[15]
            )
        }

        require(assets.isNotEmpty()) { "В отчёте не найдены позиции ценных бумаг." }
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
