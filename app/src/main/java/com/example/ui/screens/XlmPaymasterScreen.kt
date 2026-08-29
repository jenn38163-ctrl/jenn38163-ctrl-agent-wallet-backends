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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XlmPaymasterScreen(
  onLogAudit: (String, String) -> Unit
) {
  var horizonUrl by remember { mutableStateOf("https://horizon-testnet.stellar.org") }
  var networkPassphrase by remember { mutableStateOf("Test SDF Network ; September 2015") }
  var baseFee by remember { mutableStateOf("100") }
  var feeMultiplier by remember { mutableStateOf("2.0") }
  var feePayerSecret by remember { mutableStateOf("SA3V5KX2...TESTNET_SECRET_KEY") }

  var sampleXdr by remember {
    mutableStateOf("AAAAAgAAAAA3W0jW...[Signed Transaction XDR]...")
  }

  var feeBumpResult by remember { mutableStateOf<String?>(null) }
  var isProcessing by remember { mutableStateOf(false) }
  var serverStatus by remember { mutableStateOf("Healthy (Rust Backend v0.1.0)") }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("XLM Paymaster - Fee Sponsorship", fontWeight = FontWeight.Bold) },
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
      // Info Card
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
              Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Text("Gasless Stellar Transactions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.primaryContainer
            ) {
              Text(
                serverStatus,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
              )
            }
          }
          Text(
            "XLM Paymaster abstracts network fees by wrapping client-signed Stellar transactions in fee-bump transactions. Applications sponsor XLM fees while users transact seamlessly.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      // Configuration Card
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text("Paymaster Server & Rust Backend Config", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

          OutlinedTextField(
            value = horizonUrl,
            onValueChange = { horizonUrl = it },
            label = { Text("STELLAR_HORIZON_URL") },
            modifier = Modifier.fillMaxWidth().testTag("horizon_url_input")
          )

          OutlinedTextField(
            value = networkPassphrase,
            onValueChange = { networkPassphrase = it },
            label = { Text("STELLAR_NETWORK_PASSPHRASE") },
            modifier = Modifier.fillMaxWidth()
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedTextField(
              value = baseFee,
              onValueChange = { baseFee = it },
              label = { Text("Base Fee (stroops)") },
              modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
              value = feeMultiplier,
              onValueChange = { feeMultiplier = it },
              label = { Text("Fee Multiplier") },
              modifier = Modifier.weight(1f)
            )
          }

          OutlinedTextField(
            value = feePayerSecret,
            onValueChange = { feePayerSecret = it },
            label = { Text("PAYMASTER_FEE_PAYER_SECRET") },
            modifier = Modifier.fillMaxWidth()
          )
        }
      }

      // Fee-Bump Request Card
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text("Fee-Bump Request Simulator (POST /fee-bump)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Text(
            "Submit a locally signed user transaction XDR to the XLM Paymaster sponsorship service.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          OutlinedTextField(
            value = sampleXdr,
            onValueChange = { sampleXdr = it },
            label = { Text("Signed Transaction XDR") },
            modifier = Modifier.fillMaxWidth().height(100.dp).testTag("signed_xdr_input")
          )

          Button(
            onClick = {
              isProcessing = true
              android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isProcessing = false
                feeBumpResult = "AAAAEEv83J...[Fee-Bump Sponsored Transaction XDR]...Success (Fee paid by Paymaster: 200 stroops)"
                onLogAudit("FEE_BUMP_SPONSORED", "Successfully sponsored fee-bump transaction via Stellar Testnet Horizon")
              }, 800)
            },
            modifier = Modifier.fillMaxWidth().testTag("request_fee_bump_btn")
          ) {
            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isProcessing) "Wrapping Fee-Bump..." else "Request Fee-Bump Sponsorship")
          }

          feeBumpResult?.let { res ->
            Surface(
              modifier = Modifier.fillMaxWidth(),
              color = MaterialTheme.colorScheme.primaryContainer,
              shape = RoundedCornerShape(8.dp)
            ) {
              Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Text("Fee-Bump Result (XDR)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                Text(res, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
              }
            }
          }
        }
      }
    }
  }
}
