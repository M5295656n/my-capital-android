package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.fail

class SberBrokerReportParserTest {
    @Test
    fun parsesCashFlowsTradesAndClosingPositions() {
        val tables = listOf(
            "<tr><td>Обзор</td></tr>",
            "<tr><td>Описание</td></tr>",
            "<tr><td>Площадка: Фондовый рынок</td></tr><tr><td>Тестовая акция</td><td>RU000A000001</td><td>RUB</td><td>2</td><td></td><td>10</td><td>20</td><td>0</td><td>3</td><td></td><td>11</td><td>33</td><td>0</td><td>0</td><td>13</td><td>0</td><td>0</td><td>3</td>",
            "<tr><td>Торговый счет, Основной рынок</td><td>RUB</td><td></td><td>428.73</td><td>207.00</td><td>635.73</td>",
            "<tr><td>30.10.2026</td><td>Основной рынок</td><td>Зачисление д/с</td><td>RUB</td><td>107.00</td><td>0.00</td><td></td><td></td></tr><tr><td>30.10.2026</td><td>Основной рынок</td><td>Зачисление д/с</td><td>RUB</td><td>100.00</td><td>0.00</td>",
            "<tr><td>30.10.2026</td><td>02.11.2026</td><td>10:54:54</td><td>Тестовая акция</td><td>TEST</td><td>RUB</td><td>Покупка</td><td>10</td><td>47.34</td><td>473.40</td><td>0</td><td>0.28</td><td>0.14</td><td>123456</td><td></td><td>О</td>",
            "<tr><td>Налог</td></tr>",
            "<tr><td>Налог</td></tr>",
            "<tr><td>Налог</td></tr>",
            "<tr><td>Тестовая акция</td><td>TEST</td><td>RU000A000001</td><td>Эмитент</td><td>Акция</td><td>1</td>"
        ).joinToString("") { "<table>$it</table>" }
        val html = "<html><body>за период с 30.10.2026 по 30.10.2026$tables</body></html>"

        val report = SberBrokerReportParser.parse(html)

        assertEquals(428.73, report.openingCash, 0.001)
        assertEquals(635.73, report.closingCash, 0.001)
        assertEquals(207.0, report.cashCredits, 0.001)
        assertEquals(2, report.cashFlows.size)
        assertEquals("TEST", report.assets.single().ticker)
        assertEquals(3.0, report.assets.single().closingQuantity, 0.001)
        assertEquals(1, report.trades.size)
        assertEquals(0.42, report.trades.single().totalFee, 0.001)
        assertEquals("SBER:123456", report.trades.single().sourceKey)
    }
}
