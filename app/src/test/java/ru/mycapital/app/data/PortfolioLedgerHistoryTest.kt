package ru.mycapital.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class PortfolioLedgerHistoryTest {
    private fun operation(
        id: Long,
        type: String,
        date: LocalDate,
        quantity: Double,
        price: Double,
        commission: Double = 0.0,
        ticker: String? = "SBER"
    ) = OperationEntity(
        id = id,
        assetId = null,
        operationType = type,
        ticker = ticker,
        quantity = quantity,
        price = price,
        commission = commission,
        operationDate = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    )

    @Test
    fun reconstructsDailyCashAndHoldingsWithoutTreatingDepositsAsReturn() {
        val start = LocalDate.parse("2026-09-01")
        val buyDate = LocalDate.parse("2026-09-15")
        val depositDate = LocalDate.parse("2026-09-16")
        val sellDate = LocalDate.parse("2026-09-17")
        val rows = reconstructPortfolioLedger(
            openingCash = 1_000.0,
            periodStart = start,
            throughDate = sellDate,
            operations = listOf(
                operation(1, "Покупка", buyDate, 2.0, 100.0, 1.0),
                operation(2, "Пополнение", depositDate, 1.0, 50.0, ticker = "RUB"),
                operation(3, "Продажа", sellDate, 1.0, 120.0, 1.0)
            )
        )

        assertNotNull(rows)
        assertEquals(3, rows!!.size)
        assertEquals(buyDate, rows[0].date)
        assertEquals(799.0, rows[0].cashBalance, 0.000001)
        assertEquals(2.0, rows[0].quantities["SBER"]!!, 0.000001)
        assertEquals(50.0, rows[1].externalFlow, 0.000001)
        assertEquals(849.0, rows[1].cashBalance, 0.000001)
        assertEquals(1.0, rows[2].quantities["SBER"]!!, 0.000001)
        assertEquals(968.0, rows[2].cashBalance, 0.000001)
    }

    @Test
    fun refusesToInventHistoryWithoutOpeningCashBaseline() {
        val result = reconstructPortfolioLedger(
            openingCash = null,
            periodStart = LocalDate.parse("2026-09-01"),
            operations = emptyList(),
            throughDate = LocalDate.parse("2026-09-30")
        )
        assertNull(result)
    }
}
