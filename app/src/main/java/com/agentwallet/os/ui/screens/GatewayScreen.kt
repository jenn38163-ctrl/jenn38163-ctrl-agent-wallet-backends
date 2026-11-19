package com.agentwallet.os.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentwallet.os.data.AgentWalletEntity
import com.agentwallet.os.data.PaymentResult
import com.agentwallet.os.utils.CurrencyUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayScreen(
  wallets: List<AgentWalletEntity>,
  lastPaymentResult: PaymentResult?,
  isLoading: Boolean = false,
  onSimulatePayment: (String, String, Long, String, String) -> Unit,
  onClearResult: () -> Unit
) {
  var selectedWalletId by remember { mutableStateOf(wallets.firstOrNull()?.id ?: "") }
  var vendorName by remember { mutableStateOf("You.com") }
  var amountStr by remember { mutableStateOf("5.00") }
  var protocol by remember { mutableStateOf("HTTP_402") }
  var rail by remember { mutableStateOf("PAYU") }

  LaunchedEffect(wallets) {
    if ((selectedWalletId.isEmpty() || wallets.none { it.id == selectedWalletId }) && wallets.isNotEmpty()) {
      selectedWalletId = wallets.first().id
    }
  }

  val selectedWallet = wallets.find { it.id == selectedWalletId }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("402 Payment Gateway", fontWeight = FontWeight.Bold) },
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
      AnimatedVisibility(visible = isLoading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }

      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
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
                text = "Authorize Payment via Backend Outbox",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = "Payments are atomically verified against PostgreSQL wallet balance and limits, queued in the PayU settlement outbox with an idempotency key, and finalized only on webhook/reconciliation confirmation. Blockchain settlement remains disabled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )

              Text("Select Agent Wallet", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
              if (wallets.isEmpty()) {
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                  Text(
                    text = "No wallets loaded. Create or fund a wallet in the Wallets tab first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                  )
                }
              } else {
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
                        Column(modifier = Modifier.weight(1f)) {
                          Text("${w.name} (${w.currency})", fontWeight = FontWeight.SemiBold)
                          Text(
                            "Balance: ${CurrencyUtils.formatAmount(w.usdcBalance, w.currency)} | Max/req: ${CurrencyUtils.formatAmount(w.perRequestLimit, w.currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                          )
                        }
                        if (isSelected) {
                          Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                      }
                    }
                  }
                }
              }

              Spacer(modifier = Modifier.height(4.dp))

              Text("Vendor Presets", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                FilterChip(
                  selected = vendorName == "You.com",
                  onClick = { vendorName = "You.com"; amountStr = "1.00"; protocol = "MPP" },
                  label = { Text("You.com (1.00)") }
                )
                FilterChip(
                  selected = vendorName == "Polygon.io",
                  onClick = { vendorName = "Polygon.io"; amountStr = "12.50"; protocol = "x402" },
                  label = { Text("Polygon (12.50)") }
                )
                FilterChip(
                  selected = vendorName == "GitHub",
                  onClick = { vendorName = "GitHub"; amountStr = "2.00"; protocol = "HTTP_402" },
                  label = { Text("GitHub (2.00)") }
                )
              }

              OutlinedTextField(
                value = vendorName,
                onValueChange = { vendorName = it },
                label = { Text("Vendor Name") },
                modifier = Modifier.fillMaxWidth().testTag("gateway_vendor_input")
              )

              val selectedCurrency = selectedWallet?.currency ?: "USD"
              OutlinedTextField(
                value = amountStr,
                onValueChange = { amountStr = it },
                label = { Text("Requested Amount ($selectedCurrency)") },
                modifier = Modifier.fillMaxWidth().testTag("gateway_amount_input")
              )

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
              ) {
                OutlinedTextField(
                  value = protocol,
                  onValueChange = { protocol = it },
                  label = { Text("Protocol (HTTP_402/MPP/x402)") },
                  modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                  value = rail,
                  onValueChange = {},
                  readOnly = true,
                  label = { Text("Settlement Rail") },
                  modifier = Modifier.weight(1f)
                )
              }

              val isPayuIncompatible = selectedCurrency.equals("USDC", ignoreCase = true)
              if (isPayuIncompatible) {
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                ) {
                  Text(
                    text = "PayU settles fiat USD/PLN only. Select a USD or PLN wallet (blockchain USDC settlement is disabled).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().padding(10.dp)
                  )
                }
              }

              Button(
                onClick = {
                  val amt = CurrencyUtils.parseCurrencyInput(amountStr)
                  if (selectedWallet != null && amt > 0) {
                    onSimulatePayment(selectedWallet.id, vendorName.trim(), amt, protocol.trim(), rail)
                  }
                },
                enabled = !isLoading && selectedWallet != null && !isPayuIncompatible && CurrencyUtils.parseCurrencyInput(amountStr) > 0,
                modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("trigger_402_btn")
              ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isLoading) "Authorizing..." else "Authorize $selectedCurrency Payment (PAYU)")
              }
            }
          }
        }

        lastPaymentResult?.let { result ->
          item {
            Card(
              modifier = Modifier.fillMaxWidth().testTag("gateway_result_card"),
              colors = CardDefaults.cardColors(
                containerColor = if (result.policyPassed) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
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
                      text = "Backend Status: ${result.status}",
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold,
                      color = if (result.policyPassed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                    )
                  }
                  IconButton(onClick = onClearResult) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                  }
                }

                if (result.receiptHash.isNotBlank()) {
                  Text("Ledger Audit Hash:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
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
                }

                Text("Settlement & Policy Trace:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Text(
                  text = result.details,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }
        }
      }
    }
  }
}
