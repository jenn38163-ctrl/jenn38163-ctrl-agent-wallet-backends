package com.agentwallet.os.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentwallet.os.data.AuditLogEntity
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditLogsScreen(
  auditLogs: List<AuditLogEntity>,
  onTriggerTestAudit: (String, String) -> Unit
) {
  var filterType by remember { mutableStateOf("ALL") }
  val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

  val filteredLogs = remember(auditLogs, filterType) {
    if (filterType == "ALL") auditLogs
    else auditLogs.filter { it.eventType.contains(filterType, ignoreCase = true) }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Audit Trail & Security Logs", fontWeight = FontWeight.Bold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
      )
    }
  ) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(paddingValues)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Summary & Simulation Card
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text("Cryptographic Audit Trail", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
              Text("Immutable event records for payment operations, rejections, & key rotations", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.primaryContainer
            ) {
              Text(
                "${auditLogs.size} Events",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
              )
            }
          }

          // Simulation buttons for requested audit logs
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedButton(
              onClick = { onTriggerTestAudit("DEPOSIT_REJECTED", "Deposit of $5,000.00 rejected due to velocity limit violation and unverified AML source.") },
              modifier = Modifier.weight(1f).testTag("sim_deposit_rejected_btn")
            ) {
              Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
              Spacer(modifier = Modifier.width(4.dp))
              Text("Sim. Deposit Rejected", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
              onClick = { onTriggerTestAudit("DENIED_PAYMENT", "Denied Google Pay token decryption & settlement due to expired cryptographic nonce.") },
              modifier = Modifier.weight(1f).testTag("sim_denied_payment_btn")
            ) {
              Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
              Spacer(modifier = Modifier.width(4.dp))
              Text("Sim. Denied Payment", style = MaterialTheme.typography.bodySmall)
            }
          }
        }
      }

      // Filter Chips
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        listOf("ALL", "REJECTED", "DENIED", "KEY_ROTATION", "PAYMENT").forEach { type ->
          FilterChip(
            selected = filterType == type,
            onClick = { filterType = type },
            label = { Text(type) }
          )
        }
      }

      // Scrollable Audit Log List View
      if (filteredLogs.isEmpty()) {
        Box(
          modifier = Modifier.fillMaxWidth().weight(1f),
          contentAlignment = Alignment.Center
        ) {
          Text("No audit logs found for filter '$filterType'.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxWidth().weight(1f),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          items(filteredLogs) { log ->
            val isError = log.eventType.contains("REJECTED") || log.eventType.contains("DENIED")
            val containerColor = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface

            Card(
              modifier = Modifier.fillMaxWidth(),
              colors = CardDefaults.cardColors(containerColor = containerColor)
            ) {
              Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                      modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                          when {
                            log.eventType.contains("REJECTED") -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                            log.eventType.contains("DENIED") -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                            log.eventType.contains("KEY") -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                          }
                        ),
                      contentAlignment = Alignment.Center
                    ) {
                      Icon(
                        imageVector = when {
                          log.eventType.contains("REJECTED") -> Icons.Default.Block
                          log.eventType.contains("DENIED") -> Icons.Default.Warning
                          log.eventType.contains("KEY") -> Icons.Default.VpnKey
                          else -> Icons.Default.VerifiedUser
                        },
                        contentDescription = null,
                        tint = when {
                          log.eventType.contains("REJECTED") -> MaterialTheme.colorScheme.error
                          log.eventType.contains("DENIED") -> MaterialTheme.colorScheme.tertiary
                          log.eventType.contains("KEY") -> MaterialTheme.colorScheme.secondary
                          else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(16.dp)
                      )
                    }
                    Text(log.eventType, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                  }
                  Text(dateFormat.format(Date(log.timestamp)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text(log.description, style = MaterialTheme.typography.bodyMedium)

                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text("Actor: ${log.actor}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  Text("Hash: ${log.cryptographicHash.take(16)}...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
              }
            }
          }
        }
      }
    }
  }
}
