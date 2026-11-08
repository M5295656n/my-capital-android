package ru.mycapital.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AssetEntity::class, OperationEntity::class, PayoutEntity::class, CashBalanceEntity::class, PortfolioSnapshotEntity::class, IndexHistoryEntity::class, BrokerAccountCashEntity::class, BrokerAccountAssetEntity::class, BrokerAccountEntity::class, BrokerCashOpeningEntity::class, PortfolioCompositionSnapshotEntity::class, MarketCalendarItemEntity::class],
    version = 18,
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

        internal val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE operations ADD COLUMN sourceKey TEXT")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_operations_sourceKey ON operations(sourceKey)")
            }
        }

        internal val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS broker_account_cash (accountId TEXT NOT NULL PRIMARY KEY, balance REAL NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS broker_account_assets (accountId TEXT NOT NULL, ticker TEXT NOT NULL, quantity REAL NOT NULL, PRIMARY KEY(accountId, ticker))")
            }
        }

        internal val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE payouts ADD COLUMN reportedNetAmount REAL")
            }
        }

        internal val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS broker_accounts (accountId TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, accountType TEXT NOT NULL)")
                database.execSQL("INSERT OR IGNORE INTO broker_accounts (accountId, displayName, accountType) SELECT accountId, 'Договор ••••' || substr(accountId, -4), 'Не указан' FROM broker_account_cash")
            }
        }

        internal val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS broker_cash_opening (accountId TEXT NOT NULL, periodStart TEXT NOT NULL, openingCash REAL NOT NULL, periodEnd TEXT NOT NULL, importedAt INTEGER NOT NULL, PRIMARY KEY(accountId, periodStart))")
            }
        }

        internal val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Existing manual and legacy imported operations remain unassigned.
                database.execSQL("ALTER TABLE operations ADD COLUMN accountId TEXT")
            }
        }

        internal val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE payouts ADD COLUMN recordDate INTEGER")
                database.execSQL("ALTER TABLE payouts ADD COLUMN sourceName TEXT")
            }
        }

        internal val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS portfolio_composition_snapshots (" +
                        "dateKey TEXT NOT NULL, accountId TEXT NOT NULL, dimension TEXT NOT NULL, " +
                        "category TEXT NOT NULL, value REAL NOT NULL, " +
                        "PRIMARY KEY(dateKey, accountId, dimension, category))"
                )
            }
        }

        internal val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE operations ADD COLUMN settlementDate INTEGER")
            }
        }


        internal val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS market_calendar_items (" +
                        "sourceKey TEXT NOT NULL PRIMARY KEY, itemType TEXT NOT NULL, category TEXT NOT NULL, " +
                        "eventDate INTEGER NOT NULL, publishedAt INTEGER, title TEXT NOT NULL, summary TEXT NOT NULL, " +
                        "sourceName TEXT NOT NULL, sourceUrl TEXT NOT NULL, relatedTickers TEXT NOT NULL, " +
                        "importance TEXT NOT NULL, impactAssessment TEXT NOT NULL, impactHorizon TEXT NOT NULL, " +
                        "confidence TEXT NOT NULL, updatedAt INTEGER NOT NULL)"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_market_calendar_items_eventDate ON market_calendar_items(eventDate)")
            }
        }


        internal val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE market_calendar_items ADD COLUMN issuer TEXT")
                database.execSQL("ALTER TABLE market_calendar_items ADD COLUMN instrumentType TEXT")
                database.execSQL("ALTER TABLE market_calendar_items ADD COLUMN placementVolume TEXT")
                database.execSQL("ALTER TABLE market_calendar_items ADD COLUMN placementPrice TEXT")
                database.execSQL("ALTER TABLE market_calendar_items ADD COLUMN couponRate TEXT")
                database.execSQL("ALTER TABLE market_calendar_items ADD COLUMN applicationDates TEXT")
                database.execSQL("ALTER TABLE market_calendar_items ADD COLUMN tradingStartDate TEXT")
            }
        }

        internal val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE market_calendar_items ADD COLUMN isin TEXT")
                database.execSQL("ALTER TABLE market_calendar_items ADD COLUMN couponAmount TEXT")
                database.execSQL("ALTER TABLE market_calendar_items ADD COLUMN detailsSourceUrl TEXT")
            }
        }

        @Volatile private var INSTANCE: PortfolioDatabase? = null

        @Synchronized
        fun closeForRestore() {
            INSTANCE?.close()
            INSTANCE = null
        }

        fun getInstance(context: Context): PortfolioDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PortfolioDatabase::class.java,
                    "my_capital.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18).build().also { INSTANCE = it }
            }
    }
}
