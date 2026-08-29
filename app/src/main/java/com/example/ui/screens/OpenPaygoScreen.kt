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
import java.util.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenPaygoScreen(
  onLogAudit: (String, String) -> Unit
) {
  var secretKey by remember { mutableStateOf("9912345678901234") }
  var tokenType by remember { mutableStateOf("ADD_TIME") }
  var tokenValue by remember { mutableStateOf("30") } // days or count
  var deviceTokenCount by remember { mutableStateOf(105) }
  var generatedToken by remember { mutableStateOf("8492039182") }
  
  var simulatorTokenInput by remember { mutableStateOf("") }
  var simulationResult by remember { mutableStateOf<String?>(null) }
  var deviceStatus by remember { mutableStateOf("UNLOCKED") }
  var deviceRemainingDays by remember { mutableStateOf(45) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("OpenPAYGO Token v2 Suite", fontWeight = FontWeight.Bold) },
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
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("OpenPAYGO Token v2 (EnAccess)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          }
          Text(
            "Open source cryptographic token system enabling Pay-As-You-Go (PAYGO) functionality in hardware & IoT products without cellular connectivity.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      // Generator Card
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text("Token Generator (Server)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

          OutlinedTextField(
            value = secretKey,
            onValueChange = { secretKey = it },
            label = { Text("Device Secret Key") },
            modifier = Modifier.fillMaxWidth().testTag("openpaygo_secret_input")
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedTextField(
              value = tokenType,
              onValueChange = { tokenType = it },
              label = { Text("Token Type") },
              modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
              value = tokenValue,
              onValueChange = { tokenValue = it },
              label = { Text("Value (Days/Count)") },
              modifier = Modifier.weight(1f).testTag("openpaygo_value_input")
            )
          }

          OutlinedTextField(
            value = deviceTokenCount.toString(),
            onValueChange = { deviceTokenCount = it.toIntOrNull() ?: 0 },
            label = { Text("Device Current Token Count (Counter)") },
            modifier = Modifier.fillMaxWidth()
          )

          Button(
            onClick = {
              val randomToken = (1000000000L + Random().nextInt(900000000)).toString() + (tokenValue.toIntOrNull() ?: 30) % 10
              generatedToken = randomToken
              onLogAudit("PAYGO_TOKEN_GENERATED", "Generated OpenPAYGO token type $tokenType with value $tokenValue for count $deviceTokenCount")
            },
            modifier = Modifier.fillMaxWidth().testTag("generate_paygo_token_btn")
          ) {
            Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Generate PAYGO Token")
          }

          generatedToken.let { token ->
            Surface(
              modifier = Modifier.fillMaxWidth(),
              color = MaterialTheme.colorScheme.primaryContainer,
              shape = RoundedCornerShape(8.dp)
            ) {
              Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Text("Generated Token Number", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(token, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
              }
            }
          }
        }
      }

      // Device Simulator Card
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
            Text("Device Simulator", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = if (deviceStatus == "UNLOCKED") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
            ) {
              Text(
                "$deviceStatus ($deviceRemainingDays Days Left)",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = if (deviceStatus == "UNLOCKED") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
              )
            }
          }

          OutlinedTextField(
            value = simulatorTokenInput,
            onValueChange = { simulatorTokenInput = it },
            label = { Text("Enter Token on Device Keypad") },
            modifier = Modifier.fillMaxWidth().testTag("device_token_input")
          )

          Button(
            onClick = {
              if (simulatorTokenInput.isNotBlank()) {
                deviceRemainingDays += 30
                deviceStatus = "UNLOCKED"
                deviceTokenCount++
                simulationResult = "✓ Token Accepted! Added 30 days. New remaining time: $deviceRemainingDays days."
                onLogAudit("PAYGO_TOKEN_ACCEPTED", "Device successfully validated and accepted token $simulatorTokenInput")
                simulatorTokenInput = ""
              } else {
                simulationResult = "✗ Please enter a valid token number."
              }
            },
            modifier = Modifier.fillMaxWidth().testTag("submit_token_device_btn")
          ) {
            Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Submit Token to Simulator")
          }

          simulationResult?.let { res ->
            Surface(
              modifier = Modifier.fillMaxWidth(),
              color = MaterialTheme.colorScheme.surfaceVariant,
              shape = RoundedCornerShape(8.dp)
            ) {
              Box(modifier = Modifier.padding(12.dp)) {
                Text(res, style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        }
      }
    }
  }
}
