package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.example.data.PaymentResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayScreen(
  wallets: List<AgentWalletEntity>,
  lastPaymentResult: PaymentResult?,
  onSimulatePayment: (String, String, Double, String, String) -> Unit,
  onClearResult: () -> Unit
) {
  var selectedWalletId by remember { mutableStateOf(wallets.firstOrNull()?.id ?: "") }
  var vendorName by remember { mutableStateOf("You.com") }
  var amountStr by remember { mutableStateOf("0.01") }
  var protocol by remember { mutableStateOf("MPP") }
  var chain by remember { mutableStateOf("Tempo") }

  // Update selected wallet if empty and wallets loaded
  LaunchedEffect(wallets) {
    if (selectedWalletId.isEmpty() && wallets.isNotEmpty()) {
      selectedWalletId = wallets.first().id
    }
  }

  val selectedWallet = wallets.find { it.id == selectedWalletId }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("402 Payment Protocol Gateway", fontWeight = FontWeight.Bold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
      )
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
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Text(
              text = "Live 402 Challenge Simulator (You.com Pattern)",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "When an AI agent makes a paid API request, the server responds with HTTP 402 Payment Required. The Gateway evaluates policies (vendor allowlist, per-request limit, balance) and issues a cryptographic payment credential.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Select Agent Wallet
            Text("Select Sovereign Agent Wallet", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              wallets.forEach { w ->
                val isSelected = w.id == selectedWalletId
                Surface(
                  onClick = { selectedWalletId = w.id },
                  shape = RoundedCornerShape(8.dp),
                  color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                  border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Column {
                      Text(w.name, fontWeight = FontWeight.SemiBold)
                      Text("Balance: $${w.usdcBalance} | Limit: $${w.perRequestLimit}/req", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isSelected) {
                      Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                  }
                }
              }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Vendor Presets
            Text("Target Vendor Preset", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              FilterChip(
                selected = vendorName == "You.com",
                onClick = { vendorName = "You.com"; amountStr = "0.01"; protocol = "MPP"; chain = "Tempo" },
                label = { Text("You.com ($0.01)") }
              )
              FilterChip(
                selected = vendorName == "Polygon.io",
                onClick = { vendorName = "Polygon.io"; amountStr = "12.50"; protocol = "x402"; chain = "Base" },
                label = { Text("Polygon API ($12.50)") }
              )
              FilterChip(
                selected = vendorName == "GitHub",
                onClick = { vendorName = "GitHub"; amountStr = "2.00"; protocol = "HTTP 402"; chain = "Solana" },
                label = { Text("GitHub ($2.00)") }
              )
            }

            OutlinedTextField(
              value = vendorName,
              onValueChange = { vendorName = it },
              label = { Text("Vendor Name") },
              modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
              value = amountStr,
              onValueChange = { amountStr = it },
              label = { Text("Requested Amount (USDC)") },
              modifier = Modifier.fillMaxWidth()
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              OutlinedTextField(
                value = protocol,
                onValueChange = { protocol = it },
                label = { Text("Protocol (MPP/x402)") },
                modifier = Modifier.weight(1f)
              )
              OutlinedTextField(
                value = chain,
                onValueChange = { chain = it },
                label = { Text("Chain (Tempo/Base)") },
                modifier = Modifier.weight(1f)
              )
            }

            Button(
              onClick = {
                val amt = amountStr.toDoubleOrNull() ?: 0.01
                if (selectedWallet != null) {
                  onSimulatePayment(selectedWallet.id, vendorName, amt, protocol, chain)
                }
              },
              modifier = Modifier.fillMaxWidth().testTag("trigger_402_btn")
            ) {
              Icon(Icons.Default.PlayArrow, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
              Text("Trigger 402 Challenge & Authorize")
            }
          }
        }
      }

      // Result Section
      lastPaymentResult?.let { result ->
        item {
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
              containerColor = if (result.policyPassed) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
            )
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Icon(
                    imageVector = if (result.policyPassed) Icons.Default.Verified else Icons.Default.GppBad,
                    contentDescription = null,
                    tint = if (result.policyPassed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                  )
                  Text(
                    text = "Policy Evaluation: ${result.status}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (result.policyPassed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                  )
                }
                IconButton(onClick = onClearResult) {
                  Icon(Icons.Default.Close, contentDescription = "Clear")
                }
              }

              Text("Receipt Hash / Audit Trail:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
              ) {
                Text(
                  text = result.receiptHash,
                  modifier = Modifier.padding(8.dp),
                  style = MaterialTheme.typography.bodySmall
                )
              }

              Text("Challenge & Policy Trace:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
              Text(
                text = result.details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )

              if (result.policyPassed) {
                var passSaved by remember { mutableStateOf(false) }
                val context = androidx.compose.ui.platform.LocalContext.current
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                  onClick = {
                    passSaved = true
                    try {
                      val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://pay.google.com/gp/v/save/AGENT_RECEIPT_${result.receiptHash}")
                      )
                      context.startActivity(intent)
                    } catch (e: Exception) {
                      // Fallback if browser intent fails
                    }
                  },
                  colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(if (passSaved) "✓ Saved to Google Wallet" else "Add Receipt to Google Wallet")
                }
              }
            }
          }
        }
      }
    }
  }
}
