package ru.mycapital.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DividendPayoutRoomInstrumentedTest {
    private lateinit var database: PortfolioDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PortfolioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun historyRefreshReplacesOnlyUnreceivedHistoryForecastsAndRemainsObservable() = runBlocking {
        val dao = database.portfolioDao()
        val assetId = dao.insertAsset(
            AssetEntity(
                ticker = "TATN",
                name = "Татнефть",
                assetType = "Акция",
                quantity = 10.0,
                averagePrice = 500.0,
                currentPrice = 600.0
            )
        )
        fun payout(key: String, status: String, amount: Double) = PayoutEntity(
            assetId = assetId,
            ticker = "TATN",
            payoutType = "Дивиденд",
            amountPerUnit = amount,
            quantity = 10.0,
            totalAmount = amount * 10.0,
            expectedDate = 1_800_000_000_000L,
            status = status,
            sourceKey = key,
            sourceName = "MyCapital · история дивидендов"
        )

        dao.insertPayout(payout("MYCAPITAL_HISTORY:TATN:old", "FORECAST", 10.0))
        dao.insertPayout(payout("MYCAPITAL_HISTORY:TATN:received", "RECEIVED", 8.0))
        dao.insertPayout(payout("LONGSHORT:TATN:confirmed", "ANNOUNCED", 12.0))

        database.withTransaction {
            dao.deleteUnreceivedMyCapitalHistoryDividendForecastsForTicker("TATN")
            dao.insertPayout(payout("MYCAPITAL_HISTORY:TATN:2027-01-10", "FORECAST", 11.0))
            dao.insertPayout(payout("MYCAPITAL_HISTORY:TATN:2027-07-10", "FORECAST", 12.0))
        }

        val rows = dao.observePayouts().first().filter { it.ticker == "TATN" }
        assertEquals(
            setOf(
                "MYCAPITAL_HISTORY:TATN:received",
                "LONGSHORT:TATN:confirmed",
                "MYCAPITAL_HISTORY:TATN:2027-01-10",
                "MYCAPITAL_HISTORY:TATN:2027-07-10"
            ),
            rows.mapNotNull { it.sourceKey }.toSet()
        )
        assertEquals(4, rows.size)
        assertEquals(8.0, rows.first { it.status == "RECEIVED" }.amountPerUnit, 0.000001)
    }
}
