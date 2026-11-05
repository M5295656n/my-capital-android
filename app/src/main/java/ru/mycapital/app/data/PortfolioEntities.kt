package ru.mycapital.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(tableName = "assets")
data class AssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ticker: String,
    val name: String,
    val assetType: String,
    val quantity: Double,
    val averagePrice: Double,
    val currentPrice: Double,
    val currency: String = "RUB",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "operations", indices = [Index(value = ["sourceKey"], unique = true)])
data class OperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: Long?,
    val operationType: String,
    val ticker: String?,
    val quantity: Double,
    val price: Double,
    val commission: Double = 0.0,
    val operationDate: Long = System.currentTimeMillis(),
    val note: String = "",
    val sourceKey: String? = null
)

@Entity(tableName = "payouts", indices = [Index(value = ["sourceKey"], unique = true)])
data class PayoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: Long?,
    val ticker: String,
    val payoutType: String,
    val amountPerUnit: Double,
    val quantity: Double,
    val totalAmount: Double,
    val taxAmount: Double = 0.0,
    val expectedDate: Long? = null,
    val actualDate: Long? = null,
    val status: String = "EXPECTED",
    val sourceKey: String? = null,
    // Net amount explicitly credited in a broker report when gross/tax split is unavailable.
    val reportedNetAmount: Double? = null
)

@Entity(tableName = "cash_balance")
data class CashBalanceEntity(
    @PrimaryKey val id: Int = 1,
    val balance: Double = 0.0
)

@Entity(tableName = "portfolio_snapshots")
data class PortfolioSnapshotEntity(
    @PrimaryKey val dateKey: String,
    val capturedAt: Long,
    val assetsValue: Double,
    val cashValue: Double,
    val totalValue: Double,
    val externalFlow: Double = 0.0
)


@Entity(tableName = "broker_account_cash")
data class BrokerAccountCashEntity(
    @PrimaryKey val accountId: String,
    val balance: Double
)

@Entity(tableName = "broker_account_assets", primaryKeys = ["accountId", "ticker"])
data class BrokerAccountAssetEntity(
    val accountId: String,
    val ticker: String,
    val quantity: Double
)


@Entity(tableName = "broker_accounts")
data class BrokerAccountEntity(
    @PrimaryKey val accountId: String,
    val displayName: String,
    val accountType: String = "Не указан"
)

@Entity(tableName = "broker_cash_opening", primaryKeys = ["accountId", "periodStart"])
data class BrokerCashOpeningEntity(
    val accountId: String,
    val periodStart: String,
    val openingCash: Double,
    val periodEnd: String,
    val importedAt: Long = System.currentTimeMillis()
)
