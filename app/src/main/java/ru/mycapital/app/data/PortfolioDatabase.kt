package ru.mycapital.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AssetEntity::class, OperationEntity::class, PayoutEntity::class, CashBalanceEntity::class],
    version = 3,
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

        @Volatile private var INSTANCE: PortfolioDatabase? = null

        fun getInstance(context: Context): PortfolioDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PortfolioDatabase::class.java,
                    "my_capital.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
    }
}
