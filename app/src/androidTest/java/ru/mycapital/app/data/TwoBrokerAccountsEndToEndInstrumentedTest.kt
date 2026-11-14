package ru.mycapital.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end Room regression for the two-account Sber import workflow.
 *
 * The fixtures deliberately use synthetic account IDs, securities, amounts and deal numbers.
 * Their shape mirrors the two real report layouts: 12 + 5 positions, 47 + 6 executed trades,
 * one rejected main-account trade, three dividends, one coupon and three internal transfers.
 * Private broker statements are not stored in the repository.
 */
@RunWith(AndroidJUnit4::class)
class TwoBrokerAccountsEndToEndInstrumentedTest {
    private lateinit var database: PortfolioDatabase
    private lateinit var dao: PortfolioDao
    private lateinit var repository: PortfolioRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PortfolioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.portfolioDao()
        repository = PortfolioRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun importsBothAccountsAndCanRepeatWithoutChangingCashPositionsOperationsOrPayouts() = runBlocking {
        val mainAssets = (1..12).map { index ->
            BrokerReportAsset(
                name = "Main security $index",
                ticker = "MAIN%02d".format(index),
                isin = "RU000A%06d".format(index),
                openingQuantity = 0.0,
                closingQuantity = index.toDouble(),
                closingPrice = 10.0 + index,
                closingValue = index * (10.0 + index),
                assetType = "Акция"
            )
        }
        val iisAssets = (1..5).map { index ->
            BrokerReportAsset(
                name = "IIS security $index",
                ticker = "IIS%02d".format(index),
                isin = "RU000B%06d".format(index),
                openingQuantity = 0.0,
                closingQuantity = (index * 2).toDouble(),
                closingPrice = 20.0 + index,
                closingValue = index * 2.0 * (20.0 + index)
            )
        }
        // Start with an empty portfolio: snapshot reconciliation must create assets.
        dao.ensureCashBalance(CashBalanceEntity(balance = 0.0))

        val mainReport = report(
            accountId = "MAIN-TEST",
            closingCash = 500.0,
            assets = mainAssets,
            cashFlows = buildList {
                repeat(43) { index ->
                    add(flow(
                        date = LocalDate.parse("2026-08-01").plusDays(index.toLong()),
                        description = "Зачисление д/с",
                        credit = 10.0 + index,
                        key = "MAIN:DEPOSIT:$index"
                    ))
                }
                add(flow(LocalDate.parse("2026-10-20"), "Выплата дивидендов Main security 1", 1.10, "MAIN:DIV:1", "MAIN01"))
                add(flow(LocalDate.parse("2026-10-21"), "Выплата дивидендов Main security 2", 2.20, "MAIN:DIV:2", "MAIN02"))
                add(flow(LocalDate.parse("2026-10-22"), "Выплата дивидендов Main security 3", 3.30, "MAIN:DIV:3", "MAIN03"))
                repeat(3) { index ->
                    add(BrokerReportCashFlow(
                        date = LocalDate.parse("2026-10-14").plusDays(index.toLong()),
                        description = "Перевод д/с с договора MAIN-TEST на договор IIS-TEST",
                        credit = 0.0,
                        debit = (index + 1) * 10.0,
                        rowKey = "MAIN:TRANSFER:$index"
                    ))
                }
            },
            trades = buildList {
                repeat(47) { index -> add(trade("MAIN-DEAL-$index", "ЗИ", "MAIN01")) }
                add(trade("MAIN-DEAL-REJECTED", "ОТКЛОНЕНА", "MAIN02"))
            }
        )
        val iisReport = report(
            accountId = "IIS-TEST",
            closingCash = 100.0,
            assets = iisAssets,
            cashFlows = buildList {
                repeat(4) { index ->
                    add(flow(
                        date = LocalDate.parse("2026-10-12").plusDays(index.toLong()),
                        description = "Зачисление д/с",
                        credit = 20.0 + index,
                        key = "IIS:DEPOSIT:$index"
                    ))
                }
                add(flow(LocalDate.parse("2026-10-14"), "Выплата купонов IIS security 1", 4.40, "IIS:COUPON:1", "IIS01"))
                repeat(3) { index ->
                    add(BrokerReportCashFlow(
                        date = LocalDate.parse("2026-10-14").plusDays(index.toLong()),
                        description = "Перевод д/с с договора MAIN-TEST на договор IIS-TEST",
                        credit = (index + 1) * 10.0,
                        debit = 0.0,
                        rowKey = "IIS:TRANSFER:$index"
                    ))
                }
            },
            trades = (1..6).map { index -> trade("IIS-DEAL-$index", "ЗИ", "IIS01") }
        )

        // Snapshot reconciliation is absolute and account-scoped.
        val mainSnapshot = repository.reconcileBrokerReportSnapshot(mainReport)
        val iisSnapshot = repository.reconcileBrokerReportSnapshot(iisReport)
        assertEquals(0, mainSnapshot.unmatchedReportAssets)
        assertEquals(0, iisSnapshot.unmatchedReportAssets)
        assertEquals(12, mainSnapshot.matchedAssets)
        assertEquals(5, iisSnapshot.matchedAssets)
        assertEquals(600.0, dao.getCashBalance()!!, 0.000001)
        assertEquals(17, dao.getAssets().size)
        assertTrue(dao.getAssets().all { it.averagePrice == 0.0 })
        assertTrue(dao.getAssets().filter { it.ticker.startsWith("MAIN") }.all { it.assetType == "Акция" })
        mainAssets.forEach { expected ->
            assertEquals(expected.closingQuantity, dao.getAssetByTicker(expected.ticker)!!.quantity, 0.000001)
        }
        iisAssets.forEach { expected ->
            assertEquals(expected.closingQuantity, dao.getAssetByTicker(expected.ticker)!!.quantity, 0.000001)
        }

        // Import history from both accounts. Cash and positions must remain untouched.
        val mainFirst = repository.importBrokerReportHistory(mainReport)
        val iisFirst = repository.importBrokerReportHistory(iisReport)
        assertEquals(96, mainFirst.imported) // 43 deposits + 3 dividends + 47 trades + 3 account-side internal transfers
        assertEquals(3, mainFirst.payoutsImported)
        assertEquals(1, mainFirst.unsettledSkipped)
        assertEquals(14, iisFirst.imported) // 4 deposits + 1 coupon + 6 trades + 3 account-side internal transfers
        assertEquals(1, iisFirst.payoutsImported)
        assertEquals(0, iisFirst.unsettledSkipped)

        val cashAfterFirstImport = dao.getCashBalance()!!
        val quantitiesAfterFirstImport = dao.getAssets().associate { it.ticker to it.quantity }
        assertEquals(600.0, cashAfterFirstImport, 0.000001)

        // Re-import history and snapshots in the opposite order: nothing is counted twice.
        val mainSecond = repository.importBrokerReportHistory(mainReport)
        val iisSecond = repository.importBrokerReportHistory(iisReport)
        repository.reconcileBrokerReportSnapshot(iisReport)
        repository.reconcileBrokerReportSnapshot(mainReport)

        assertEquals(0, mainSecond.imported)
        assertEquals(96, mainSecond.duplicates)
        assertEquals(0, mainSecond.payoutsImported)
        assertEquals(1, mainSecond.unsettledSkipped)
        assertEquals(0, iisSecond.imported)
        assertEquals(14, iisSecond.duplicates)
        assertEquals(0, iisSecond.payoutsImported)

        assertEquals(cashAfterFirstImport, dao.getCashBalance()!!, 0.000001)
        assertEquals(quantitiesAfterFirstImport, dao.getAssets().associate { it.ticker to it.quantity })
        assertEquals(110, dao.observeOperations().first().size)
        assertEquals(4, dao.observePayouts().first().size)
        assertEquals(4, dao.observePayouts().first().count { it.status == "RECEIVED" })
        assertEquals(
            setOf(1.10, 2.20, 3.30, 4.40),
            dao.observePayouts().first().map { it.reportedNetAmount!! }.toSet()
        )
        assertEquals(6, dao.observeOperations().first().count { it.operationType.startsWith("Внутренний перевод") })
        assertTrue(dao.observeOperations().first().filter { it.operationType.startsWith("Внутренний перевод") }.all { it.accountId != null })
        val finalOperations = dao.observeOperations().first()
        assertEquals(53, finalOperations.count { it.operationType == "Покупка" })
        assertFalse(finalOperations.any { it.note.contains("MAIN-DEAL-REJECTED") })
    }

