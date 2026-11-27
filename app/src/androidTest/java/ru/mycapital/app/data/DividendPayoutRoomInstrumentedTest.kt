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

    @Test
    fun sevenReported2027TickerMonthsHaveNoDuplicateForecastRowsInRoom() = runBlocking {
        val dao = database.portfolioDao()
        val cases = listOf(
            "NVTK" to "2027-04-12",
            "YDEX" to "2027-04-18",
            "IRAO" to "2027-06-15",
            "MOEX" to "2027-07-10",
            "MDMG" to "2027-07-14",
            "SBER" to "2027-07-20",
            "MDMG" to "2027-10-19"
        )

        fun millis(date: String) = java.time.LocalDate.parse(date)
            .atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

        val assets = cases.map { (ticker, _) ->
            ticker to dao.insertAsset(
                AssetEntity(
                    ticker = ticker,
                    name = ticker,
                    assetType = "Акция",
                    quantity = 1.0,
                    averagePrice = 100.0,
                    currentPrice = 100.0
                )
            )
        }.toMap()

        for ((ticker, date) in cases) {
            val providerEvent = DividendCalendarEvent(
                ticker = ticker,
                amountPerUnit = 10.0,
                status = "FORECAST",
                recordDate = millis(date),
                paymentDate = millis(date),
                sourceKey = "LONGSHORT:$ticker:$date"
            )
            val providerEvents = removeSupersededDividendForecasts(listOf(providerEvent))
            val historyForecasts = removeHistoryForecastsSupersededByProvider(
                ticker = ticker,
                forecasts = listOf(
                    HistoricalDividendForecast(
                        ticker = ticker,
                        amountPerShare = 9.5,
                        recordDate = java.time.LocalDate.parse(date),
                        sourceKey = "MYCAPITAL_HISTORY:$ticker:$date"
                    )
                ),
                events = providerEvents
            )

            database.withTransaction {
                providerEvents.forEach { event ->
                    dao.insertPayout(
                        PayoutEntity(
                            assetId = assets.getValue(ticker),
                            ticker = ticker,
                            payoutType = "Дивиденд",
                            amountPerUnit = event.amountPerUnit,
                            quantity = 1.0,
                            totalAmount = event.amountPerUnit,
                            taxAmount = 0.0,
                            expectedDate = event.paymentDate ?: event.recordDate,
                            actualDate = null,
                            status = event.status,
                            sourceKey = event.sourceKey,
                            recordDate = event.recordDate,
                            sourceName = "Long/Short"
                        )
                    )
                }
                historyForecasts.forEach { forecast ->
                    val timestamp = millis(forecast.recordDate.toString())
                    dao.insertPayout(
                        PayoutEntity(
                            assetId = assets.getValue(ticker),
                            ticker = ticker,
                            payoutType = "Дивиденд",
                            amountPerUnit = forecast.amountPerShare,
                            quantity = 1.0,
                            totalAmount = forecast.amountPerShare,
                            taxAmount = 0.0,
                            expectedDate = timestamp,
                            actualDate = null,
                            status = "FORECAST",
                            sourceKey = forecast.sourceKey,
                            recordDate = timestamp,
                            sourceName = "MyCapital · история дивидендов"
                        )
                    )
                }
            }
        }

        val rows = dao.observePayouts().first()
        cases.forEach { (ticker, date) ->
            val month = java.time.YearMonth.from(java.time.LocalDate.parse(date))
            val monthRows = rows.filter { payout ->
                payout.ticker == ticker &&
                    payout.payoutType.equals("Дивиденд", true) &&
                    payout.status == "FORECAST" &&
                    payout.expectedDate?.let {
                        java.time.YearMonth.from(
                            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        ) == month
                    } == true
            }
            assertEquals("$ticker $month must have exactly one forecast row in Room", 1, monthRows.size)
        }
    }

}