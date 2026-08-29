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
import com.example.ui.GPayKeyPair

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GooglePayKeyManagementScreen(
  keyPairs: List<GPayKeyPair>,
  onAddKeyPair: (String, String, String) -> Unit,
  onToggleKey: (String) -> Unit,
  onDeleteKey: (String) -> Unit
) {
  var nameInput by remember { mutableStateOf("") }
  var privateKeyInput by remember { mutableStateOf("") }
  var publicKeyInput by remember { mutableStateOf("") }
  var showAddDialog by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("GPay Key Rotation Management", fontWeight = FontWeight.Bold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
      )
    },
    floatingActionButton = {
      FloatingActionButton(
        onClick = { showAddDialog = true },
        containerColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("add_gpay_key_fab")
      ) {
        Icon(Icons.Default.Add, contentDescription = "Add Key Pair")
      }
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
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Graceful Key Rotation Manager", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          }
          Text(
            "Manage multiple merchant private/public key pairs for Google Pay ECv2 decryption. Maintaining old keys for at least 8 days after rotation ensures smooth transitions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      if (keyPairs.isEmpty()) {
        Box(
          modifier = Modifier.fillMaxWidth().weight(1f),
          contentAlignment = Alignment.Center
        ) {
          Text("No key pairs stored. Tap '+' to add a decryption key.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxWidth().weight(1f),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          items(keyPairs) { kp ->
            Card(
              modifier = Modifier.fillMaxWidth(),
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
                    Box(
                      modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (kp.isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant),
                      contentAlignment = Alignment.Center
                    ) {
                      Icon(
                        imageVector = if (kp.isActive) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (kp.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                      )
                    }
                    Column {
                      Text(kp.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                      Text(if (kp.isActive) "Active for Decryption" else "Archived / Grace Period", style = MaterialTheme.typography.bodySmall, color = if (kp.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                  }
                  Row {
                    Switch(
                      checked = kp.isActive,
                      onCheckedChange = { onToggleKey(kp.id) }
                    )
                    IconButton(onClick = { onDeleteKey(kp.id) }) {
                      Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                  }
                }

                Divider(color = MaterialTheme.colorScheme.surfaceVariant)

                Text("Private Key (PKCS#8 Base64):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(kp.privateKey.take(45) + "...", style = MaterialTheme.typography.bodySmall)

                Text("Public Key (Uncompressed Point Base64):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(kp.publicKey, style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        }
      }
    }
  }

  if (showAddDialog) {
    AlertDialog(
      onDismissRequest = { showAddDialog = false },
      title = { Text("Add GPay Decryption Key Pair", fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = it },
            label = { Text("Key Pair Name (e.g. Q3 Rotation Key)") },
            modifier = Modifier.fillMaxWidth().testTag("key_name_input")
          )
          OutlinedTextField(
            value = privateKeyInput,
            onValueChange = { privateKeyInput = it },
            label = { Text("Private Key (PKCS#8 Base64)") },
            modifier = Modifier.fillMaxWidth().height(100.dp).testTag("private_key_input")
          )
          OutlinedTextField(
            value = publicKeyInput,
            onValueChange = { publicKeyInput = it },
            label = { Text("Public Key (Base64 Uncompressed)") },
            modifier = Modifier.fillMaxWidth().height(80.dp).testTag("public_key_input")
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (nameInput.isNotBlank() && privateKeyInput.isNotBlank()) {
              onAddKeyPair(nameInput, privateKeyInput, publicKeyInput.ifBlank { "BOdoXP+9Aq473SnGwg3JU1..." })
              nameInput = ""
              privateKeyInput = ""
              publicKeyInput = ""
              showAddDialog = false
            }
          },
          modifier = Modifier.testTag("save_key_pair_btn")
        ) {
          Text("Save & Add Key")
        }
      },
      dismissButton = {
        TextButton(onClick = { showAddDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }
}
