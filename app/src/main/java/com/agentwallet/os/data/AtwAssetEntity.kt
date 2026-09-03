package com.agentwallet.os.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "atw_assets")
data class AtwAssetEntity(
  @PrimaryKey val id: String,
  val name: String,
  val assetType: String, // "PRIVATE_KEY", "CSR", "SELF_SIGNED_CERT", "KEY_CSR_BUNDLE"
  val keySize: Int, // 2048 or 4096
  val encrypted: Boolean,
  val commonName: String,
  val organization: String,
  val organizationalUnit: String,
  val country: String,
  val state: String,
  val locality: String,
  val email: String,
  val validityDays: Int,
  val pemData: String,
  val createdAt: Long
)
