package ru.mycapital.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mycapital.app.data.AssetEntity
import ru.mycapital.app.data.MoexDailyClose
import ru.mycapital.app.data.OperationEntity
import java.time.LocalDate
import java.time.ZoneOffset

class PortfolioMarketHistoryTest {
    private fun operation(
        id: Long,
        type: String,
        date: String,
        quantity: Double,
        price: Double,
        commission: Double = 0.0,
        ticker: String = "SBER"
    ) = OperationEntity(
        id = id,
        assetId = 1L,
        operationType = type,
        ticker = ticker,
        quantity = quantity,
        price = price,
        commission = commission,
        operationDate = LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    )

    @Test
    fun chartStartsFromFirstPurchaseWithoutAnyDailySnapshots() = kotlinx.coroutines.runBlocking {
        val firstBuy = LocalDate.parse("2026-01-02")
        val nextDay = LocalDate.parse("2026-01-05")
        val result = buildPortfolioMarketHistory(
            assets = listOf(AssetEntity(
                id = 1L, ticker = "SBER", name = "Сбербанк", assetType = "Акция",
                quantity = 1.0, averagePrice = 101.0, currentPrice = 110.0
            )),
            operations = listOf(
                operation(1, "Пополнение", "2026-01-01", 1.0, 1_000.0, ticker = "RUB"),
                operation(2, "Покупка", "2026-01-02", 1.0, 100.0, 1.0)
            ),
            currentCash = 899.0,
            range = PortfolioHistoryRange.ALL,
            throughDate = nextDay
        ) { _, _, _, _ ->
            listOf(MoexDailyClose(firstBuy, 100.0), MoexDailyClose(nextDay, 110.0))
        }

        assertEquals(firstBuy, result.firstPurchaseDate)
        assertEquals(2, result.points.size)
        assertEquals("2026-01-02", result.points.first().dateKey)
        assertEquals(999.0, result.points.first().totalValue, 0.000001)
        assertEquals(1_000.0, result.points.first().investedCapital, 0.000001)
        assertEquals(899.0, result.points.first().cashValue, 0.000001)
        assertEquals(1.0, result.points.first().quantities["SBER"] ?: 0.0, 0.000001)
        assertEquals(100.0, result.points.first().assetValues["SBER"] ?: 0.0, 0.000001)
        assertEquals(1_009.0, result.points.last().totalValue, 0.000001)
        assertEquals(1.0, result.points.last().quantities["SBER"] ?: 0.0, 0.000001)
        assertEquals(110.0, result.points.last().assetValues["SBER"] ?: 0.0, 0.000001)
        assertTrue(result.unavailableTickers.isEmpty())
    }

    @Test
    fun reverseLedgerRestoresSharesBeforeLaterSaleAndRemovesSaleCashEffect() = kotlinx.coroutines.runBlocking {
        val buyDate = LocalDate.parse("2026-02-02")
        val sellDate = LocalDate.parse("2026-02-03")
        val result = buildPortfolioMarketHistory(
            assets = listOf(AssetEntity(
                id = 1L, ticker = "SBER", name = "Сбербанк", assetType = "Акция",
                quantity = 0.0, averagePrice = 0.0, currentPrice = 110.0
            )),
            operations = listOf(
                operation(1, "Пополнение", "2026-02-01", 1.0, 500.0, ticker = "RUB"),
                operation(2, "Покупка", "2026-02-02", 2.0, 100.0, 1.0),
                operation(3, "Продажа", "2026-02-03", 2.0, 110.0, 1.0)
            ),
            currentCash = 518.0,
            range = PortfolioHistoryRange.ALL,
            throughDate = sellDate
        ) { _, _, _, _ ->
            listOf(MoexDailyClose(buyDate, 100.0), MoexDailyClose(sellDate, 110.0))
        }

        assertEquals(2, result.points.size)
        assertEquals(499.0, result.points[0].totalValue, 0.000001)
        assertEquals(518.0, result.points[1].totalValue, 0.000001)
        assertFalse(result.unavailableTickers.contains("SBER"))
    }
    @Test
    fun includesOfzDirtyRublePriceInPortfolioHistory() = kotlinx.coroutines.runBlocking {
        val buyDate = LocalDate.parse("2026-04-01")
        val result = buildPortfolioMarketHistory(
            assets = listOf(AssetEntity(
                id = 2L, ticker = "SU26238RMFS4", name = "ОФЗ 26238", assetType = "Облигация",
                quantity = 1.0, averagePrice = 950.0, currentPrice = 967.5
            )),
            operations = listOf(
                operation(10, "Пополнение", "2026-03-31", 1.0, 1_000.0, ticker = "RUB"),
                operation(11, "Покупка", "2026-04-01", 1.0, 950.0, 0.5, ticker = "SU26238RMFS4")
            ),
            currentCash = 49.5,
            range = PortfolioHistoryRange.ALL,
            throughDate = buyDate
        ) { _, type, _, _ ->
            assertTrue(type.contains("облига", ignoreCase = true))
            listOf(MoexDailyClose(buyDate, 967.5))
        }

        assertEquals(1, result.points.size)
        assertEquals(1_017.0, result.points.single().totalValue, 0.000001)
        assertTrue(result.unavailableTickers.isEmpty())
    }

