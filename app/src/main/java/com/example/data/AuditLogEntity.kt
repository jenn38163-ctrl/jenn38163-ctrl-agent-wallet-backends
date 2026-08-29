package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
  @PrimaryKey val id: String,
  val timestamp: Long,
  val eventType: String,
  val description: String,
  val actor: String,
  val cryptographicHash: String
)
