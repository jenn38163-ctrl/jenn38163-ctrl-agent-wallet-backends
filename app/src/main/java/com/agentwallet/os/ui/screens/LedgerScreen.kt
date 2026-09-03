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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentwallet.os.data.AuditLogEntity
import com.agentwallet.os.data.LedgerTransactionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
  transactions: List<LedgerTransactionEntity>,
  auditLogs: List<AuditLogEntity>
) {
  var selectedTab by remember { mutableIntStateOf(0) }
  val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Double-Entry Ledger & Audit Trail", fontWeight = FontWeight.Bold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
      )
    }
  ) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(paddingValues)
    ) {
      TabRow(
        selectedTabIndex = selectedTab,
        containerColor = MaterialTheme.colorScheme.surface
      ) {
        Tab(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          text = { Text("Ledger Transactions (${transactions.size})") }
        )
        Tab(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          text = { Text("Cryptographic Audit Trail (${auditLogs.size})") }
        )
      }

      if (selectedTab == 0) {
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          if (transactions.isEmpty()) {
            item {
              Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No ledger transactions recorded.", color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          } else {
            items(transactions) { tx ->
              Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
              ) {
                Column(
                  modifier = Modifier.fillMaxWidth().padding(16.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(tx.id, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(dateFormat.format(Date(tx.timestamp)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }

                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Column {
                      Text("${tx.agentName} → ${tx.vendorName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                      Text("${tx.protocol} on ${tx.chain} • Status: ${tx.status}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                      text = "${com.agentwallet.os.utils.CurrencyUtils.formatUsd(tx.amountUsdc)} USDC",
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold,
                      color = if (tx.status == "APPROVED") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                    )
                  }

                  Spacer(modifier = Modifier.height(4.dp))
                  
                  // Double Entry Details
                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                  ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                      Text("Double-Entry Ledger Lines:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                      Spacer(modifier = Modifier.height(2.dp))
                      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("DR  ${tx.debitAccountId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${com.agentwallet.os.utils.CurrencyUtils.formatUsd(tx.amountUsdc)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                      }
                      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("CR  ${tx.creditAccountId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${com.agentwallet.os.utils.CurrencyUtils.formatUsd(tx.amountUsdc)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                      }
                    }
                  }

                  Spacer(modifier = Modifier.height(4.dp))

                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                  ) {
                    Row(
                      modifier = Modifier.fillMaxWidth().padding(8.dp),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Text(
                        text = "Receipt: ${tx.receiptHash}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                      )
                      if (tx.status == "APPROVED") {
                        var passSaved by remember { mutableStateOf(false) }
                        val context = androidx.compose.ui.platform.LocalContext.current
                        TextButton(
                          onClick = {
                            passSaved = true
                            try {
                              val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://pay.google.com/gp/v/save/AGENT_RECEIPT_${tx.receiptHash}")
                              )
                              context.startActivity(intent)
                            } catch (e: Exception) {}
                          }
                        ) {
                          Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(14.dp))
                          Spacer(modifier = Modifier.width(4.dp))
                          Text(if (passSaved) "Saved" else "Google Wallet", style = MaterialTheme.typography.bodySmall)
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          if (auditLogs.isEmpty()) {
            item {
              Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No audit logs recorded.", color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          } else {
            items(auditLogs) { log ->
              Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
              ) {
                Column(
                  modifier = Modifier.fillMaxWidth().padding(16.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                    ) {
                      Text(
                        text = log.eventType,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                      )
                    }
                    Text(dateFormat.format(Date(log.timestamp)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                  Text(log.description, style = MaterialTheme.typography.bodyMedium)
                  Text("Actor DID: ${log.actor}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  Text("Hash: ${log.cryptographicHash}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
              }
            }
          }
        }
      }
    }
  }
}