    @Test
    fun fullySoldInstrumentRemainsInHistoricalNavWithoutCurrentAssetRow() = kotlinx.coroutines.runBlocking {
        val buyDate = LocalDate.parse("2026-02-02")
        val sellDate = LocalDate.parse("2026-02-03")
        val requestedTickers = mutableListOf<String>()
        val result = buildPortfolioMarketHistory(
            // A fully sold instrument is correctly absent from the current assets table.
            assets = emptyList(),
            operations = listOf(
                operation(1, "Пополнение", "2026-02-01", 1.0, 500.0, ticker = "RUB"),
                operation(2, "Покупка", "2026-02-02", 2.0, 100.0, 1.0),
                operation(3, "Продажа", "2026-02-03", 2.0, 110.0, 1.0)
            ),
            currentCash = 518.0,
            range = PortfolioHistoryRange.ALL,
            throughDate = sellDate
        ) { ticker, type, _, _ ->
            requestedTickers += ticker
            assertFalse(type.contains("облиг", ignoreCase = true))
            listOf(MoexDailyClose(buyDate, 100.0), MoexDailyClose(sellDate, 110.0))
        }

        assertTrue(requestedTickers.contains("SBER"))
        assertEquals(2, result.points.size)
        assertEquals(2.0, result.points[0].quantities["SBER"] ?: 0.0, 0.000001)
        assertEquals(200.0, result.points[0].assetValues["SBER"] ?: 0.0, 0.000001)
        assertEquals(499.0, result.points[0].totalValue, 0.000001)
        assertEquals(0.0, result.points[1].quantities["SBER"] ?: 0.0, 0.000001)
        assertEquals(518.0, result.points[1].cashValue, 0.000001)
        assertEquals(518.0, result.points[1].totalValue, 0.000001)
        assertFalse(result.unavailableTickers.contains("SBER"))
    }

    @Test
    fun tradeDateAndSettlementDateDoNotDoubleCountCashAndHoldings() = kotlinx.coroutines.runBlocking {
        val tradeDate = LocalDate.parse("2026-03-02")
        val settlementDate = LocalDate.parse("2026-03-04")
        val result = buildPortfolioMarketHistory(
            assets = listOf(AssetEntity(
                id = 3L, ticker = "SBER", name = "Сбербанк", assetType = "Акция",
                quantity = 1.0, averagePrice = 100.0, currentPrice = 100.0
            )),
            operations = listOf(
                operation(20, "Пополнение", "2026-03-01", 1.0, 1_000.0, ticker = "RUB"),
                operation(21, "Покупка", "2026-03-02", 1.0, 100.0).copy(
                    settlementDate = settlementDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                )
            ),
            currentCash = 900.0,
            range = PortfolioHistoryRange.ALL,
            throughDate = settlementDate
        ) { _, _, _, _ ->
            listOf(
                MoexDailyClose(tradeDate, 100.0),
                MoexDailyClose(tradeDate.plusDays(1), 100.0),
                MoexDailyClose(settlementDate, 100.0)
            )
        }

        assertEquals(3, result.points.size)
        assertEquals(1_000.0, result.points[0].cashValue, 0.000001)
        assertEquals(1_000.0, result.points[0].totalValue, 0.000001)
        assertEquals(1_000.0, result.points[1].totalValue, 0.000001)
        assertEquals(900.0, result.points[2].cashValue, 0.000001)
        assertEquals(1_000.0, result.points[2].totalValue, 0.000001)
    }


