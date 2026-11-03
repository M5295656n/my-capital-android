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

enum class BrokerCashFlowKind { DEPOSIT, WITHDRAWAL, DIVIDEND, COUPON, INTERNAL_TRANSFER, IGNORE }

data class BrokerReportCashFlow(
    val date: LocalDate,
    val description: String,
    val credit: Double,
    val debit: Double,
    val rowKey: String,
    val ticker: String? = null
) {
    val kind: BrokerCashFlowKind
        get() = classifyBrokerCashFlow(description)
}

internal fun classifyBrokerCashFlow(description: String): BrokerCashFlowKind {
    val value = description.trim().lowercase(Locale.ROOT)
    return when {
        value.startsWith("зачисление д/с") -> BrokerCashFlowKind.DEPOSIT
        value.startsWith("вывод средств") || value.startsWith("вывод д/с") -> BrokerCashFlowKind.WITHDRAWAL
        value.startsWith("выплата дивидендов") -> BrokerCashFlowKind.DIVIDEND
        value.startsWith("выплата купонов") -> BrokerCashFlowKind.COUPON
        value.startsWith("перевод д/с") || value.startsWith("перевод денежных средств") -> BrokerCashFlowKind.INTERNAL_TRANSFER
        else -> BrokerCashFlowKind.IGNORE
    }
}

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
    val accountId: String,
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
    private val datePattern = Regex("\\d{2}\\.\\d{2}\\.\\d{4}")

    fun parse(html: String): SberBrokerReport {
        val document = Jsoup.parse(html)
        val tables = document.select("table")
        require(tables.isNotEmpty()) { "В отчёте не найдены таблицы." }

        fun rows(tableIndex: Int): List<List<String>> = tables[tableIndex].select("tr").map { row ->
            row.select("th,td").map { it.text().replace('\u00a0', ' ').trim() }
        }
        fun headerIndex(tableIndex: Int, required: Set<String>): Int =
            rows(tableIndex).indexOfFirst { row -> required.all { requiredName -> row.any { it.startsWith(requiredName, true) } } }

        fun locate(required: Set<String>): Pair<Int, Int> {
            tables.indices.forEach { tableIndex ->
                val index = headerIndex(tableIndex, required)
                if (index >= 0) return tableIndex to index
            }
            error("В отчёте не найдена таблица с заголовками: ${required.joinToString()}.")
        }

        fun number(value: String?, label: String): Double {
            require(!value.isNullOrBlank()) { "В отчёте отсутствует сумма: $label." }
            val normalized = value.replace(" ", "").replace('\u00a0'.toString(), "").replace(",", ".")
            return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: error("Не удалось прочитать сумму «$label» в брокерском отчёте.")
        }

        // Sber exports the agreement number on a separate line in <TITLE>;
        // normalize whitespace and accept both е/ё in the Russian word "отчёт".
        val title = document.selectFirst("title")?.text().orEmpty()
            .replace('ё', 'е')
            .replace('Ё', 'Е')
            .replace(Regex("\\s+"), " ")
            .trim()
        val accountId = Regex("(?iu)отчет\\s+брокера\\s+([A-Z0-9-]+)")
            .find(title)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
            ?: error("Не удалось определить номер брокерского счёта в заголовке отчёта: «$title».")
        val reportText = document.text()
        val periodMatch = Regex("за период с (\\d{2}\\.\\d{2}\\.\\d{4}) по (\\d{2}\\.\\d{2}\\.\\d{4})")
            .find(reportText) ?: error("Не удалось определить период отчёта.")
        val periodStart = LocalDate.parse(periodMatch.groupValues[1], dateFormat)
        val periodEnd = LocalDate.parse(periodMatch.groupValues[2], dateFormat)
        require(!periodEnd.isBefore(periodStart)) { "В отчёте неверно указан период." }

        val cashTable = locate(setOf("Торговая площадка", "Валюта", "Начало периода", "Конец периода"))
        val cashRows = rows(cashTable.first)
        val cashHeader = cashRows[cashTable.second]
        val openingIndex = cashHeader.indexOf("Начало периода")
        val closingIndex = cashHeader.indexOf("Конец периода")
        val cashAccountRow = cashRows.drop(cashTable.second + 1).firstOrNull {
            it.firstOrNull()?.contains("Торговый счет", true) == true &&
                it.getOrNull(cashHeader.indexOf("Валюта")) == "RUB"
        } ?: error("В отчёте не найден рублёвый денежный остаток.")
        val openingCash = number(cashAccountRow.getOrNull(openingIndex), "остаток на начало")
        val closingCash = number(cashAccountRow.getOrNull(closingIndex), "остаток на конец")
        require(openingCash >= 0.0 && closingCash >= 0.0) { "Денежный остаток в отчёте не может быть отрицательным." }

        val securitiesTable = locate(setOf("Наименование", "Код", "ISIN ценной бумаги"))
        val securitiesRows = rows(securitiesTable.first).drop(securitiesTable.second + 1)
        val securityNames = securitiesRows.mapNotNull { row ->
            val name = row.getOrNull(0).orEmpty()
            val ticker = row.getOrNull(1).orEmpty()
            val isin = row.getOrNull(2).orEmpty()
            if (name.isNotBlank() && ticker.isNotBlank() && isin.startsWith("RU")) Triple(name, ticker, isin) else null
        }
        fun normalize(value: String): String = value.lowercase(Locale.ROOT)
            .replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]"), "")
        fun tickerFor(description: String): String? {
            val normalizedDescription = normalize(description)
            return securityNames
                .filter { (name, _, _) -> normalize(name).length >= 4 && normalizedDescription.contains(normalize(name)) }
                .maxByOrNull { normalize(it.first).length }?.second
        }

        val flowsTable = locate(setOf("Дата", "Описание операции", "Сумма зачисления", "Сумма списания"))
        val flowRows = rows(flowsTable.first)
        val flowHeader = flowRows[flowsTable.second]
        val dateIndex = flowHeader.indexOf("Дата")
        val descriptionIndex = flowHeader.indexOf("Описание операции")
        val currencyIndex = flowHeader.indexOf("Валюта")
        val creditIndex = flowHeader.indexOf("Сумма зачисления")
        val debitIndex = flowHeader.indexOf("Сумма списания")
        val occurrences = mutableMapOf<String, Int>()
        val cashFlows = flowRows.drop(flowsTable.second + 1).mapNotNull { row ->
            val dateText = row.getOrNull(dateIndex).orEmpty()
            if (!datePattern.matches(dateText)) return@mapNotNull null
            if (row.getOrNull(currencyIndex) != "RUB") return@mapNotNull null
            val description = row.getOrNull(descriptionIndex).orEmpty()
            val credit = number(row.getOrNull(creditIndex), "поступление по денежной операции")
            val debit = number(row.getOrNull(debitIndex), "списание по денежной операции")
            require(credit >= 0.0 && debit >= 0.0) { "В отчёте найдена отрицательная сумма денежной операции." }
            if (credit == 0.0 && debit == 0.0) return@mapNotNull null
            val date = LocalDate.parse(dateText, dateFormat)
            val canonical = "${date}:$description:$credit:$debit"
            val occurrence = occurrences.getOrDefault(canonical, 0)
            occurrences[canonical] = occurrence + 1
            BrokerReportCashFlow(
                date = date,
                description = description,
                credit = credit,
                debit = debit,
                rowKey = "SBER:CASH:$canonical:$occurrence",
                ticker = tickerFor(description)
            )
        }.toMutableList()

        // IIS-3 reports list contributions in a separate register, not necessarily in
        // the general cash-operation table. Import those rows as cash flows as well.
        // Transfers between the user's own agreements remain INTERNAL_TRANSFER and are
        // ignored by the repository's external-cashflow importer.
        val iisContributionTable = tables.indices.firstNotNullOfOrNull { tableIndex ->
            val index = headerIndex(tableIndex, setOf("Дата операции", "Сумма, руб.", "Основание операции"))
            if (index >= 0) tableIndex to index else null
        }
        iisContributionTable?.let { (tableIndex, headerRowIndex) ->
            val contributionRows = rows(tableIndex)
            val header = contributionRows[headerRowIndex]
            val contributionDateIndex = header.indexOf("Дата операции")
            val amountIndex = header.indexOf("Сумма, руб.")
            val reasonIndex = header.indexOf("Основание операции")
            val generalFlowCounts = cashFlows.groupingBy {
                "${it.date}:${it.description}:${it.credit}:${it.debit}"
            }.eachCount()
            val iisOccurrences = mutableMapOf<String, Int>()
            contributionRows.drop(headerRowIndex + 1).forEach { row ->
                val dateText = row.getOrNull(contributionDateIndex).orEmpty()
                if (!datePattern.matches(dateText)) return@forEach
                val description = row.getOrNull(reasonIndex).orEmpty()
                val amount = number(row.getOrNull(amountIndex), "сумма операции по ИИС")
                require(amount >= 0.0) { "В отчёте найдена отрицательная сумма операции по ИИС." }
                if (amount == 0.0 || description.isBlank()) return@forEach
                val date = LocalDate.parse(dateText, dateFormat)
                val canonical = "${date}:$description:$amount:0.0"
                val occurrence = iisOccurrences.getOrDefault(canonical, 0)
                iisOccurrences[canonical] = occurrence + 1
                // If the same row is already present in the general cash-flow table,
                // skip only the matching occurrence; preserve legitimate repeated rows. 
                if (occurrence < generalFlowCounts.getOrDefault(canonical, 0)) return@forEach
                cashFlows += BrokerReportCashFlow(
                    date = date,
                    description = description,
                    credit = amount,
                    debit = 0.0,
                    rowKey = "SBER:IIS:CASH:$canonical:$occurrence",
                    ticker = tickerFor(description)
                )
            }
        }

        val assetsTable = locate(setOf("Наименование", "ISIN ценной бумаги", "Количество, шт", "Рыночная цена"))
        val assetRows = rows(assetsTable.first).drop(assetsTable.second + 1)
        val assets = assetRows.mapNotNull { row ->
            val isin = row.getOrNull(1).orEmpty()
            if (!isin.startsWith("RU") || row.size < 18) return@mapNotNull null
            val security = securityNames.firstOrNull { it.third == isin }
            val closingQuantity = number(row.getOrNull(8), "количество на конец по ${row[0]}")
            val closingValueText = row.getOrNull(11)
            // Sber leaves market value blank for zero-quantity rows (e.g. a fully sold position).
            val closingValue = if (closingValueText.isNullOrBlank() && closingQuantity == 0.0) 0.0
                else number(closingValueText, "стоимость позиции ${row[0]}")
            BrokerReportAsset(
                name = row[0],
                ticker = security?.second.orEmpty(),
                isin = isin,
                openingQuantity = number(row.getOrNull(3), "количество на начало по ${row[0]}"),
                closingQuantity = closingQuantity,
                closingPrice = number(row.getOrNull(10), "цена на конец по ${row[0]}"),
                closingValue = closingValue
            )
        }

        val tradesTable = locate(setOf("Дата заключения", "Дата расчетов", "Номер сделки"))
        val tradeRows = rows(tradesTable.first).drop(tradesTable.second + 1)
        val trades = tradeRows.mapNotNull { row ->
            if (!datePattern.matches(row.getOrNull(0).orEmpty()) || row.size < 16 || row[5] != "RUB") return@mapNotNull null
            BrokerReportTrade(
                tradeDate = LocalDate.parse(row[0], dateFormat),
                settlementDate = LocalDate.parse(row[1], dateFormat),
                time = row[2],
                name = row[3],
                // Sber sometimes writes the ISIN (rather than the exchange ticker)
                // into the trade-code column, notably for OFZ bonds.
                ticker = securityNames.firstOrNull { it.third == row[4] }?.second ?: row[4],
                side = row[6],
                quantity = number(row.getOrNull(7), "количество в сделке"),
                price = number(row.getOrNull(8), "цена сделки"),
                grossAmount = number(row.getOrNull(9), "сумма сделки"),
                brokerFee = number(row.getOrNull(11), "комиссия брокера"),
                exchangeFee = number(row.getOrNull(12), "биржевая комиссия"),
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
        return SberBrokerReport(
            accountId = accountId,
            periodStart = periodStart,
            periodEnd = periodEnd,
            openingCash = openingCash,
            cashCredits = cashFlows.sumOf { it.credit },
            cashDebits = cashFlows.sumOf { it.debit },
            closingCash = closingCash,
            assets = assets,
            cashFlows = cashFlows,
            trades = trades
        )
    }
}
