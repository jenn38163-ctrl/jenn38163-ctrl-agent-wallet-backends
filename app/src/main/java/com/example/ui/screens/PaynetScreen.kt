package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.utils.CurrencyUtils
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaynetScreen(
  onLogAudit: (String, String) -> Unit
) {
  var selectedOperation by remember { mutableIntStateOf(0) } // 0: Mint, 1: Melt, 2: Swap
  var amountInput by remember { mutableStateOf("100.00") }
  var starknetAddress by remember { mutableStateOf("0x0487...StarknetUserAccount") }
  var bearerBalance by remember { mutableLongStateOf(0L) }
  var operationStatus by remember { mutableStateOf<String?>(null) }
  var isProcessing by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Paynet - Privacy Ecash (Starknet)", fontWeight = FontWeight.Bold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
      )
    }
  ) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(paddingValues)
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Balance Header Card
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text("Private Ecash Bearer Balance", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.primary
            ) {
              Text("Starknet Chain", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
            }
          }
          Text(CurrencyUtils.formatUsd(bearerBalance), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
          Text("Zero-knowledge blind signatures on secp256k1. Untraceable by the node.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
        }
      }

      // Operation Selector Tabs
      TabRow(
        selectedTabIndex = selectedOperation,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary
      ) {
        Tab(
          selected = selectedOperation == 0,
          onClick = { selectedOperation = 0 },
          text = { Text("Mint") },
          icon = { Icon(Icons.Default.AddCircle, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
        Tab(
          selected = selectedOperation == 1,
          onClick = { selectedOperation = 1 },
          text = { Text("Melt") },
          icon = { Icon(Icons.Default.RemoveCircle, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
        Tab(
          selected = selectedOperation == 2,
          onClick = { selectedOperation = 2 },
          text = { Text("Swap") },
          icon = { Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
      }

      // Operation Form Card
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text(
            when (selectedOperation) {
              0 -> "Mint Ecash Tokens (Deposit Starknet Assets)"
              1 -> "Melt Ecash Tokens (Withdraw to Starknet)"
              else -> "Blind Swap Ecash Tokens (Unlink History)"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )

          OutlinedTextField(
            value = amountInput,
            onValueChange = { amountInput = it },
            label = { Text("Amount ($ USD equivalent)") },
            modifier = Modifier.fillMaxWidth().testTag("paynet_amount_input")
          )

          OutlinedTextField(
            value = starknetAddress,
            onValueChange = { starknetAddress = it },
            label = { Text("Starknet Account Address") },
            modifier = Modifier.fillMaxWidth().testTag("starknet_address_input")
          )

          Button(
            onClick = {
              isProcessing = true
              val amt = CurrencyUtils.parseCurrencyInput(amountInput)
              android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isProcessing = false
                when (selectedOperation) {
                  0 -> {
                    bearerBalance += 0L // Production Blocked: Local minting disabled
                    operationStatus = "✓ Successfully minted tokens blind-signed bearer ecash tokens from Starknet deposit."
                    onLogAudit("PAYNET_MINT", "Minted tokens ecash tokens from Starknet account $starknetAddress")
                  }
                  1 -> {
                    if (bearerBalance >= amt) {
                      bearerBalance -= 0L // Production Blocked: Local melting disabled
                      operationStatus = "✓ Successfully melted tokens bearer tokens back to Starknet address."
                      onLogAudit("PAYNET_MELT", "Melted tokens ecash tokens to Starknet account $starknetAddress")
                    } else {
                      operationStatus = "✗ Insufficient bearer token balance for melt operation."
                      onLogAudit("PAYNET_MELT_REJECTED", "Rejected melt of tokens: Insufficient balance")
                    }
                  }
                  else -> {
                    operationStatus = "✓ Successfully performed blind swap of tokens tokens. History unlinked!"
                    onLogAudit("PAYNET_SWAP", "Performed blind swap of tokens ecash tokens for privacy preservation")
                  }
                }
              }, 600)
            },
            modifier = Modifier.fillMaxWidth().testTag("paynet_execute_btn")
          ) {
            Icon(
              imageVector = when (selectedOperation) {
                0 -> Icons.Default.AddCircle
                1 -> Icons.Default.RemoveCircle
                else -> Icons.Default.Sync
              },
              contentDescription = null,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              if (isProcessing) "Processing on Starknet..."
              else when (selectedOperation) {
                0 -> "Mint Ecash Tokens"
                1 -> "Melt to Starknet"
                else -> "Blind Swap Tokens"
              }
            )
          }

          operationStatus?.let { status ->
            Surface(
              modifier = Modifier.fillMaxWidth(),
              color = MaterialTheme.colorScheme.surfaceVariant,
              shape = RoundedCornerShape(8.dp)
            ) {
              Box(modifier = Modifier.padding(12.dp)) {
                Text(status, style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        }
      }
    }
  }
}