    @Test
    fun timeWeightedReturnNeutralizesDepositsAndWithdrawals() = kotlinx.coroutines.runBlocking {
        val firstDate = LocalDate.parse("2026-05-04")
        val depositDate = LocalDate.parse("2026-05-05")
        val withdrawalDate = LocalDate.parse("2026-05-06")
        val result = buildPortfolioMarketHistory(
            assets = listOf(AssetEntity(
                id = 4L, ticker = "SBER", name = "Сбербанк", assetType = "Акция",
                quantity = 5.0, averagePrice = 100.0, currentPrice = 120.0
            )),
            operations = listOf(
                operation(30, "Пополнение", "2026-05-03", 1.0, 1_000.0, ticker = "RUB"),
                operation(31, "Покупка", "2026-05-04", 5.0, 100.0),
                operation(32, "Пополнение", "2026-05-05", 1.0, 500.0, ticker = "RUB"),
                operation(33, "Вывод", "2026-05-06", 1.0, 200.0, ticker = "RUB")
            ),
            currentCash = 800.0,
            range = PortfolioHistoryRange.ALL,
            throughDate = withdrawalDate
        ) { _, _, _, _ ->
            listOf(
                MoexDailyClose(firstDate, 100.0),
                MoexDailyClose(depositDate, 110.0),
                MoexDailyClose(withdrawalDate, 120.0)
            )
        }

        assertEquals(3, result.points.size)
        assertEquals(1_000.0, result.points[0].totalValue, 0.000001)
        assertEquals(100.0, result.points[0].nav, 0.000001)
        assertEquals(1_500.0, result.points[1].investedCapital, 0.000001)
        assertEquals(1_550.0, result.points[1].totalValue, 0.000001)
        assertEquals(105.0, result.points[1].nav, 0.000001)
        assertEquals(1_300.0, result.points[2].investedCapital, 0.000001)
        assertEquals(1_400.0, result.points[2].totalValue, 0.000001)
        assertEquals(100.0 * 105.0 * 1_600.0 / 1_550.0, result.points[2].nav, 0.000001)
    }

    @Test
    fun dividendsIncreaseNavAsInvestmentIncomeNotExternalContribution() = kotlinx.coroutines.runBlocking {
        val purchaseDate = LocalDate.parse("2026-06-01")
        val dividendDate = LocalDate.parse("2026-06-02")
        val result = buildPortfolioMarketHistory(
            assets = listOf(AssetEntity(
                id = 5L, ticker = "SBER", name = "Сбербанк", assetType = "Акция",
                quantity = 2.0, averagePrice = 100.0, currentPrice = 105.0
            )),
            operations = listOf(
                operation(40, "Пополнение", "2026-05-31", 1.0, 500.0, ticker = "RUB"),
                operation(41, "Покупка", "2026-06-01", 2.0, 100.0),
                operation(42, "Дивиденд", "2026-06-02", 1.0, 20.0)
            ),
            currentCash = 320.0,
            range = PortfolioHistoryRange.ALL,
            throughDate = dividendDate
        ) { _, _, _, _ ->
            listOf(
                MoexDailyClose(purchaseDate, 100.0),
                MoexDailyClose(dividendDate, 105.0)
            )
        }

        assertEquals(2, result.points.size)
        assertEquals(500.0, result.points[0].totalValue, 0.000001)
        assertEquals(520.0, result.points[1].totalValue, 0.000001)
        assertEquals(500.0, result.points[1].investedCapital, 0.000001)
        assertEquals(104.0, result.points[1].nav, 0.000001)
    }

}
