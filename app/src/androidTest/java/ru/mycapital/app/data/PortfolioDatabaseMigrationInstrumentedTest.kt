package ru.mycapital.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortfolioDatabaseMigrationInstrumentedTest {

    @Test
    fun migration6To7PreservesOperationsAndAddsUniqueImportKey() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-6-to-7-test.db"
        context.deleteDatabase(databaseName)
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()

        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            legacy.execSQL(
                "CREATE TABLE assets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ticker TEXT NOT NULL, name TEXT NOT NULL, assetType TEXT NOT NULL, quantity REAL NOT NULL, averagePrice REAL NOT NULL, currentPrice REAL NOT NULL, currency TEXT NOT NULL, updatedAt INTEGER NOT NULL)"
            )
            legacy.execSQL(
                "CREATE TABLE operations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, assetId INTEGER, operationType TEXT NOT NULL, ticker TEXT, quantity REAL NOT NULL, price REAL NOT NULL, commission REAL NOT NULL, operationDate INTEGER NOT NULL, note TEXT NOT NULL)"
            )
            legacy.execSQL(
                "CREATE TABLE payouts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, assetId INTEGER, ticker TEXT NOT NULL, payoutType TEXT NOT NULL, amountPerUnit REAL NOT NULL, quantity REAL NOT NULL, totalAmount REAL NOT NULL, taxAmount REAL NOT NULL, expectedDate INTEGER, actualDate INTEGER, status TEXT NOT NULL, sourceKey TEXT)"
            )
            legacy.execSQL("CREATE UNIQUE INDEX index_payouts_sourceKey ON payouts(sourceKey)")
            legacy.execSQL(
                "INSERT INTO payouts (id, assetId, ticker, payoutType, amountPerUnit, quantity, totalAmount, taxAmount, expectedDate, actualDate, status, sourceKey) VALUES (9, NULL, 'SBER', 'Дивиденд', 1.0, 2.0, 20.0, 2.0, NULL, 1790000000000, 'RECEIVED', 'LEGACY:PAYOUT')"
            )
            legacy.execSQL("CREATE TABLE cash_balance (id INTEGER NOT NULL PRIMARY KEY, balance REAL NOT NULL)")
            legacy.execSQL(
                "CREATE TABLE portfolio_snapshots (dateKey TEXT NOT NULL PRIMARY KEY, capturedAt INTEGER NOT NULL, assetsValue REAL NOT NULL, cashValue REAL NOT NULL, totalValue REAL NOT NULL, externalFlow REAL NOT NULL DEFAULT 0.0)"
            )
            legacy.execSQL(
                "CREATE TABLE index_history (indexCode TEXT NOT NULL, dateKey TEXT NOT NULL, close REAL NOT NULL, PRIMARY KEY(indexCode, dateKey))"
            )
            legacy.execSQL(
                "INSERT INTO operations (id, assetId, operationType, ticker, quantity, price, commission, operationDate, note) VALUES (7, NULL, 'Покупка', 'SBER', 2.0, 250.0, 0.5, 1790000000000, 'Старая операция')"
            )
            legacy.execSQL("INSERT INTO cash_balance (id, balance) VALUES (1, 123.45)")
            legacy.version = 6
        } finally {
            legacy.close()
        }

        val database = Room.databaseBuilder(context, PortfolioDatabase::class.java, databaseName)
            .addMigrations(PortfolioDatabase.MIGRATION_6_7, PortfolioDatabase.MIGRATION_7_8, PortfolioDatabase.MIGRATION_8_9, PortfolioDatabase.MIGRATION_9_10, PortfolioDatabase.MIGRATION_10_11, PortfolioDatabase.MIGRATION_11_12, PortfolioDatabase.MIGRATION_12_13, PortfolioDatabase.MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()

        try {
            database.openHelper.writableDatabase
            val dao = database.portfolioDao()
            val oldOperation = dao.observeOperations().first()
            assertEquals(1, oldOperation.size)
            assertEquals(7L, oldOperation.single().id)
            assertEquals("Старая операция", oldOperation.single().note)
            assertEquals(null, oldOperation.single().sourceKey)
            assertEquals(null, oldOperation.single().accountId)
            assertEquals(null, oldOperation.single().accountId)
            assertEquals(123.45, dao.getCashBalance()!!, 0.000001)
            assertEquals(0, dao.getBrokerAccountCashAccounts().size)
            assertEquals(0, dao.getBrokerAccountAssets("403RY0J").size)
            val legacyPayout = dao.getPayoutById(9L)
            assertNotNull(legacyPayout)
            assertEquals(20.0, legacyPayout!!.totalAmount, 0.000001)
            assertEquals(null, legacyPayout.reportedNetAmount)

            val imported = OperationEntity(
                assetId = null,
                operationType = "Пополнение",
                ticker = null,
                quantity = 0.0,
                price = 50.0,
                note = "Импорт",
                sourceKey = "SBER:IMPORT:1"
            )
            val firstId = dao.insertImportedOperation(imported)
            val duplicateId = dao.insertImportedOperation(imported.copy(note = "Дубль"))

            assertEquals(true, firstId > 0)
            assertEquals(-1L, duplicateId)
            assertNotNull(dao.getOperationBySourceKey("SBER:IMPORT:1"))
            assertEquals(2, dao.observeOperations().first().size)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
