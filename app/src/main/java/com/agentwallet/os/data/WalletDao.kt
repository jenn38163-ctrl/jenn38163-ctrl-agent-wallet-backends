package com.agentwallet.os.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {
  @Query("SELECT * FROM agent_wallets")
  fun getAllWallets(): Flow<List<AgentWalletEntity>>

  @Query("SELECT * FROM agent_wallets WHERE id = :walletId")
  suspend fun getWalletById(walletId: String): AgentWalletEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertWallet(wallet: AgentWalletEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertWallets(wallets: List<AgentWalletEntity>)

  @Query("DELETE FROM agent_wallets")
  suspend fun deleteAllWallets()

  @Transaction
  suspend fun replaceWallets(wallets: List<AgentWalletEntity>) {
    deleteAllWallets()
    if (wallets.isNotEmpty()) insertWallets(wallets)
  }

  @Query("SELECT * FROM ledger_transactions ORDER BY timestamp DESC")
  fun getAllTransactions(): Flow<List<LedgerTransactionEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTransaction(transaction: LedgerTransactionEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTransactions(transactions: List<LedgerTransactionEntity>)

  @Query("DELETE FROM ledger_transactions")
  suspend fun deleteAllTransactions()

  @Transaction
  suspend fun replaceTransactions(transactions: List<LedgerTransactionEntity>) {
    deleteAllTransactions()
    if (transactions.isNotEmpty()) insertTransactions(transactions)
  }

  @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
  fun getAllAuditLogs(): Flow<List<AuditLogEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAuditLog(log: AuditLogEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAuditLogs(logs: List<AuditLogEntity>)

  @Query("DELETE FROM audit_logs")
  suspend fun deleteAllAuditLogs()

  @Transaction
  suspend fun replaceAuditLogs(logs: List<AuditLogEntity>) {
    deleteAllAuditLogs()
    if (logs.isNotEmpty()) insertAuditLogs(logs)
  }

  @Query("SELECT COUNT(*) FROM agent_wallets")
  suspend fun getWalletCount(): Int

  @Query("SELECT * FROM atw_assets ORDER BY createdAt DESC")
  fun getAllAtwAssets(): Flow<List<AtwAssetEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAtwAsset(asset: AtwAssetEntity)

  @Query("DELETE FROM atw_assets WHERE id = :assetId")
  suspend fun deleteAtwAsset(assetId: String)

  @Query("SELECT COUNT(*) FROM atw_assets")
  suspend fun getAssetCount(): Int
}