    private fun flow(
        date: LocalDate,
        description: String,
        credit: Double,
        key: String,
        ticker: String? = null
    ) = BrokerReportCashFlow(
        date = date,
        description = description,
        credit = credit,
        debit = 0.0,
        rowKey = key,
        ticker = ticker
    )

    private fun report(
        accountId: String,
        closingCash: Double,
        assets: List<BrokerReportAsset>,
        cashFlows: List<BrokerReportCashFlow>,
        trades: List<BrokerReportTrade>
    ) = SberBrokerReport(
        accountId = accountId,
        periodStart = LocalDate.parse("2026-08-01"),
        periodEnd = LocalDate.parse("2026-10-30"),
        openingCash = 0.0,
        cashCredits = cashFlows.sumOf { it.credit },
        cashDebits = cashFlows.sumOf { it.debit },
        closingCash = closingCash,
        assets = assets,
        cashFlows = cashFlows,
        trades = trades
    )

    private fun trade(number: String, status: String, ticker: String) = BrokerReportTrade(
        tradeDate = LocalDate.parse("2026-10-20"),
        settlementDate = LocalDate.parse("2026-10-21"),
        time = "10:00:00",
        name = ticker,
        ticker = ticker,
        side = "Покупка",
        quantity = 1.0,
        price = 10.0,
        grossAmount = 10.0,
        brokerFee = 0.1,
        exchangeFee = 0.1,
        dealNumber = number,
        status = status
    )
}
