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

    @Query("SELECT * FROM assets WHERE UPPER(ticker) = UPPER(:ticker) LIMIT 1")
    suspend fun getAssetByTicker(ticker: String): AssetEntity?

    @Query("SELECT * FROM broker_accounts ORDER BY displayName")
    fun observeBrokerAccounts(): Flow<List<BrokerAccountEntity>>

    @Query("SELECT * FROM broker_account_cash ORDER BY accountId")
    fun observeBrokerAccountCashAccounts(): Flow<List<BrokerAccountCashEntity>>

    @Query("SELECT * FROM broker_account_assets ORDER BY accountId, ticker")
    fun observeAllBrokerAccountAssets(): Flow<List<BrokerAccountAssetEntity>>

    @Query("SELECT * FROM broker_account_assets ORDER BY accountId, ticker")
    suspend fun getAllBrokerAccountAssets(): List<BrokerAccountAssetEntity>

    @Query("SELECT * FROM broker_accounts WHERE accountId = :accountId LIMIT 1")
    suspend fun getBrokerAccount(accountId: String): BrokerAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBrokerAccount(account: BrokerAccountEntity)

    @Query("SELECT * FROM broker_account_cash WHERE accountId = :accountId LIMIT 1")
    suspend fun getBrokerAccountCash(accountId: String): BrokerAccountCashEntity?

    @Query("SELECT * FROM broker_account_cash")
    suspend fun getBrokerAccountCashAccounts(): List<BrokerAccountCashEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBrokerAccountCash(snapshot: BrokerAccountCashEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBrokerCashOpening(opening: BrokerCashOpeningEntity)

    @Query("SELECT * FROM broker_cash_opening WHERE accountId = :accountId ORDER BY periodStart ASC")
    suspend fun getBrokerCashOpenings(accountId: String): List<BrokerCashOpeningEntity>

    @Query("SELECT * FROM broker_account_assets WHERE accountId = :accountId")
    suspend fun getBrokerAccountAssets(accountId: String): List<BrokerAccountAssetEntity>

    @Query("DELETE FROM broker_account_assets WHERE accountId = :accountId")
    suspend fun deleteBrokerAccountAssets(accountId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBrokerAccountAssets(snapshots: List<BrokerAccountAssetEntity>)

    @Query("SELECT * FROM operations ORDER BY operationDate DESC")
    fun observeOperations(): Flow<List<OperationEntity>>

    @Query("SELECT * FROM operations WHERE accountId = :accountId ORDER BY operationDate ASC, id ASC")
    suspend fun getOperationsForAccount(accountId: String): List<OperationEntity>

    @Query("SELECT * FROM operations WHERE accountId IS NULL ORDER BY operationDate ASC, id ASC")
    suspend fun getUnassignedOperations(): List<OperationEntity>

    @Query("SELECT * FROM operations WHERE assetId = :assetId ORDER BY operationDate ASC, id ASC")
    suspend fun getOperationsForAsset(assetId: Long): List<OperationEntity>

    @Insert
    suspend fun insertOperation(operation: OperationEntity): Long

    @Query("SELECT * FROM operations WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun getOperationBySourceKey(sourceKey: String): OperationEntity?

    @Query("UPDATE operations SET settlementDate = :settlementDate WHERE sourceKey = :sourceKey")
    suspend fun updateOperationSettlementDate(sourceKey: String, settlementDate: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImportedOperation(operation: OperationEntity): Long

    @Query("SELECT * FROM market_calendar_items ORDER BY eventDate ASC, publishedAt DESC")
    fun observeMarketCalendarItems(): Flow<List<MarketCalendarItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMarketCalendarItems(items: List<MarketCalendarItemEntity>)

    @Query("DELETE FROM market_calendar_items WHERE updatedAt < :cutoff")
    suspend fun deleteStaleMarketCalendarItems(cutoff: Long)

    @Query("SELECT * FROM payouts ORDER BY COALESCE(expectedDate, actualDate) ASC")
    fun observePayouts(): Flow<List<PayoutEntity>>

    @Query("SELECT * FROM payouts WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun getPayoutBySourceKey(sourceKey: String): PayoutEntity?

    @Query("DELETE FROM payouts WHERE sourceKey LIKE 'LONGSHORT:%' AND UPPER(ticker) = UPPER(:ticker) AND status != 'RECEIVED'")
    suspend fun deleteUnreceivedLongShortDividendEventsForTicker(ticker: String)

    @Query("DELETE FROM payouts WHERE sourceKey LIKE 'MYCAPITAL_HISTORY:%' AND UPPER(ticker) = UPPER(:ticker) AND status != 'RECEIVED'")
    suspend fun deleteUnreceivedMyCapitalHistoryDividendForecastsForTicker(ticker: String)

    @Query("SELECT * FROM payouts WHERE id = :payoutId LIMIT 1")
    suspend fun getPayoutById(payoutId: Long): PayoutEntity?

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

    @Query("SELECT * FROM portfolio_composition_snapshots ORDER BY dateKey ASC, accountId, dimension, category")
    fun observePortfolioCompositionSnapshots(): Flow<List<PortfolioCompositionSnapshotEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPortfolioCompositionSnapshots(snapshots: List<PortfolioCompositionSnapshotEntity>)

    @Query("SELECT * FROM portfolio_snapshots ORDER BY dateKey DESC LIMIT 1")
    suspend fun getLatestPortfolioSnapshot(): PortfolioSnapshotEntity?

    @Query("SELECT * FROM portfolio_snapshots WHERE dateKey < :dateKey ORDER BY dateKey DESC LIMIT 1")
    suspend fun getLatestPortfolioSnapshotBefore(dateKey: String): PortfolioSnapshotEntity?

    @Query("SELECT * FROM operations WHERE operationDate > :afterTimestamp AND (operationType = 'Пополнение' OR operationType = 'Вывод') ORDER BY operationDate ASC")
    suspend fun getExternalFlowsSince(afterTimestamp: Long): List<OperationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPortfolioSnapshot(snapshot: PortfolioSnapshotEntity): Long

    @Query("SELECT * FROM index_history WHERE indexCode = :indexCode AND dateKey BETWEEN :fromDate AND :tillDate ORDER BY dateKey ASC")
    suspend fun getIndexHistory(indexCode: String, fromDate: String, tillDate: String): List<IndexHistoryEntity>

    @Query("SELECT MAX(dateKey) FROM index_history WHERE indexCode = :indexCode")
    suspend fun getLatestIndexHistoryDate(indexCode: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIndexHistory(points: List<IndexHistoryEntity>)
}
