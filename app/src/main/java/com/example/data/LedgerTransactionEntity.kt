package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ledger_transactions")
data class LedgerTransactionEntity(
  @PrimaryKey val id: String,
  val timestamp: Long,
  val walletId: String,
  val agentName: String,
  val vendorName: String,
  val amountUsdc: Long,
  val debitAccountId: String,
  val creditAccountId: String,
  val protocol: String, // MPP, x402, HTTP 402
  val chain: String, // Tempo, Base, Solana
  val status: String, // APPROVED, DENIED, PENDING_APPROVAL
  val receiptHash: String,
  val challengeDetails: String
)
