package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.WalletViewModel
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
  object Home : Screen("home", "Home", Icons.Default.Dashboard)
  object PrivateKey : Screen("private_key", "Key", Icons.Default.VpnKey)
  object Csr : Screen("csr", "CSR", Icons.Default.Description)
  object Cert : Screen("cert", "Cert", Icons.Default.Verified)
  object GPay : Screen("gpay", "GPay", Icons.Default.Payment)
  object Paynet : Screen("paynet", "Paynet", Icons.Default.Shield)
  object Paymaster : Screen("paymaster", "Paymaster", Icons.Default.Bolt)
  object OpenPaygo : Screen("openpaygo", "PAYGO", Icons.Default.Timer)
  object GPayKeys : Screen("gpay_keys", "Rotation", Icons.Default.Key)
  object AuditLogs : Screen("audit_logs", "Audit", Icons.Default.History)
  object Vault : Screen("vault", "Vault", Icons.Default.FolderOpen)
  object Copilot : Screen("copilot", "AI", Icons.Default.SmartToy)
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        val viewModel: WalletViewModel = viewModel()
        val atwAssets by viewModel.atwAssets.collectAsState()
        val auditLogs by viewModel.auditLogs.collectAsState()
        val gpayKeyPairs by viewModel.gpayKeyPairs.collectAsState()
        val chatMessages by viewModel.chatMessages.collectAsState()
        val isCopilotLoading by viewModel.isCopilotLoading.collectAsState()

        var selectedTab by remember { mutableIntStateOf(0) }
        val screens = listOf(
          Screen.Home,
          Screen.PrivateKey,
          Screen.Csr,
          Screen.Cert,
          Screen.GPay,
          Screen.Paynet,
          Screen.Paymaster,
          Screen.OpenPaygo,
          Screen.GPayKeys,
          Screen.AuditLogs,
          Screen.Vault,
          Screen.Copilot
        )

        Scaffold(
          modifier = Modifier.fillMaxSize(),
          bottomBar = {
            NavigationBar(
              containerColor = MaterialTheme.colorScheme.surface
            ) {
              screens.forEachIndexed { index, screen ->
                NavigationBarItem(
                  icon = { Icon(screen.icon, contentDescription = screen.title) },
                  label = { Text(screen.title, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                  selected = selectedTab == index,
                  onClick = { selectedTab = index }
                )
              }
            }
          }
        ) { innerPadding ->
          Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (selectedTab) {
              0 -> HomeScreen(
                assets = atwAssets,
                onNavigateToPrivateKey = { selectedTab = 1 },
                onNavigateToCsr = { selectedTab = 2 },
                onNavigateToCert = { selectedTab = 3 },
                onNavigateToVault = { selectedTab = 10 }
              )
              1 -> PrivateKeyScreen(
                onGenerateKey = { name, keySize, encrypted ->
                  viewModel.generatePrivateKey(name, keySize, encrypted)
                }
              )
              2 -> CsrScreen(
                onGenerateCsr = { name, keySize, cn, o, ou, c, st, l, email ->
                  viewModel.generateCsr(name, keySize, cn, o, ou, c, st, l, email)
                }
              )
              3 -> SelfSignedCertScreen(
                onGenerateCert = { name, keySize, cn, o, ou, c, st, l, email, days ->
                  viewModel.generateSelfSignedCert(name, keySize, cn, o, ou, c, st, l, email, days)
                }
              )
              4 -> GooglePayDecryptScreen()
              5 -> PaynetScreen(
                onLogAudit = { eventType, desc ->
                  viewModel.logAudit(eventType, desc)
                }
              )
              6 -> XlmPaymasterScreen(
                onLogAudit = { eventType, desc ->
                  viewModel.logAudit(eventType, desc)
                }
              )
              7 -> OpenPaygoScreen(
                onLogAudit = { eventType, desc ->
                  viewModel.logAudit(eventType, desc)
                }
              )
              8 -> GooglePayKeyManagementScreen(
                keyPairs = gpayKeyPairs,
                onAddKeyPair = { name, priv, pub ->
                  viewModel.addGPayKeyPair(name, priv, pub)
                },
                onToggleKey = { keyId ->
                  viewModel.toggleGPayKeyPair(keyId)
                },
                onDeleteKey = { keyId ->
                  viewModel.deleteGPayKeyPair(keyId)
                }
              )
              9 -> AuditLogsScreen(
                auditLogs = auditLogs,
                onTriggerTestAudit = { eventType, desc ->
                  viewModel.logAudit(eventType, desc)
                }
              )
              10 -> CertificatesVaultScreen(
                assets = atwAssets,
                onDeleteAsset = { assetId ->
                  viewModel.deleteAsset(assetId)
                }
              )
              11 -> CopilotScreen(
                messages = chatMessages,
                isLoading = isCopilotLoading,
                onSendMessage = { prompt ->
                  viewModel.sendCopilotMessage(prompt)
                }
              )
            }
          }
        }
      }
    }
  }
}
