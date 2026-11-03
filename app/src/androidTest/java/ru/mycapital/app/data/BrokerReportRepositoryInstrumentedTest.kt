package ru.mycapital.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrokerReportRepositoryInstrumentedTest {
    private lateinit var database: PortfolioDatabase
    private lateinit var repository: PortfolioRepository
    private lateinit var dao: PortfolioDao

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
    fun historyImportIsIdempotentAndDoesNotChangeCashOrPositions() = runBlocking {
        val assetId = dao.insertAsset(
            AssetEntity(ticker = "ABC", name = "ABC", assetType = "Акция",
                quantity = 3.0, averagePrice = 10.0, currentPrice = 11.0)
        )
        dao.ensureCashBalance(CashBalanceEntity(balance = 100.0))
        val report = report(
            accountId = "403RY0J",
            cashFlows = listOf(
                BrokerReportCashFlow(LocalDate.parse("2026-10-30"), "Зачисление д/с", 50.0, 0.0, "CASH:1")
            ),
            trades = listOf(
                trade("101", "И"),
                trade("102", "ЗО")
            )
        )

        val first = repository.importBrokerReportHistory(report)
        val second = repository.importBrokerReportHistory(report)

        assertEquals(2, first.imported)
        assertEquals(0, first.duplicates)
        assertEquals(1, first.unsettledSkipped)
        assertEquals(0, second.imported)
        assertEquals(2, second.duplicates)
        assertEquals(1, second.unsettledSkipped)
        assertEquals(100.0, dao.getCashBalance()!!, 0.000001)
        assertEquals(3.0, dao.getAsset(assetId)!!.quantity, 0.000001)
        assertEquals(2, dao.observeOperations().first().size)
        assertNotNull(dao.getOperationBySourceKey("SBER:403RY0J:CASH:1"))
        assertNotNull(dao.getOperationBySourceKey("SBER:403RY0J:TRADE:101"))
    }


    @Test
    fun payoutImportsUseCreditedNetAmountsAndIgnoreInternalTransfers() = runBlocking {
        dao.ensureCashBalance(CashBalanceEntity(balance = 250.0))
        val report = report(
            cashFlows = listOf(
                BrokerReportCashFlow(LocalDate.parse("2026-10-20"),
                    "Выплата дивидендов Т-Технологии. Налог удержан.", 8.40, 0.0, "DIV:1", "T"),
                BrokerReportCashFlow(LocalDate.parse("2026-10-14"),
                    "Выплата купонов ОФЗ 26228, номер купона 15", 38.15, 0.0, "COUPON:1", "SU26228RMFS9"),
                BrokerReportCashFlow(LocalDate.parse("2026-10-14"),
                    "Перевод д/с с договора 403RY0J на договор S25F9ZU", 0.0, 800.0, "TRANSFER:1")
            )
        )

        val first = repository.importBrokerReportHistory(report)
        val second = repository.importBrokerReportHistory(report)
        val operations = dao.observeOperations().first()

        assertEquals(2, first.imported)
        assertEquals(0, first.duplicates)
        assertEquals(0, second.imported)
        assertEquals(2, second.duplicates)
        assertEquals(250.0, dao.getCashBalance()!!, 0.000001)
        assertEquals(2, operations.size)
        assertEquals(setOf("Дивиденд", "Купон"), operations.map { it.operationType }.toSet())
        assertEquals(8.40, operations.single { it.operationType == "Дивиденд" }.price, 0.000001)
        assertEquals(38.15, operations.single { it.operationType == "Купон" }.price, 0.000001)
        assertEquals("T", operations.single { it.operationType == "Дивиденд" }.ticker)
        assertEquals("SU26228RMFS9", operations.single { it.operationType == "Купон" }.ticker)
        assertEquals(null, dao.getOperationBySourceKey("TRANSFER:1"))
    }


    @Test
    fun identicalOperationsFromDifferentBrokerageAccountsAreImportedSeparately() = runBlocking {
        val sameCashFlow = BrokerReportCashFlow(
            LocalDate.parse("2026-10-30"), "Зачисление д/с", 50.0, 0.0, "CASH:SAME"
        )
        val mainAccount = report(accountId = "403RY0J", cashFlows = listOf(sameCashFlow))
        val iisAccount = report(accountId = "S25F9ZU", cashFlows = listOf(sameCashFlow))

        val mainResult = repository.importBrokerReportHistory(mainAccount)
        val iisResult = repository.importBrokerReportHistory(iisAccount)
        val repeatedIisResult = repository.importBrokerReportHistory(iisAccount)

        assertEquals(1, mainResult.imported)
        assertEquals(1, iisResult.imported)
        assertEquals(1, repeatedIisResult.duplicates)
        assertEquals(2, dao.observeOperations().first().size)
        assertNotNull(dao.getOperationBySourceKey("SBER:403RY0J:CASH:SAME"))
        assertNotNull(dao.getOperationBySourceKey("SBER:S25F9ZU:CASH:SAME"))
    }

    @Test
    fun legacyUnscopedImportKeyPreventsReimportAfterUpgrade() = runBlocking {
        dao.insertOperation(
            OperationEntity(
                assetId = null, operationType = "Пополнение", ticker = "RUB",
                quantity = 1.0, price = 50.0, sourceKey = "LEGACY:CASH:1"
            )
        )
        val result = repository.importBrokerReportHistory(
            report(cashFlows = listOf(
                BrokerReportCashFlow(LocalDate.parse("2026-10-30"),
                    "Зачисление д/с", 50.0, 0.0, "LEGACY:CASH:1")
            ))
        )

        assertEquals(0, result.imported)
        assertEquals(1, result.duplicates)
        assertEquals(1, dao.observeOperations().first().size)
    }

    @Test
    fun snapshotReconciliationSetsAbsoluteCashAndOnlyUpdatesMatchedAssets() = runBlocking {
        val assetId = dao.insertAsset(
            AssetEntity(ticker = "ABC", name = "ABC", assetType = "Акция",
                quantity = 2.0, averagePrice = 10.0, currentPrice = 11.0)
        )
        dao.ensureCashBalance(CashBalanceEntity(balance = 5.0))
        val report = report(
            closingCash = 42.0,
            assets = listOf(
                BrokerReportAsset("ABC", "ABC", "RU000A", 2.0, 4.0, 12.0, 48.0),
                BrokerReportAsset("Unknown", "XYZ", "RU000B", 0.0, 1.0, 5.0, 5.0)
            )
        )

        val result = repository.reconcileBrokerReportSnapshot(report)
        val updated = dao.getAsset(assetId)!!

        assertEquals(1, result.matchedAssets)
        assertEquals(1, result.unmatchedReportAssets)
        assertEquals(5.0, result.previousCash, 0.000001)
        assertEquals(42.0, dao.getCashBalance()!!, 0.000001)
        assertEquals(4.0, updated.quantity, 0.000001)
        assertEquals(12.0, updated.currentPrice, 0.000001)
        assertEquals(10.0, updated.averagePrice, 0.000001)
        assertEquals(null, dao.getAssetByTicker("XYZ"))
    }

    @Test
    fun snapshotReconciliationAggregatesCashAndPositionsAcrossBrokerAccounts() = runBlocking {
        val assetId = dao.insertAsset(
            AssetEntity(ticker = "ABC", name = "ABC", assetType = "Акция",
                quantity = 99.0, averagePrice = 10.0, currentPrice = 11.0)
        )

        val mainResult = repository.reconcileBrokerReportSnapshot(
            report(
                accountId = "403RY0J",
                closingCash = 42.0,
                assets = listOf(BrokerReportAsset("ABC", "ABC", "RU000A", 0.0, 4.0, 12.0, 48.0))
            )
        )
        assertEquals(42.0, mainResult.resultingCash, 0.000001)
        assertEquals(4.0, dao.getAsset(assetId)!!.quantity, 0.000001)

        val iisResult = repository.reconcileBrokerReportSnapshot(
            report(
                accountId = "S25F9ZU",
                closingCash = 9.63,
                assets = listOf(BrokerReportAsset("ABC", "ABC", "RU000A", 0.0, 3.0, 12.0, 36.0))
            )
        )
        assertEquals(51.63, iisResult.reportCash, 0.000001)
        assertEquals(51.63, dao.getCashBalance()!!, 0.000001)
        assertEquals(7.0, dao.getAsset(assetId)!!.quantity, 0.000001)

        val updatedMain = repository.reconcileBrokerReportSnapshot(
            report(
                accountId = "403RY0J",
                closingCash = 50.0,
                assets = listOf(BrokerReportAsset("ABC", "ABC", "RU000A", 0.0, 5.0, 13.0, 65.0))
            )
        )
        assertEquals(59.63, updatedMain.reportCash, 0.000001)
        assertEquals(59.63, dao.getCashBalance()!!, 0.000001)
        assertEquals(8.0, dao.getAsset(assetId)!!.quantity, 0.000001)
        assertEquals(13.0, dao.getAsset(assetId)!!.currentPrice, 0.000001)
    }

    private fun report(
        accountId: String = "403RY0J",
        closingCash: Double = 100.0,
        assets: List<BrokerReportAsset> = emptyList(),
        cashFlows: List<BrokerReportCashFlow> = emptyList(),
        trades: List<BrokerReportTrade> = emptyList()
    ) = SberBrokerReport(
        accountId = accountId,
        periodStart = LocalDate.parse("2026-10-01"),
        periodEnd = LocalDate.parse("2026-10-31"),
        openingCash = 50.0,
        cashCredits = cashFlows.sumOf { it.credit },
        cashDebits = cashFlows.sumOf { it.debit },
        closingCash = closingCash,
        assets = assets,
        cashFlows = cashFlows,
        trades = trades
    )

    private fun trade(number: String, status: String) = BrokerReportTrade(
        tradeDate = LocalDate.parse("2026-10-30"),
        settlementDate = LocalDate.parse("2026-11-02"),
        time = "10:54:54",
        name = "ABC",
        ticker = "ABC",
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

