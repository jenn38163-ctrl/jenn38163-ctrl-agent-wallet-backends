package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agent_wallets")
data class AgentWalletEntity(
  @PrimaryKey val id: String,
  val name: String,
  val organization: String,
  val usdcBalance: Double,
  val monthlyLimit: Double,
  val monthlySpent: Double,
  val perRequestLimit: Double,
  val allowedVendors: String, // comma separated e.g. "You.com,OpenAI,Financial APIs,SaaS Vendors"
  val allowedChains: String, // e.g. "Base,Solana,Tempo"
  val requireApproval: Boolean,
  val didIdentity: String
)
