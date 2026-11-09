package com.agentwallet.os.firebase

data class UserProfile(
  val uid: String = "",
  val email: String = "",
  val displayName: String = "",
  val organization: String = "AI Corp Enterprises",
  val role: String = "SecOps Administrator",
  val createdAt: Long = System.currentTimeMillis()
)

data class FirestoreVaultAsset(
  val id: String = "",
  val name: String = "",
  val assetType: String = "",
  val keySize: Int = 2048,
  val encrypted: Boolean = false,
  val commonName: String = "",
  val organization: String = "",
  val organizationalUnit: String = "",
  val country: String = "",
  val state: String = "",
  val locality: String = "",
  val email: String = "",
  val validityDays: Int = 0,
  val createdAt: Long = System.currentTimeMillis(),
  val userId: String = "",
  val projectId: String = "wallet-54645"
)

data class FirestoreWallet(
  val id: String = "",
  val name: String = "",
  val organization: String = "",
  val didIdentity: String = "",
  val usdcBalance: Long = 0,
  val monthlyLimit: Long = 0,
  val monthlySpent: Long = 0,
  val perRequestLimit: Long = 0,
  val allowedVendors: String = "",
  val allowedChains: String = "",
  val requireApproval: Boolean = false,
  val createdAt: Long = System.currentTimeMillis(),
  val userId: String = ""
)

data class FirestoreAuditLog(
  val id: String = "",
  val timestamp: Long = System.currentTimeMillis(),
  val eventType: String = "",
  val description: String = "",
  val actor: String = "",
  val cryptographicHash: String = "",
  val userId: String = ""
)

data class FirestoreTransaction(
  val id: String = "",
  val timestamp: Long = System.currentTimeMillis(),
  val walletId: String = "",
  val agentName: String = "",
  val vendorName: String = "",
  val amountUsdc: Long = 0,
  val debitAccountId: String = "",
  val creditAccountId: String = "",
  val protocol: String = "",
  val chain: String = "",
  val status: String = "",
  val receiptHash: String = "",
  val challengeDetails: String = "",
  val userId: String = ""
)
