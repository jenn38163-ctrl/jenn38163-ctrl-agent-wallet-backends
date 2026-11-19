package com.agentwallet.os.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentwallet.os.data.AtwAssetEntity
import com.agentwallet.os.firebase.FirebaseAuthService
import com.google.firebase.auth.FirebaseUser

@Composable
fun HomeScreen(
  assets: List<AtwAssetEntity>,
  currentUser: FirebaseUser?,
  onNavigateToPrivateKey: () -> Unit,
  onNavigateToCsr: () -> Unit,
  onNavigateToCert: () -> Unit,
  onNavigateToVault: () -> Unit,
  onNavigateToFirebaseAuth: () -> Unit,
  onNavigateToTransactions: () -> Unit = {}
) {
  val privateKeyCount = assets.count { it.assetType == "PRIVATE_KEY" }
  val csrCount = assets.count { it.assetType == "CSR" }
  val certCount = assets.count { it.assetType == "SELF_SIGNED_CERT" }
  val bundleCount = assets.count { it.assetType == "KEY_CSR_BUNDLE" }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    contentPadding = PaddingValues(vertical = 24.dp)
  ) {
    // Hero Banner
    item {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(24.dp))
          .background(
            Brush.linearGradient(
              listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
            )
          )
          .padding(24.dp)
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "SAMSUNG ATW",
              style = MaterialTheme.typography.labelMedium,
              color = Color.White.copy(alpha = 0.8f),
              fontWeight = FontWeight.Bold,
              letterSpacing = 1.5.sp
            )
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = Color.White.copy(alpha = 0.2f)
            ) {
              Text(
                text = "Authorized Testing Workshop",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
              )
            }
          }
          Text(
            text = "Key & X.509 Generator",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Create RSA private keys, CSRs, and self-signed certificates for Samsung ATW testing seamlessly without OpenSSL.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.9f)
          )
          Spacer(modifier = Modifier.height(8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
              onClick = onNavigateToPrivateKey,
              colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary),
              modifier = Modifier.testTag("hero_generate_key_btn")
            ) {
              Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Generate Key")
            }
            OutlinedButton(
              onClick = onNavigateToVault,
              colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
              Text("View Vault (${assets.size})")
            }
          }
        }
      }
    }

    // Firebase Backend Status Card
    item {
      Card(
        onClick = onNavigateToFirebaseAuth,
        modifier = Modifier.fillMaxWidth().testTag("firebase_status_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
      ) {
        Row(
          modifier = Modifier.padding(16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
          ) {
            Box(
              modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFFFA000).copy(alpha = 0.2f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(Icons.Default.CloudSync, contentDescription = null, tint = Color(0xFFF57C00), modifier = Modifier.size(24.dp))
            }
            Column {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Firebase Backend", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                  Text(
                    text = "wallet-54645",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                  )
                }
              }
              Text(
                text = if (currentUser != null) "Logged in as ${currentUser.email}" else "Email/Password Auth • Tap to sign in",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
          Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }

    // Firestore Transaction History Card
    item {
      Card(
        onClick = onNavigateToTransactions,
        modifier = Modifier.fillMaxWidth().testTag("home_transaction_history_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Row(
          modifier = Modifier.padding(16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
          ) {
            Box(
              modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
            }
            Column {
              Text("Transaction History", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
              Text(
                text = "View pending & completed Firestore payments",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
          Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }

    // Metrics Row
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Card(
          modifier = Modifier.weight(1f),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Private Keys", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$privateKeyCount", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("RSA 2048/4096-bit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
          }
        }
        Card(
          modifier = Modifier.weight(1f),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Default.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Certificates", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${csrCount + certCount + bundleCount}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("CSRs & X.509 Root CA", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
          }
        }
      }
    }

    // Quick Actions Header
    item {
      Text(
        text = "Quick Asset Generators",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp)
      )
    }

    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        OutlinedCard(
          onClick = onNavigateToPrivateKey,
          modifier = Modifier.weight(1f)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Private Key", fontWeight = FontWeight.Bold)
            Text("RSA 2048/4096", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        OutlinedCard(
          onClick = onNavigateToCsr,
          modifier = Modifier.weight(1f)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text("CSR Request", fontWeight = FontWeight.Bold)
            Text("PKCS#10 format", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
    }

    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        OutlinedCard(
          onClick = onNavigateToCert,
          modifier = Modifier.weight(1f)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(Icons.Default.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Text("Self-Signed Cert", fontWeight = FontWeight.Bold)
            Text("X.509 standard", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        OutlinedCard(
          onClick = onNavigateToVault,
          modifier = Modifier.weight(1f)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Certificates Vault", fontWeight = FontWeight.Bold)
            Text("Browse & Copy PEM", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
    }

    // Recent Assets Header
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Recently Generated Assets",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(top = 8.dp)
        )
        TextButton(onClick = onNavigateToVault) {
          Text("View Vault")
        }
      }
    }

    if (assets.isEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
          Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("No assets in vault yet. Generate your first key or certificate!", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
    } else {
      items(assets.take(5)) { asset ->
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier = Modifier
                  .size(40.dp)
                  .clip(RoundedCornerShape(10.dp))
                  .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = when (asset.assetType) {
                    "PRIVATE_KEY" -> Icons.Default.VpnKey
                    "CSR" -> Icons.Default.Description
                    else -> Icons.Default.Verified
                  },
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp)
                )
              }
              Column {
                Text(asset.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text("${asset.assetType} • ${asset.keySize}-bit${if (asset.commonName.isNotBlank()) " • CN=${asset.commonName}" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }
        }
      }
    }
  }
}
