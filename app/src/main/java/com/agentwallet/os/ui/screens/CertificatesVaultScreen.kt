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
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentwallet.os.data.AtwAssetEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CertificatesVaultScreen(
  assets: List<AtwAssetEntity>,
  onDeleteAsset: (String) -> Unit
) {
  val clipboardManager: ClipboardManager = LocalClipboardManager.current
  var selectedAsset by remember { mutableStateOf<AtwAssetEntity?>(null) }
  var copiedToast by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Certificates Vault (`certificates/`)", fontWeight = FontWeight.Bold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
      )
    }
  ) { paddingValues ->
    if (assets.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(paddingValues),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
          Text("No certificates or keys generated yet.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Text("Use the generator tabs to create RSA keys, CSRs, or certificates.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .padding(paddingValues)
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        item {
          Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Local Assets Repository", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
              Text("Generated assets are organized under certificates/ (private_keys/, csr_requests/, self_signed_certs/, combined_keys_csr/).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }

        items(assets) { asset ->
          Card(
            onClick = { selectedAsset = asset },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.testTag("asset_card_${asset.id}")
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Box(
                modifier = Modifier
                  .size(44.dp)
                  .clip(RoundedCornerShape(10.dp))
                  .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
              ) {
                val icon = when (asset.assetType) {
                  "PRIVATE_KEY" -> Icons.Default.VpnKey
                  "CSR" -> Icons.Default.Description
                  "KEY_CSR_BUNDLE" -> Icons.Default.AllInclusive
                  else -> Icons.Default.Verified
                }
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              }
              Column(modifier = Modifier.weight(1f)) {
                Text(asset.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${asset.assetType} • ${asset.keySize}-bit${if (asset.commonName.isNotBlank()) " • CN=${asset.commonName}" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              IconButton(onClick = { onDeleteAsset(asset.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
              }
            }
          }
        }
      }
    }

    selectedAsset?.let { asset ->
      AlertDialog(
        onDismissRequest = { selectedAsset = null },
        title = { Text(asset.name) },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Type: ${asset.assetType} (${asset.keySize}-bit)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            if (asset.commonName.isNotBlank()) {
              Text("CN: ${asset.commonName} | Org: ${asset.organization}", style = MaterialTheme.typography.bodySmall)
            }
            Surface(
              modifier = Modifier.fillMaxWidth().height(180.dp),
              color = MaterialTheme.colorScheme.surfaceVariant,
              shape = RoundedCornerShape(8.dp)
            ) {
              Box(modifier = Modifier.padding(12.dp)) {
                Text(asset.pemData, style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        },
        confirmButton = {
          TextButton(
            onClick = {
              clipboardManager.setText(AnnotatedString(asset.pemData))
              copiedToast = true
            }
          ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Copy PEM")
          }
        },
        dismissButton = {
          TextButton(onClick = { selectedAsset = null }) {
            Text("Close")
          }
        }
      )
    }
  }
}
