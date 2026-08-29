package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {
  @Query("SELECT * FROM agent_wallets")
  fun getAllWallets(): Flow<List<AgentWalletEntity>>

  @Query("SELECT * FROM agent_wallets WHERE id = :walletId")
  suspend fun getWalletById(walletId: String): AgentWalletEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertWallet(wallet: AgentWalletEntity)

  @Query("UPDATE agent_wallets SET usdcBalance = usdcBalance - :amount, monthlySpent = monthlySpent + :amount WHERE id = :walletId")
  suspend fun deductBalance(walletId: String, amount: Double)

  @Query("UPDATE agent_wallets SET usdcBalance = usdcBalance + :amount WHERE id = :walletId")
  suspend fun addBalance(walletId: String, amount: Double)

  @Query("SELECT * FROM ledger_transactions ORDER BY timestamp DESC")
  fun getAllTransactions(): Flow<List<LedgerTransactionEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTransaction(transaction: LedgerTransactionEntity)

  @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
  fun getAllAuditLogs(): Flow<List<AuditLogEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAuditLog(log: AuditLogEntity)

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
