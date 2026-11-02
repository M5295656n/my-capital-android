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

@Entity(tableName = "operations")
data class OperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: Long?,
    val operationType: String,
    val ticker: String?,
    val quantity: Double,
    val price: Double,
    val commission: Double = 0.0,
    val operationDate: Long = System.currentTimeMillis(),
    val note: String = ""
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
    val sourceKey: String? = null
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
    val totalValue: Double
)
