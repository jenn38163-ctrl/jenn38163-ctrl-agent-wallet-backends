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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateKeyScreen(
  onGenerateKey: (name: String, keySize: Int, encrypted: Boolean) -> Unit
) {
  var keyName by remember { mutableStateOf("Samsung_ATW_Key_01") }
  var keySize by remember { mutableIntStateOf(2048) }
  var encrypted by remember { mutableStateOf(false) }
  var password by remember { mutableStateOf("") }
  var successMessage by remember { mutableStateOf<String?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Generate RSA Private Key", fontWeight = FontWeight.Bold) },
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
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text("RSA Private Key Generator", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Text(
            "Create secure 2048-bit or 4096-bit RSA private keys for Samsung ATW (Authorized Testing Workshop) without requiring external OpenSSL binaries.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          OutlinedTextField(
            value = keyName,
            onValueChange = { keyName = it },
            label = { Text("Asset Name / Filename") },
            modifier = Modifier.fillMaxWidth().testTag("key_name_input")
          )

          Text("Key Size (Bits)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
          Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilterChip(
              selected = keySize == 2048,
              onClick = { keySize = 2048 },
              label = { Text("2048-bit (Standard)") }
            )
            FilterChip(
              selected = keySize == 4096,
              onClick = { keySize = 4096 },
              label = { Text("4096-bit (High Security)") }
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("AES-256 Encryption", fontWeight = FontWeight.Medium)
              Text("Optional password protection for PKCS#8 private key", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
              checked = encrypted,
              onCheckedChange = { encrypted = it }
            )
          }

          if (encrypted) {
            OutlinedTextField(
              value = password,
              onValueChange = { password = it },
              label = { Text("Encryption Password") },
              modifier = Modifier.fillMaxWidth()
            )
          }

          Button(
            onClick = {
              if (keyName.isNotBlank()) {
                onGenerateKey(keyName, keySize, encrypted)
                successMessage = "Successfully generated $keySize-bit RSA private key '$keyName'!"
              }
            },
            modifier = Modifier.fillMaxWidth().testTag("generate_key_btn")
          ) {
            Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Generate & Save Private Key")
          }
        }
      }

      successMessage?.let { msg ->
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
        ) {
          Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text(msg, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
    }
  }
}
