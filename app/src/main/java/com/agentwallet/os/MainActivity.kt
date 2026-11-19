package com.agentwallet.os

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentwallet.os.ui.WalletViewModel
import com.agentwallet.os.ui.screens.*
import com.agentwallet.os.ui.theme.MyApplicationTheme

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
  object Home : Screen("home", "Home", Icons.Default.Dashboard)
  object Wallets : Screen("wallets", "Wallets", Icons.Default.AccountBalanceWallet)
  object Gateway : Screen("gateway", "Gateway", Icons.Default.SwapHoriz)
  object Ledger : Screen("ledger", "Ledger", Icons.Default.Book)
  object Transactions : Screen("transactions", "History", Icons.Default.ReceiptLong)
  object FirebaseAuth : Screen("firebase_auth", "Firebase", Icons.Default.CloudSync)
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

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        val viewModel: WalletViewModel = viewModel()
        val currentUser by viewModel.currentUser.collectAsState()
        val isAuthLoading by viewModel.isAuthLoading.collectAsState()
        val authErrorMessage by viewModel.authErrorMessage.collectAsState()
        val authSuccessMessage by viewModel.authSuccessMessage.collectAsState()
        val isBackendLoading by viewModel.isBackendLoading.collectAsState()
        val backendErrorMessage by viewModel.backendErrorMessage.collectAsState()
        val backendStatusMessage by viewModel.backendStatusMessage.collectAsState()
        val provisioningInfo by viewModel.provisioningInfo.collectAsState()
        val firestoreAssets by viewModel.firestoreVaultAssets.collectAsState()
        val fcmToken by viewModel.fcmToken.collectAsState()
        val fcmMessages by viewModel.fcmMessages.collectAsState()
        val wallets by viewModel.wallets.collectAsState()
        val backendTransactions by viewModel.transactions.collectAsState()
        val lastPaymentResult by viewModel.lastPaymentResult.collectAsState()
        val atwAssets by viewModel.atwAssets.collectAsState()
        val auditLogs by viewModel.auditLogs.collectAsState()
        val gpayKeyPairs by viewModel.gpayKeyPairs.collectAsState()
        val chatMessages by viewModel.chatMessages.collectAsState()
        val isCopilotLoading by viewModel.isCopilotLoading.collectAsState()
        val transactionHistoryState by viewModel.transactionHistoryState.collectAsStateWithLifecycle()

        var isGuestMode by remember { mutableStateOf(false) }
        var selectedTab by remember { mutableIntStateOf(0) }

        val screens = listOf(
          Screen.Home,
          Screen.Wallets,
          Screen.Gateway,
          Screen.Ledger,
          Screen.Transactions,
          Screen.FirebaseAuth,
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

        BackHandler(enabled = selectedTab != 0) {
          selectedTab = 0
        }

        if (currentUser == null && !isGuestMode) {
          SignInScreen(
            isLoading = isAuthLoading,
            errorMessage = authErrorMessage,
            successMessage = authSuccessMessage,
            onSignIn = { email, password ->
              viewModel.signInWithEmailPassword(email, password)
            },
            onSignUp = { email, password, displayName, organization ->
              viewModel.signUpWithEmailPassword(email, password, displayName, organization)
            },
            onForgotPassword = { email ->
              viewModel.sendPasswordResetEmail(email)
            },
            onContinueAsGuest = {
              isGuestMode = true
            },
            onClearMessages = {
              viewModel.clearAuthMessages()
            }
          )
        } else {
          Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
              TopAppBar(
                title = {
                  Column {
                    Text(
                      text = "Agent Wallet OS",
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold
                    )
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                      Box(
                        modifier = Modifier
                          .size(8.dp)
                          .clip(CircleShape)
                          .background(
                            if (currentUser != null) Color(0xFF4CAF50) else Color(0xFFFF9800)
                          )
                      )
                      Text(
                        text = if (currentUser != null) (currentUser?.email ?: "Authenticated") else "Guest Mode (Unauthenticated)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1
                      )
                    }
                  }
                },
                actions = {
                  if (currentUser != null) {
                    FilledTonalButton(
                      onClick = {
                        viewModel.signOut()
                        isGuestMode = false
                      },
                      contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                      modifier = Modifier
                        .padding(end = 8.dp)
                        .testTag("topbar_btn_signout")
                    ) {
                      Icon(
                        Icons.Default.Logout,
                        contentDescription = "Sign Out",
                        modifier = Modifier.size(16.dp)
                      )
                      Spacer(modifier = Modifier.width(4.dp))
                      Text("Sign Out", style = MaterialTheme.typography.labelSmall)
                    }
                  } else {
                    Button(
                      onClick = {
                        isGuestMode = false
                      },
                      contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                      modifier = Modifier
                        .padding(end = 8.dp)
                        .testTag("topbar_btn_signin")
                    ) {
                      Icon(
                        Icons.Default.Login,
                        contentDescription = "Sign In",
                        modifier = Modifier.size(16.dp)
                      )
                      Spacer(modifier = Modifier.width(4.dp))
                      Text("Sign In", style = MaterialTheme.typography.labelSmall)
                    }
                  }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                  containerColor = MaterialTheme.colorScheme.surface
                )
              )
            },
            bottomBar = {
              ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 0.dp,
                containerColor = MaterialTheme.colorScheme.surface
              ) {
                screens.forEachIndexed { index, screen ->
                  Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    icon = { Icon(screen.icon, contentDescription = screen.title) },
                    text = { Text(screen.title, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                  )
                }
              }
            }
          ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
              when (screens[selectedTab]) {
                Screen.Home -> HomeScreen(
                  assets = atwAssets,
                  currentUser = currentUser,
                  onNavigateToPrivateKey = { selectedTab = screens.indexOf(Screen.PrivateKey) },
                  onNavigateToCsr = { selectedTab = screens.indexOf(Screen.Csr) },
                  onNavigateToCert = { selectedTab = screens.indexOf(Screen.Cert) },
                  onNavigateToVault = { selectedTab = screens.indexOf(Screen.Vault) },
                  onNavigateToFirebaseAuth = { selectedTab = screens.indexOf(Screen.FirebaseAuth) },
                  onNavigateToTransactions = { selectedTab = screens.indexOf(Screen.Transactions) }
                )
                Screen.Wallets -> WalletsScreen(
                  wallets = wallets,
                  provisioningInfo = provisioningInfo,
                  isLoading = isBackendLoading,
                  errorMessage = backendErrorMessage,
                  statusMessage = backendStatusMessage,
                  onRefresh = { viewModel.refreshBackendFinancialData() },
                  onDismissMessages = { viewModel.clearBackendMessages() },
                  onRequestTenantAccess = { orgName ->
                    viewModel.requestTenantAccess(orgName)
                  },
                  onCreateWallet = { name, org, bal, monthly, perReq, vendors, chains, approval, currency ->
                    viewModel.createWallet(name, org, bal, monthly, perReq, vendors, chains, approval, currency)
                  },
                  onDeposit = { walletId, amount ->
                    viewModel.depositFunds(walletId, amount)
                  }
                )
                Screen.Gateway -> GatewayScreen(
                  wallets = wallets,
                  lastPaymentResult = lastPaymentResult,
                  isLoading = isBackendLoading,
                  onSimulatePayment = { walletId, vendor, amount, protocol, chain ->
                    viewModel.simulatePaymentChallenge(walletId, vendor, amount, protocol, chain)
                  },
                  onClearResult = { viewModel.clearPaymentResult() }
                )
                Screen.Ledger -> LedgerScreen(
                  transactions = backendTransactions,
                  auditLogs = auditLogs
                )
                Screen.Transactions -> TransactionHistoryScreen(
                  uiState = transactionHistoryState,
                  userEmail = currentUser?.email,
                  onRefresh = { viewModel.refreshTransactionHistory() },
                  onNavigateToAuth = { isGuestMode = false }
                )
                Screen.FirebaseAuth -> FirebaseAuthScreen(
                  currentUser = currentUser,
                  isAuthLoading = isAuthLoading,
                  authErrorMessage = authErrorMessage,
                  authSuccessMessage = authSuccessMessage,
                  firestoreAssets = firestoreAssets,
                  fcmToken = fcmToken,
                  fcmMessages = fcmMessages,
                  onSignIn = { email, pass ->
                    viewModel.signInWithEmailPassword(email, pass)
                  },
                  onSignUp = { email, pass, displayName, org ->
                    viewModel.signUpWithEmailPassword(email, pass, displayName, org)
                  },
                  onSignOut = {
                    viewModel.signOut()
                    isGuestMode = false
                  },
                  onForgotPassword = { email ->
                    viewModel.sendPasswordResetEmail(email)
                  },
                  onSyncLocalToCloud = {
                    viewModel.syncAllLocalToFirestore()
                  },
                  onClearAuthMessages = {
                    viewModel.clearAuthMessages()
                  },
                  onRefreshFcmToken = {
                    viewModel.fetchFcmToken()
                  },
                  onSubscribeTopic = { topic ->
                    viewModel.subscribeToFcmTopic(topic)
                  }
                )
                Screen.PrivateKey -> PrivateKeyScreen(
                  onGenerateKey = { name, keySize, encrypted ->
                    viewModel.generatePrivateKey(name, keySize, encrypted)
                  }
                )
                Screen.Csr -> CsrScreen(
                  onGenerateCsr = { name, keySize, cn, o, ou, c, st, l, email ->
                    viewModel.generateCsr(name, keySize, cn, o, ou, c, st, l, email)
                  }
                )
                Screen.Cert -> SelfSignedCertScreen(
                  onGenerateCert = { name, keySize, cn, o, ou, c, st, l, email, days ->
                    viewModel.generateSelfSignedCert(name, keySize, cn, o, ou, c, st, l, email, days)
                  }
                )
                Screen.GPay -> GooglePayDecryptScreen()
                Screen.Paynet -> PaynetScreen(
                  onLogAudit = { eventType, desc ->
                    viewModel.logAudit(eventType, desc)
                  }
                )
                Screen.Paymaster -> XlmPaymasterScreen(
                  onLogAudit = { eventType, desc ->
                    viewModel.logAudit(eventType, desc)
                  }
                )
                Screen.OpenPaygo -> OpenPaygoScreen(
                  onLogAudit = { eventType, desc ->
                    viewModel.logAudit(eventType, desc)
                  }
                )
                Screen.GPayKeys -> GooglePayKeyManagementScreen(
                  keyPairs = gpayKeyPairs,
                  onAddKeyPair = { name, pub ->
                    viewModel.addGPayKeyPair(name, pub)
                  },
                  onToggleKey = { keyId ->
                    viewModel.toggleGPayKeyPair(keyId)
                  },
                  onDeleteKey = { keyId ->
                    viewModel.deleteGPayKeyPair(keyId)
                  }
                )
                Screen.AuditLogs -> AuditLogsScreen(
                  auditLogs = auditLogs,
                  onTriggerTestAudit = { eventType, desc ->
                    viewModel.logAudit(eventType, desc)
                  }
                )
                Screen.Vault -> CertificatesVaultScreen(
                  assets = atwAssets,
                  onDeleteAsset = { assetId ->
                    viewModel.deleteAsset(assetId)
                  }
                )
                Screen.Copilot -> CopilotScreen(
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
}
