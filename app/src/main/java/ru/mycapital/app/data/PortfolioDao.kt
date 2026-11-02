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

    @Query("SELECT * FROM assets ORDER BY assetType, ticker")
    suspend fun getAssets(): List<AssetEntity>

    @Query("UPDATE assets SET currentPrice = :price, updatedAt = :updatedAt WHERE id = :assetId")
    suspend fun updateAssetPrice(assetId: Long, price: Double, updatedAt: Long)

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

    @Query("SELECT * FROM payouts WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun getPayoutBySourceKey(sourceKey: String): PayoutEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPayout(payout: PayoutEntity): Long

    @androidx.room.Update
    suspend fun updatePayout(payout: PayoutEntity)

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

    @Query("SELECT * FROM portfolio_snapshots ORDER BY dateKey ASC")
    fun observePortfolioSnapshots(): Flow<List<PortfolioSnapshotEntity>>

    @Query("SELECT * FROM portfolio_snapshots ORDER BY dateKey DESC LIMIT 1")
    suspend fun getLatestPortfolioSnapshot(): PortfolioSnapshotEntity?

    @Query("SELECT * FROM operations WHERE operationDate > :afterTimestamp AND (operationType = 'Пополнение' OR operationType = 'Вывод') ORDER BY operationDate ASC")
    suspend fun getExternalFlowsSince(afterTimestamp: Long): List<OperationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPortfolioSnapshot(snapshot: PortfolioSnapshotEntity): Long

    @Query("SELECT * FROM index_history WHERE indexCode = :indexCode AND dateKey BETWEEN :fromDate AND :tillDate ORDER BY dateKey ASC")
    suspend fun getIndexHistory(indexCode: String, fromDate: String, tillDate: String): List<IndexHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIndexHistory(points: List<IndexHistoryEntity>)
}
