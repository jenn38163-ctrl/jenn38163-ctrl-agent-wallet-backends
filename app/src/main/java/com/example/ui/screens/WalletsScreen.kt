package com.example.ui.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.AgentWalletEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletsScreen(
  wallets: List<AgentWalletEntity>,
  onCreateWallet: (String, String, Double, Double, Double, String, String, Boolean) -> Unit,
  onDeposit: (String, Double) -> Unit
) {
  var showCreateDialog by remember { mutableStateOf(false) }
  var depositWalletId by remember { mutableStateOf<String?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Sovereign Agent Wallets", fontWeight = FontWeight.Bold) },
        actions = {
          IconButton(onClick = { showCreateDialog = true }, modifier = Modifier.testTag("add_wallet_button")) {
            Icon(Icons.Default.Add, contentDescription = "Add Wallet")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
      )
    },
    floatingActionButton = {
      FloatingActionButton(
        onClick = { showCreateDialog = true },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.testTag("fab_add_wallet")
      ) {
        Icon(Icons.Default.Add, contentDescription = "Create Agent Wallet")
      }
    }
  ) { paddingValues ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(paddingValues)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      item {
        Text(
          text = "Organizations govern agent financial autonomy without exposing private keys. Each wallet enforces strict spending policies, per-request limits, and vendor allowlists.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      items(wallets) { wallet ->
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                  modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                  contentAlignment = Alignment.Center
                ) {
                  Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Column {
                  Text(wallet.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                  Text(wallet.organization, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
              Column(horizontalAlignment = Alignment.End) {
                Text(
                  text = "$${String.format("%.2f", wallet.usdcBalance)}",
                  style = MaterialTheme.typography.titleLarge,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.secondary
                )
                Text("USDC Balance", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // Limits grid
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column {
                Text("Monthly Budget", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$${wallet.monthlySpent.toInt()} / $${wallet.monthlyLimit.toInt()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
              }
              Column {
                Text("Max / Request", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$${wallet.perRequestLimit}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
              }
              Column {
                Text("Approval Req.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (wallet.requireApproval) "Yes (> $10)" else "Autonomous", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
              }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Allowed Vendors:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(wallet.allowedVendors, style = MaterialTheme.typography.bodySmall)
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Allowed Chains:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(wallet.allowedChains, style = MaterialTheme.typography.bodySmall)
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.End
            ) {
              OutlinedButton(
                onClick = { depositWalletId = wallet.id },
                modifier = Modifier.testTag("deposit_btn_${wallet.id}")
              ) {
                Icon(Icons.Default.AddCard, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Deposit USDC")
              }
            }
          }
        }
      }
    }
  }

  // Create Wallet Dialog
  if (showCreateDialog) {
    var name by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("AI Corp Enterprises") }
    var balance by remember { mutableStateOf("100.0") }
    var monthlyLimit by remember { mutableStateOf("250.0") }
    var perReq by remember { mutableStateOf("10.0") }
    var vendors by remember { mutableStateOf("You.com,OpenAI,APIs") }
    var chains by remember { mutableStateOf("Tempo,Base,Solana") }
    var requireApproval by remember { mutableStateOf(false) }

    AlertDialog(
      onDismissRequest = { showCreateDialog = false },
      title = { Text("Create Agent Wallet OS Identity") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Agent Name") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = org, onValueChange = { org = it }, label = { Text("Organization") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = balance, onValueChange = { balance = it }, label = { Text("Initial USDC Balance") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = monthlyLimit, onValueChange = { monthlyLimit = it }, label = { Text("Monthly Limit (USDC)") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = perReq, onValueChange = { perReq = it }, label = { Text("Per-Request Limit (USDC)") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = vendors, onValueChange = { vendors = it }, label = { Text("Allowed Vendors (comma sep)") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = chains, onValueChange = { chains = it }, label = { Text("Allowed Chains (Base, Solana, Tempo)") }, modifier = Modifier.fillMaxWidth())
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (name.isNotBlank()) {
              onCreateWallet(
                name,
                org,
                balance.toDoubleOrNull() ?: 50.0,
                monthlyLimit.toDoubleOrNull() ?: 100.0,
                perReq.toDoubleOrNull() ?: 5.0,
                vendors,
                chains,
                requireApproval
              )
              showCreateDialog = false
            }
          },
          modifier = Modifier.testTag("confirm_create_wallet")
        ) {
          Text("Create Wallet")
        }
      },
      dismissButton = {
        TextButton(onClick = { showCreateDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }

  // Deposit Dialog
  depositWalletId?.let { targetId ->
    var amountStr by remember { mutableStateOf("50.0") }
    AlertDialog(
      onDismissRequest = { depositWalletId = null },
      title = { Text("Deposit USDC into Sovereign Wallet") },
      text = {
        OutlinedTextField(
          value = amountStr,
          onValueChange = { amountStr = it },
          label = { Text("Amount (USDC)") },
          modifier = Modifier.fillMaxWidth()
        )
      },
      confirmButton = {
        Button(
          onClick = {
            val amt = amountStr.toDoubleOrNull() ?: 0.0
            if (amt > 0) {
              onDeposit(targetId, amt)
              depositWalletId = null
            }
          }
        ) {
          Text("Confirm Deposit")
        }
      },
      dismissButton = {
        TextButton(onClick = { depositWalletId = null }) {
          Text("Cancel")
        }
      }
    )
  }
}
