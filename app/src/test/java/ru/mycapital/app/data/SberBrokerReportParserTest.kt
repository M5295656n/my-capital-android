package ru.mycapital.app.data

import java.time.LocalDate
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class SberBrokerReportParserTest {
    private fun sampleHtml(
        tableCount: Int = 11,
        tradePrice: String = "47.34",
        cashCredit: String = "107.00",
        closingQuantity: String = "3",
        closingValue: String = "33",
        tradeCode: String = "TEST",
        includeMatchingIisRegisterRows: Boolean = true,
        plannedClosingQuantity: String = "3",
        plannedClosingCash: String = "635.73",
        tradeStatus: String = "И"
    ): String {
        val assets = """
            <table>
              <tr><td>Основной рынок</td></tr>
              <tr><td>Наименование</td><td>ISIN ценной бумаги</td><td>Валюта рыночной цены</td><td>Количество, шт</td><td>Номинал</td><td>Рыночная цена ²</td><td>Рыночная стоимость</td><td>НКД</td><td>Количество, шт</td><td>Номинал</td><td>Рыночная цена ²</td><td>Рыночная стоимость</td><td>НКД</td><td>Количество, шт</td><td>Рыночная стоимость</td><td>Плановые зачисления</td><td>Плановые списания</td><td>Плановый остаток</td></tr>
              <tr><td>1</td></tr>
              <tr><td>Тестовая акция</td><td>RU000A000001</td><td>RUB</td><td>2</td><td>10</td><td>10</td><td>20</td><td>0</td><td>$closingQuantity</td><td>10</td><td>11</td><td>$closingValue</td><td>0</td><td>3</td><td>33</td><td>0</td><td>0</td><td>$plannedClosingQuantity</td></tr>
            </table>
        """.trimIndent()
        val cash = """
            <table>
              <tr><td>Торговая площадка</td><td>Валюта</td><td>Курс на конец периода</td><td>Начало периода</td><td>Изменение за период</td><td>Конец периода</td><td>Плановые зачисления</td><td>Плановые списания</td><td>Плановый исходящий остаток</td></tr>
              <tr><td>1</td></tr>
              <tr><td>Торговый счет, Основной рынок</td><td>RUB</td><td></td><td>428.73</td><td>207.00</td><td>635.73</td><td>0</td><td>0</td><td>$plannedClosingCash</td></tr>
            </table>
        """.trimIndent()
        val flows = """
            <table>
              <tr><td>Дата</td><td>Торговая площадка</td><td>Описание операции</td><td>Валюта</td><td>Сумма зачисления</td><td>Сумма списания</td></tr>
              <tr><td>1</td></tr>
              <tr><td>30.10.2026</td><td>Основной рынок</td><td>Зачисление д/с</td><td>RUB</td><td>$cashCredit</td><td>0.00</td></tr>
              <tr><td>30.10.2026</td><td>Основной рынок</td><td>Зачисление д/с</td><td>RUB</td><td>100.00</td><td>0.00</td></tr>
              <tr><td>30.10.2026</td><td>Основной рынок</td><td>Выплата дивидендов Т-Технологии. Налог удержан.</td><td>RUB</td><td>8.40</td><td>0.00</td></tr>
              <tr><td>30.10.2026</td><td>Основной рынок</td><td>Выплата купонов ОФЗ 26228, номер купона 15</td><td>RUB</td><td>38.15</td><td>0.00</td></tr>
              <tr><td>30.10.2026</td><td>Основной рынок</td><td>Перевод д/с с договора 403RY0J на договор S25F9ZU</td><td>RUB</td><td>0.00</td><td>13.00</td></tr>
              <tr><td>30.10.2026</td><td>Основной рынок</td><td>Сделка от 29.10.2026</td><td>RUB</td><td>0.00</td><td>20.00</td></tr>
              <tr><td>30.10.2026</td><td>Основной рынок</td><td>Комиссия Брокера от 29.10.2026</td><td>RUB</td><td>0.00</td><td>0.10</td></tr>
              ${if (includeMatchingIisRegisterRows) """
                <tr><td>23.10.2026</td><td>Основной рынок</td><td>Зачисление д/с</td><td>RUB</td><td>70.00</td><td>0.00</td></tr>
                <tr><td>23.10.2026</td><td>Основной рынок</td><td>Перевод д/с с договора MAIN на договор IIS</td><td>RUB</td><td>100.00</td><td>0.00</td></tr>
              """ else ""}
            </table>
        """.trimIndent()
        val trades = """
            <table>
              <tr><td>Дата заключения</td><td>Дата расчетов</td><td>Время заключения</td><td>Наименование ЦБ</td><td>Код ЦБ</td><td>Валюта</td><td>Вид</td><td>Количество, шт.</td><td>Цена ²</td><td>Сумма</td><td>НКД ⁴</td><td>Комиссия Брокера</td><td>Комиссия Биржи</td><td>Номер сделки</td><td>Комментарий</td><td>Статус сделки ⁵</td></tr>
              <tr><td>1</td></tr>
              <tr><td>30.10.2026</td><td>02.11.2026</td><td>10:54:54</td><td>Тестовая акция</td><td>$tradeCode</td><td>RUB</td><td>Покупка</td><td>10</td><td>$tradePrice</td><td>473.40</td><td>0</td><td>0.28</td><td>0.14</td><td>123456</td><td></td><td>$tradeStatus</td></tr>
            </table>
        """.trimIndent()
        val securities = """
            <table>
              <tr><td>Наименование</td><td>Код</td><td>ISIN ценной бумаги</td><td>Эмитент</td><td>Вид</td><td>Выпуск</td></tr>
              <tr><td>1</td></tr>
              <tr><td>Тестовая акция</td><td>TEST</td><td>RU000A000001</td><td>Эмитент</td><td>Акция</td><td>1</td></tr>
              <tr><td>Т-Технологии</td><td>T</td><td>RU000A000002</td><td>Эмитент</td><td>Акция</td><td>2</td></tr>
              <tr><td>ОФЗ 26228</td><td>SU26228RMFS5</td><td>RU000A100A82</td><td>Минфин</td><td>Облигация</td><td>26228</td></tr>
            </table>
        """.trimIndent()
        // Mirror the observed Sber report sequences: regular brokerage reports include
        // three tax tables before the securities directory; IIS-3 includes a contribution
        // table before holdings and has a shorter tax section.
        val summary = """
            <table>
              <tr><td>Торговая площадка</td><td>Начало периода</td><td>Конец периода</td><td>Изменение за период</td><td>Плановые показатели</td></tr>
              <tr><td>Ценные бумаги</td><td>Денежные средства</td><td>Всего</td></tr>
              <tr><td>Основной рынок</td><td>100</td><td>635.73</td></tr>
            </table>
        """.trimIndent()
        val misc = """<table><tr><td>Описание</td><td>Сумма</td><td>Валюта</td></tr><tr><td>1</td><td>2</td><td>3</td></tr></table>"""
        val iisContribution = """
            <table>
              <tr><td>Год</td><td>Лимит, руб.</td><td>Дата операции</td><td>Сумма, руб.</td><td>Основание операции</td><td>Остаток лимита</td></tr>
              <tr><td>1</td><td>2</td><td>3</td><td>4</td><td>5</td><td>6</td></tr>
              <tr><td></td><td></td><td>23.10.2026</td><td>70.00</td><td>Зачисление д/с на ИИС</td><td></td></tr>
              <tr><td></td><td></td><td>23.10.2026</td><td>70.00</td><td>Зачисление д/с на ИИС</td><td></td></tr>
              <tr><td></td><td></td><td>23.10.2026</td><td>100.00</td><td>Перевод д/с между договорами</td><td></td></tr>
            </table>
        """.trimIndent()
        val tax = """<table><tr><td>Наименование клиента</td><td>Код дохода</td><td>Сумма дохода, руб.</td></tr><tr><td>1</td><td>2</td><td>3</td></tr></table>"""
        val signature = """<table><tr><td>Подпись Руководителя (уполномоченного лица)</td></tr></table>"""
        val orderedTables = if (tableCount == 9) {
            listOf(summary, misc, iisContribution, assets, cash, flows, trades, securities, signature)
        } else {
            listOf(summary, misc, assets, cash, flows, trades, tax, tax, tax, securities, signature)
        }
        val accountId = if (tableCount == 9) "TESTIIS03" else "TESTMAIN01"
        return "<html><head><title>Отчёт брокера\n$accountId</title></head><body>за период с 30.10.2026 по 30.10.2026${orderedTables.joinToString("")}</body></html>"
    }

    @Test
    fun detectsWindows1251BrokerReportFromHtmlMetaCharset() {
        val html = sampleHtml().replace("<head>", "<head><meta charset=\"windows-1251\">")
        val bytes = html.toByteArray(Charset.forName("windows-1251"))

        val report = SberBrokerReportParser.parse(ByteArrayInputStream(bytes))

        assertEquals("TESTMAIN01", report.accountId)
        assertEquals("Тестовая акция", report.assets.single().name)
        assertEquals("Зачисление д/с", report.cashFlows.first().description)
    }

    @Test
    fun parsesBothRegularAndIisReportLayouts() {
        listOf(11, 9).forEach { count ->
            val report = parseWithDiagnostics(sampleHtml(tableCount = count))
            assertEquals(if (count == 9) "TESTIIS03" else "TESTMAIN01", report.accountId)
            assertEquals(428.73, report.openingCash, 0.001)
            assertEquals(635.73, report.closingCash, 0.001)
            assertEquals("TEST", report.assets.single().ticker)
        assertEquals("Акция", report.assets.single().assetType)
            assertEquals(3.0, report.assets.single().closingQuantity, 0.001)
            assertEquals(1, report.trades.size)
            assertEquals(0.42, report.trades.single().totalFee, 0.001)
            assertEquals("SBER:123456", report.trades.single().sourceKey)
        }
    }

    @Test
    fun parsesPlannedOutgoingBalancesAndConcludedUnsettledTrade() {
        val report = parseWithDiagnostics(
            sampleHtml(
                closingQuantity = "7",
                closingValue = "2045.19",
                plannedClosingQuantity = "8",
                plannedClosingCash = "270.42",
                tradeStatus = "ЗО"
            )
        )

        assertEquals(7.0, report.assets.single().closingQuantity, 0.001)
        assertEquals(8.0, report.assets.single().effectiveQuantity, 0.001)
        assertEquals(292.17, report.assets.single().closingPrice, 0.001)
        assertEquals(635.73, report.closingCash, 0.001)
        assertEquals(270.42, report.plannedClosingCash!!, 0.001)
        assertEquals("ЗО", report.trades.single().status)
    }

    @Test
    fun convertsBondMarketAndTradePricesFromPercentToRubles() {
        val html = sampleHtml(
            tradePrice = "92.74",
            tradeCode = "TEST",
            closingQuantity = "1",
            closingValue = "926.97"
        ).replace("<td>Акция</td><td>1</td>", "<td>Государственная облигация</td><td>1</td>")
            .replace("<td>473.40</td>", "<td>927.40</td>")
        val report = parseWithDiagnostics(html)
        assertEquals(926.97, report.assets.single().closingPrice, 0.001)
        assertEquals("Облигация", report.assets.single().assetType)
        assertEquals(92.74, report.trades.single().price, 0.001)
    }

    @Test
    fun mapsIsinInTradeCodeColumnToExchangeTicker() {
        val report = parseWithDiagnostics(sampleHtml(tradeCode = "RU000A100A82"))
        assertEquals("SU26228RMFS5", report.trades.single().ticker)
    }

    @Test
    fun importsIisContributionRegisterAsCashFlows() {
        val report = parseWithDiagnostics(sampleHtml(tableCount = 9, includeMatchingIisRegisterRows = false))
        assertEquals(253.55, report.cashCredits, 0.001)
        assertEquals(33.10, report.cashDebits, 0.001)
        val iisDeposits = report.cashFlows.filter { it.description == "Зачисление д/с на ИИС" }
        val iisTransfer = report.cashFlows.single { it.description == "Перевод д/с между договорами" }

        assertEquals(2, iisDeposits.size)
        assertEquals(setOf(BrokerCashFlowKind.DEPOSIT), iisDeposits.map { it.kind }.toSet())
        assertEquals(listOf(70.0, 70.0), iisDeposits.map { it.credit })
        assertEquals(0.0, iisDeposits.first().debit, 0.001)
        assertNotEquals(iisDeposits[0].rowKey, iisDeposits[1].rowKey)
        assertEquals(true, iisDeposits.all { it.rowKey.startsWith("SBER:IIS:CASH:") })
        assertEquals(BrokerCashFlowKind.INTERNAL_TRANSFER, iisTransfer.kind)
        assertEquals(100.0, iisTransfer.credit, 0.001)
        assertEquals(0.0, iisTransfer.debit, 0.001)
    }

    @Test
    fun deduplicatesIisContributionRegisterAgainstGeneralCashFlowsByDateAmountAndKind() {
        val report = parseWithDiagnostics(sampleHtml(tableCount = 9))

        // The general table describes these as generic deposits/transfers, while the
        // IIS register adds "на ИИС" / "между договорами" to the descriptions.
        assertEquals(423.55, report.cashCredits, 0.001)
        assertEquals(1, report.cashFlows.count { it.description == "Зачисление д/с на ИИС" })
        assertEquals(1, report.cashFlows.count {
            it.date == LocalDate.parse("2026-10-23") && it.kind == BrokerCashFlowKind.INTERNAL_TRANSFER
        })
        assertEquals(0, report.cashFlows.count { it.description == "Перевод д/с между договорами" })
    }

    @Test
    fun parsesPayoutTickersAndStableCashFlowKeys() {
        // Keep IIS-only matching rows out of this regular brokerage-report fixture.
        val report = parseWithDiagnostics(sampleHtml(includeMatchingIisRegisterRows = false))
        val dividend = report.cashFlows.single { it.kind == BrokerCashFlowKind.DIVIDEND }
        val coupon = report.cashFlows.single { it.kind == BrokerCashFlowKind.COUPON }
        assertEquals("T", dividend.ticker)
        assertEquals("SU26228RMFS5", coupon.ticker)
        assertEquals(report.cashFlows.map { it.rowKey }, SberBrokerReportParser.parse(sampleHtml(includeMatchingIisRegisterRows = false)).cashFlows.map { it.rowKey })
        val duplicateDeposits = report.cashFlows.filter { it.kind == BrokerCashFlowKind.DEPOSIT }
        assertEquals(2, duplicateDeposits.size)
        assertNotEquals(duplicateDeposits[0].rowKey, duplicateDeposits[1].rowKey)
    }

    @Test
    fun parsesBondRedemptionAndResolvesItsSecurityTicker() {
        val html = sampleHtml(includeMatchingIisRegisterRows = false)
            .replace("Выплата купонов ОФЗ 26228, номер купона 15", "Погашение номинала ОФЗ 26228")
        val report = parseWithDiagnostics(html)
        val redemption = report.cashFlows.single { it.kind == BrokerCashFlowKind.REDEMPTION }

        assertEquals("SU26228RMFS5", redemption.ticker)
        assertEquals(38.15, redemption.credit, 0.001)
    }

    @Test
    fun classifiesOnlyKnownExternalFlowsAndPayouts() {
        assertEquals(BrokerCashFlowKind.DEPOSIT, classifyBrokerCashFlow("Зачисление д/с"))
        assertEquals(BrokerCashFlowKind.WITHDRAWAL, classifyBrokerCashFlow("Вывод средств"))
        assertEquals(BrokerCashFlowKind.DIVIDEND, classifyBrokerCashFlow("Выплата дивидендов Татнефть. Налог удержан."))
        assertEquals(BrokerCashFlowKind.COUPON, classifyBrokerCashFlow("Выплата купонов ОФЗ 26228"))
        assertEquals(BrokerCashFlowKind.REDEMPTION, classifyBrokerCashFlow("Погашение номинала ОФЗ 26228"))
        assertEquals(BrokerCashFlowKind.AMORTIZATION, classifyBrokerCashFlow("Частичное погашение номинала ОФЗ 26228"))
        assertEquals(BrokerCashFlowKind.INTERNAL_TRANSFER, classifyBrokerCashFlow("Перевод д/с с договора A на договор B"))
        assertEquals(
            BrokerInternalTransfer("403RY0J", "S25F9ZU"),
            parseBrokerInternalTransfer("Перевод д/с с договора 403RY0J, ТП Основной рынок на договор S25F9ZU, ТП Основной рынок")
        )
        assertEquals(null, parseBrokerInternalTransfer("Перевод денежных средств без номеров договоров"))
        assertEquals(BrokerCashFlowKind.IGNORE, classifyBrokerCashFlow("Комиссия Биржи от 01.10.2026"))
        assertEquals(BrokerCashFlowKind.IGNORE, classifyBrokerCashFlow("Сделка от 01.10.2026"))
    }

    @Test
    fun acceptsBlankMarketValueOnlyForZeroQuantityPosition() {
        val report = parseWithDiagnostics(sampleHtml(closingQuantity = "0", closingValue = ""))
        assertEquals(0.0, report.assets.single().closingQuantity, 0.001)
        assertEquals(0.0, report.assets.single().closingValue, 0.001)
        assertParseFails(sampleHtml(closingQuantity = "3", closingValue = ""))
    }

    @Test
    fun rejectsMalformedTradePriceInsteadOfTreatingItAsZero() {
        assertParseFails(sampleHtml(tradePrice = "не число"))
    }

    @Test
    fun rejectsMalformedCashFlowAmountInsteadOfTreatingItAsZero() {
        assertParseFails(sampleHtml(cashCredit = "не число"))
    }

    @Test
    fun rejectsMalformedPositionQuantityInsteadOfTreatingItAsZero() {
        assertParseFails(sampleHtml(closingQuantity = "не число"))
    }


    private fun parseWithDiagnostics(html: String): SberBrokerReport {
        try {
            return SberBrokerReportParser.parse(html)
        } catch (error: Exception) {
            throw AssertionError("SBER_PARSER_FAILURE: ${error.javaClass.simpleName}: ${error.message}", error)
        }
    }

    private fun assertParseFails(html: String) {
        try {
            SberBrokerReportParser.parse(html)
            fail("Ожидалось отклонение некорректного отчёта")
        } catch (expected: IllegalArgumentException) {
            // Expected: malformed numeric data must fail the whole report import.
        } catch (expected: IllegalStateException) {
            // Parser errors may also be surfaced as an explicit state failure.
        }
    }
}
