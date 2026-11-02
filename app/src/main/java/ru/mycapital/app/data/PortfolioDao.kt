package ru.mycapital.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PortfolioDao {
    @Query("SELECT * FROM assets ORDER BY assetType, ticker")
    fun observeAssets(): Flow<List<AssetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAsset(asset: AssetEntity): Long

    @Delete
    suspend fun deleteAsset(asset: AssetEntity)

    @androidx.room.Update
    suspend fun updateAsset(asset: AssetEntity)

    @Query("SELECT * FROM assets WHERE id = :assetId LIMIT 1")
    suspend fun getAsset(assetId: Long): AssetEntity?

    @Query("SELECT * FROM operations ORDER BY operationDate DESC")
    fun observeOperations(): Flow<List<OperationEntity>>

    @Insert
    suspend fun insertOperation(operation: OperationEntity): Long

    @Query("SELECT * FROM payouts ORDER BY COALESCE(expectedDate, actualDate) ASC")
    fun observePayouts(): Flow<List<PayoutEntity>>

    @Insert
    suspend fun insertPayout(payout: PayoutEntity): Long

    @Query("SELECT COALESCE(SUM(quantity * currentPrice), 0) FROM assets")
    fun observePortfolioValue(): Flow<Double>

    @Query("SELECT balance FROM cash_balance WHERE id = 1")
    fun observeCashBalance(): Flow<Double?>

    @Query("SELECT balance FROM cash_balance WHERE id = 1")
    suspend fun getCashBalance(): Double?

    @androidx.room.Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureCashBalance(balance: CashBalanceEntity)

    @Query("UPDATE cash_balance SET balance = :balance WHERE id = 1")
    suspend fun updateCashBalance(balance: Double)
}
