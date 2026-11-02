package ru.mycapital.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AssetEntity::class, OperationEntity::class, PayoutEntity::class, CashBalanceEntity::class, PortfolioSnapshotEntity::class, IndexHistoryEntity::class],
    version = 6,
    exportSchema = false
)
abstract class PortfolioDatabase : RoomDatabase() {
    abstract fun portfolioDao(): PortfolioDao

    companion object {
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS cash_balance (id INTEGER NOT NULL PRIMARY KEY, balance REAL NOT NULL)")
                database.execSQL("INSERT OR IGNORE INTO cash_balance (id, balance) VALUES (1, 0.0)")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE payouts ADD COLUMN sourceKey TEXT")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_payouts_sourceKey ON payouts(sourceKey)")
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS portfolio_snapshots (dateKey TEXT NOT NULL PRIMARY KEY, capturedAt INTEGER NOT NULL, assetsValue REAL NOT NULL, cashValue REAL NOT NULL, totalValue REAL NOT NULL)")
            }
        }

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE portfolio_snapshots ADD COLUMN externalFlow REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS index_history (indexCode TEXT NOT NULL, dateKey TEXT NOT NULL, close REAL NOT NULL, PRIMARY KEY(indexCode, dateKey))")
            }
        }

        @Volatile private var INSTANCE: PortfolioDatabase? = null

        fun getInstance(context: Context): PortfolioDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PortfolioDatabase::class.java,
                    "my_capital.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { INSTANCE = it }
            }
    }
}
