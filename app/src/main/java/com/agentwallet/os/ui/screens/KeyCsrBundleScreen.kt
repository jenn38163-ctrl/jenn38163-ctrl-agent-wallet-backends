package com.agentwallet.os.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
fun KeyCsrBundleScreen(
  onGenerateBundle: (name: String, keySize: Int, cn: String, o: String, ou: String, c: String, st: String, l: String, email: String) -> Unit
) {
  var assetName by remember { mutableStateOf("Samsung_ATW_Key_CSR_Bundle") }
  var keySize by remember { mutableIntStateOf(2048) }
  var commonName by remember { mutableStateOf("atw-device.samsung.com") }
  var organization by remember { mutableStateOf("Samsung Electronics") }
  var organizationalUnit by remember { mutableStateOf("ATW Device Lab") }
  var country by remember { mutableStateOf("KR") }
  var state by remember { mutableStateOf("Gyeonggi-do") }
  var locality by remember { mutableStateOf("Suwon") }
  var email by remember { mutableStateOf("device-atw@samsung.com") }
  var successMessage by remember { mutableStateOf<String?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Generate Key + CSR Bundle", fontWeight = FontWeight.Bold) },
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
          Text("Combined Key + CSR Generator", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Text(
            "Generate a new RSA private key and accompanying Certificate Signing Request (CSR) in a single unified step.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          OutlinedTextField(
            value = assetName,
            onValueChange = { assetName = it },
            label = { Text("Bundle Name") },
            modifier = Modifier.fillMaxWidth().testTag("bundle_name_input")
          )

          Text("Key Size", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
          Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilterChip(selected = keySize == 2048, onClick = { keySize = 2048 }, label = { Text("2048-bit") })
            FilterChip(selected = keySize == 4096, onClick = { keySize = 4096 }, label = { Text("4096-bit") })
          }

          OutlinedTextField(value = commonName, onValueChange = { commonName = it }, label = { Text("Common Name (CN)") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = organization, onValueChange = { organization = it }, label = { Text("Organization (O)") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = organizationalUnit, onValueChange = { organizationalUnit = it }, label = { Text("Organizational Unit (OU)") }, modifier = Modifier.fillMaxWidth())
          
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = country, onValueChange = { country = it }, label = { Text("Country (C)") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = state, onValueChange = { state = it }, label = { Text("State (ST)") }, modifier = Modifier.weight(1f))
          }

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = locality, onValueChange = { locality = it }, label = { Text("Locality (L)") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.weight(1f))
          }

          Button(
            onClick = {
              if (assetName.isNotBlank() && commonName.isNotBlank()) {
                onGenerateBundle(assetName, keySize, commonName, organization, organizationalUnit, country, state, locality, email)
                successMessage = "Successfully generated Key + CSR bundle '$assetName'!"
              }
            },
            modifier = Modifier.fillMaxWidth().testTag("generate_bundle_btn")
          ) {
            Icon(Icons.Default.AllInclusive, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Generate Key + CSR Bundle")
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
