package com.agentwallet.os.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agentwallet.os.data.*
import com.agentwallet.os.firebase.*
import com.agentwallet.os.network.AgentWalletApiClient
import com.agentwallet.os.network.GeminiApiClient
import com.agentwallet.os.network.TenantProvisioningInfo
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface TransactionHistoryUiState {
  data object Unauthenticated : TransactionHistoryUiState
  data object Loading : TransactionHistoryUiState
  data class Success(
    val transactions: List<FirestoreTransaction>,
    val isRefreshing: Boolean = false
  ) : TransactionHistoryUiState
  data class Error(val message: String) : TransactionHistoryUiState
}

data class ChatMessage(
  val text: String,
  val isUser: Boolean,
  val timestamp: Long = System.currentTimeMillis()
)

data class GPayKeyPair(
  val id: String,
  val name: String,
  val publicKey: String,
  val createdAt: Long = System.currentTimeMillis(),
  val isActive: Boolean = true
)

class WalletViewModel(application: Application) : AndroidViewModel(application) {
  private val dao = WalletDatabase.getDatabase(application).walletDao()
  private val repository = WalletRepository(dao)

  // Firebase Services
  private val authService = FirebaseAuthService()
  private val firestoreService = FirestoreService()

  // FCM state
  val fcmToken: StateFlow<String?> = AppFirebaseMessagingService.deviceToken
  val fcmMessages: StateFlow<List<FcmNotificationMessage>> = AppFirebaseMessagingService.incomingMessages

  val currentUser: StateFlow<FirebaseUser?> = authService.authState
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authService.currentUser)

  private val _isAuthLoading = MutableStateFlow(false)
  val isAuthLoading: StateFlow<Boolean> = _isAuthLoading.asStateFlow()

  private val _authErrorMessage = MutableStateFlow<String?>(null)
  val authErrorMessage: StateFlow<String?> = _authErrorMessage.asStateFlow()

  private val _authSuccessMessage = MutableStateFlow<String?>(null)
  val authSuccessMessage: StateFlow<String?> = _authSuccessMessage.asStateFlow()

  // Backend API state
  private val _isBackendLoading = MutableStateFlow(false)
  val isBackendLoading: StateFlow<Boolean> = _isBackendLoading.asStateFlow()

  private val _backendErrorMessage = MutableStateFlow<String?>(null)
  val backendErrorMessage: StateFlow<String?> = _backendErrorMessage.asStateFlow()

  private val _backendStatusMessage = MutableStateFlow<String?>(null)
  val backendStatusMessage: StateFlow<String?> = _backendStatusMessage.asStateFlow()

  private val _provisioningInfo = MutableStateFlow<TenantProvisioningInfo?>(null)
  val provisioningInfo: StateFlow<TenantProvisioningInfo?> = _provisioningInfo.asStateFlow()

  init {
    fetchFcmToken()
    viewModelScope.launch {
      currentUser.collectLatest { user ->
        if (user == null) {
          _provisioningInfo.value = null
          repository.clearFinancialCache()
        } else {
          refreshBackendFinancialData()
        }
      }
    }
  }

  fun fetchFcmToken() {
    viewModelScope.launch {
      FcmManager.getDeviceToken()
    }
  }

  fun subscribeToFcmTopic(topic: String) {
    viewModelScope.launch {
      val success = FcmManager.subscribeToTopic(topic)
      if (success) {
        _authSuccessMessage.value = "Subscribed to push topic: $topic"
        logAudit("FCM_SUBSCRIBE", "Subscribed to FCM topic: $topic")
      } else {
        _authErrorMessage.value = "Failed to subscribe to topic: $topic"
      }
    }
  }

  val firestoreVaultAssets: StateFlow<List<FirestoreVaultAsset>> = firestoreService.getVaultAssetsFlow()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _txRefreshTrigger = MutableStateFlow(0)
  private val _isTxRefreshing = MutableStateFlow(false)
  private val _fetchedTxSnapshot = MutableStateFlow<Pair<String, List<FirestoreTransaction>>?>(null)

  @OptIn(ExperimentalCoroutinesApi::class)
  val transactionHistoryState: StateFlow<TransactionHistoryUiState> = combine(
    currentUser,
    _txRefreshTrigger
  ) { user, _ -> user }
    .flatMapLatest { user ->
      if (user == null) {
        _fetchedTxSnapshot.value = null
        flowOf<TransactionHistoryUiState>(TransactionHistoryUiState.Unauthenticated)
      } else {
        combine(
          firestoreService.observeUserTransactions(user.uid),
          _isTxRefreshing,
          _fetchedTxSnapshot
        ) { liveList, refreshing, fetchedPair ->
          val effectiveList = if (liveList.isNotEmpty() || fetchedPair?.first != user.uid) {
            liveList
          } else {
            fetchedPair.second
          }
          TransactionHistoryUiState.Success(
            transactions = effectiveList,
            isRefreshing = refreshing
          ) as TransactionHistoryUiState
        }
          .onStart { emit(TransactionHistoryUiState.Loading) }
          .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            emit(
              TransactionHistoryUiState.Error(
                rootError.localizedMessage ?: "Failed to query transaction history from Firestore."
              )
            )
          }
      }
    }
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = if (authService.currentUser == null) TransactionHistoryUiState.Unauthenticated else TransactionHistoryUiState.Loading
    )

  fun refreshTransactionHistory() {
    val uid = currentUser.value?.uid ?: return
    if (transactionHistoryState.value is TransactionHistoryUiState.Error) {
      _txRefreshTrigger.value += 1
      return
    }
    viewModelScope.launch {
      _isTxRefreshing.value = true
      val result = firestoreService.fetchUserTransactions(uid)
      result.onSuccess { list ->
        _fetchedTxSnapshot.value = uid to list
      }
      refreshBackendFinancialData()
      _isTxRefreshing.value = false
    }
  }

  val wallets: StateFlow<List<AgentWalletEntity>> = repository.allWallets
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val transactions: StateFlow<List<LedgerTransactionEntity>> = repository.allTransactions
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val auditLogs: StateFlow<List<AuditLogEntity>> = repository.allAuditLogs
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val atwAssets: StateFlow<List<AtwAssetEntity>> = repository.allAtwAssets
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  // Google Pay Key Rotation Management Panel state
  private val _gpayKeyPairs = MutableStateFlow<List<GPayKeyPair>>(
    listOf(
      GPayKeyPair(
        id = "key_primary_01",
        name = "Primary Production Key (2026)",
        publicKey = "BOdoXP+9Aq473SnGwg3JU1aiNpsd9vH2ognq4PtDtlLGa3Kj8TPf+jaQNPyDSkh3JUhiS0KyrrlWhAgNZKHYF2Y=",
        isActive = true
      ),
      GPayKeyPair(
        id = "key_secondary_02",
        name = "Secondary Rotation Key (Grace Period)",
        publicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE/1+3HBVSbdv+j7NaArdgMyoSAM43yRydzqdg1TxodSzA96Dj4Mc1EiKroxxunavVIvdxGnJeFViTzFvzFRxyCw==",
        isActive = true
      )
    )
  )
  val gpayKeyPairs: StateFlow<List<GPayKeyPair>> = _gpayKeyPairs.asStateFlow()

  // Firebase Auth Methods
  fun signInWithEmailPassword(email: String, pass: String) {
    if (email.isBlank() || pass.isBlank()) {
      _authErrorMessage.value = "Please provide both email and password."
      return
    }
    _isAuthLoading.value = true
    _authErrorMessage.value = null
    _authSuccessMessage.value = null

    viewModelScope.launch {
      val result = authService.signInWithEmailPassword(email, pass)
      _isAuthLoading.value = false
      result.fold(
        onSuccess = { user ->
          _authSuccessMessage.value = "Signed in successfully as ${user.email}"
          logAudit("FIREBASE_AUTH_SIGNIN", "User signed in via Email/Password: ${user.email}")
        },
        onFailure = { error ->
          _authErrorMessage.value = error.localizedMessage ?: "Authentication failed."
        }
      )
    }
  }

  fun signUpWithEmailPassword(email: String, pass: String, displayName: String, org: String) {
    if (email.isBlank() || pass.isBlank()) {
      _authErrorMessage.value = "Please provide email and password."
      return
    }
    _isAuthLoading.value = true
    _authErrorMessage.value = null
    _authSuccessMessage.value = null

    viewModelScope.launch {
      val result = authService.signUpWithEmailPassword(email, pass, displayName)
      _isAuthLoading.value = false
      result.fold(
        onSuccess = { user ->
          _authSuccessMessage.value = "Account created and signed in as ${user.email}"
          val profile = UserProfile(
            uid = user.uid,
            email = user.email ?: "",
            displayName = displayName,
            organization = org,
            role = "SecOps Administrator"
          )
          firestoreService.saveUserProfile(profile)
          logAudit("FIREBASE_AUTH_SIGNUP", "Created new account in project ${FirebaseAuthService.FIREBASE_PROJECT_ID}: ${user.email}")
        },
        onFailure = { error ->
          _authErrorMessage.value = error.localizedMessage ?: "Registration failed."
        }
      )
    }
  }

  fun sendPasswordResetEmail(email: String) {
    if (email.isBlank()) {
      _authErrorMessage.value = "Enter email to receive reset link."
      return
    }
    viewModelScope.launch {
      val result = authService.sendPasswordResetEmail(email)
      result.fold(
        onSuccess = {
          _authSuccessMessage.value = "Password reset email sent to $email"
        },
        onFailure = { error ->
          _authErrorMessage.value = error.localizedMessage ?: "Failed to send reset email."
        }
      )
    }
  }

  fun signOut() {
    val email = currentUser.value?.email ?: "User"
    authService.signOut()
    _authSuccessMessage.value = "Signed out successfully."
    logAudit("FIREBASE_AUTH_SIGNOUT", "User signed out: $email")
  }

  fun clearAuthMessages() {
    _authErrorMessage.value = null
    _authSuccessMessage.value = null
  }

  fun clearBackendMessages() {
    _backendErrorMessage.value = null
    _backendStatusMessage.value = null
  }

  fun syncAllLocalToFirestore() {
    viewModelScope.launch {
      _isAuthLoading.value = true
      val user = currentUser.value
      val uid = user?.uid ?: "anonymous"
      var count = 0
      atwAssets.value.forEach { asset ->
        val cloudAsset = FirestoreVaultAsset(
          id = asset.id,
          name = asset.name,
          assetType = asset.assetType,
          keySize = asset.keySize,
          encrypted = asset.encrypted,
          commonName = asset.commonName,
          organization = asset.organization,
          organizationalUnit = asset.organizationalUnit,
          country = asset.country,
          state = asset.state,
          locality = asset.locality,
          email = asset.email,
          validityDays = asset.validityDays,
          createdAt = asset.createdAt,
          userId = uid,
          projectId = FirebaseAuthService.FIREBASE_PROJECT_ID
        )
        firestoreService.saveVaultAsset(cloudAsset)
        count++
      }
      _isAuthLoading.value = false
      _authSuccessMessage.value = "Successfully synced $count assets to Cloud Firestore!"
      logAudit("FIRESTORE_SYNC", "Synchronized $count local assets to Firestore project ${FirebaseAuthService.FIREBASE_PROJECT_ID}")
    }
  }

  fun addGPayKeyPair(name: String, publicKey: String) {
    val newPair = GPayKeyPair(
      id = "key_" + UUID.randomUUID().toString().substring(0, 8),
      name = name,
      publicKey = publicKey,
      isActive = true
    )
    _gpayKeyPairs.value = listOf(newPair) + _gpayKeyPairs.value
    logAudit("KEY_ROTATION", "Registered Google Pay public key '$name' for rotation")
  }

  fun toggleGPayKeyPair(keyId: String) {
    _gpayKeyPairs.value = _gpayKeyPairs.value.map {
      if (it.id == keyId) it.copy(isActive = !it.isActive) else it
    }
  }

  fun deleteGPayKeyPair(keyId: String) {
    _gpayKeyPairs.value = _gpayKeyPairs.value.filter { it.id != keyId }
  }

  fun logAudit(eventType: String, description: String) {
    viewModelScope.launch {
      val logId = "audit_" + UUID.randomUUID().toString().substring(0, 8)
      val timestamp = System.currentTimeMillis()
      val actor = currentUser.value?.email ?: "did:agent:enterprise:system"
      val hash = WalletRepository.computeSha256Hex("$logId:$eventType:$description:$actor:$timestamp")
      val log = AuditLogEntity(
        id = logId,
        timestamp = timestamp,
        eventType = eventType,
        description = description,
        actor = actor,
        cryptographicHash = hash
      )
      dao.insertAuditLog(log)

      val uid = currentUser.value?.uid
      if (!uid.isNullOrBlank()) {
        val cloudLog = FirestoreAuditLog(
          id = logId,
          timestamp = log.timestamp,
          eventType = eventType,
          description = description,
          actor = actor,
          cryptographicHash = hash,
          userId = uid
        )
        firestoreService.saveAuditLog(cloudLog)
      }
    }
  }

  // Navigation tab state
  private val _selectedTab = MutableStateFlow(0)
  val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

  fun setSelectedTab(tab: Int) {
    _selectedTab.value = tab
  }

  // Chat copilot state
  private val _chatMessages = MutableStateFlow<List<ChatMessage>>(
    listOf(
      ChatMessage("Hello! I am your Agent Wallet OS & ATW Copilot. I can help you configure wallets, double-entry policies, X.509 certificates, RSA private keys, and CSRs. How can I assist you today?", false)
    )
  )
  val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

  private val _isCopilotLoading = MutableStateFlow(false)
  val isCopilotLoading: StateFlow<Boolean> = _isCopilotLoading.asStateFlow()

  fun sendCopilotMessage(prompt: String) {
    if (prompt.isBlank()) return
    val userMsg = ChatMessage(prompt, true)
    _chatMessages.value = _chatMessages.value + userMsg
    _isCopilotLoading.value = true

    viewModelScope.launch {
      val contextBuilder = buildString {
        append("Firebase Backend: Connected to ${FirebaseAuthService.FIREBASE_PROJECT_ID}\n")
        append("Current User: ${currentUser.value?.email ?: "Guest"}\n")
        append("Active Wallets: ${wallets.value.size}\n")
        append("Generated ATW Assets in Vault:\n")
        atwAssets.value.forEach { a ->
          append("- [${a.assetType}] ${a.name} (KeySize=${a.keySize}, CN=${a.commonName}, Org=${a.organization})\n")
        }
        append("Total Assets: ${atwAssets.value.size}\n")
      }

      val replyText = GeminiApiClient.askCopilot(prompt, contextBuilder)
      _chatMessages.value = _chatMessages.value + ChatMessage(replyText, false)
      _isCopilotLoading.value = false
    }
  }

  // ATW Asset Generation Methods
  fun generatePrivateKey(name: String, keySize: Int, encrypted: Boolean) {
    viewModelScope.launch {
      repository.generatePrivateKeyAsset(name, keySize, encrypted)
      syncAllLocalToFirestore()
    }
  }

  fun generateCsr(name: String, keySize: Int, cn: String, o: String, ou: String, c: String, st: String, l: String, email: String) {
    viewModelScope.launch {
      repository.generateCsrAsset(name, keySize, cn, o, ou, c, st, l, email)
      syncAllLocalToFirestore()
    }
  }

  fun generateSelfSignedCert(name: String, keySize: Int, cn: String, o: String, ou: String, c: String, st: String, l: String, email: String, validityDays: Int) {
    viewModelScope.launch {
      repository.generateSelfSignedCertAsset(name, keySize, cn, o, ou, c, st, l, email, validityDays)
      syncAllLocalToFirestore()
    }
  }

  fun generateKeyAndCsr(name: String, keySize: Int, cn: String, o: String, ou: String, c: String, st: String, l: String, email: String) {
    viewModelScope.launch {
      repository.generateKeyAndCsrAsset(name, keySize, cn, o, ou, c, st, l, email)
      syncAllLocalToFirestore()
    }
  }

  fun deleteAsset(assetId: String) {
    viewModelScope.launch {
      repository.deleteAsset(assetId)
      firestoreService.deleteVaultAsset(assetId)
    }
  }

  // PostgreSQL Financial Backend Integration (Sole System of Record)
  private val _lastPaymentResult = MutableStateFlow<PaymentResult?>(null)
  val lastPaymentResult: StateFlow<PaymentResult?> = _lastPaymentResult.asStateFlow()

  fun clearPaymentResult() {
    _lastPaymentResult.value = null
  }

  fun refreshBackendFinancialData() {
    if (currentUser.value == null) return
    if (!AgentWalletApiClient.isBackendConfigured()) {
      _backendErrorMessage.value = "Set BACKEND_URL in Secrets to sync with the PostgreSQL ledger."
      return
    }
    viewModelScope.launch {
      _isBackendLoading.value = true
      _backendErrorMessage.value = null

      val meResult = AgentWalletApiClient.fetchMe()
      val me = meResult.getOrNull()
      if (me != null) {
        _provisioningInfo.value = me
        if (me.provisioningStatus != "ACTIVE") {
          repository.clearFinancialCache()
          _backendStatusMessage.value = null
          _isBackendLoading.value = false
          return@launch
        }
      } else {
        meResult.exceptionOrNull()?.let { err ->
          _backendErrorMessage.value = err.localizedMessage ?: "Failed to verify tenant membership."
        }
      }

      val walletsResult = AgentWalletApiClient.fetchWallets()
      walletsResult.onSuccess { list ->
        repository.replaceBackendWallets(list)
      }.onFailure { err ->
        _backendErrorMessage.value = err.localizedMessage ?: "Failed to load wallets from backend."
      }

      val txResult = AgentWalletApiClient.fetchTransactions()
      txResult.onSuccess { list ->
        repository.replaceBackendTransactions(list)
      }

      val auditResult = AgentWalletApiClient.fetchAuditEvents()
      auditResult.onSuccess { list ->
        if (list.isNotEmpty()) {
          repository.replaceBackendAuditLogs(list)
        }
      }

      _isBackendLoading.value = false
    }
  }

  fun requestTenantAccess(organizationName: String) {
    if (currentUser.value == null) {
      _backendErrorMessage.value = "Sign in with Firebase first."
      return
    }
    if (organizationName.isBlank()) {
      _backendErrorMessage.value = "Enter an organization name to request tenant access."
      return
    }
    if (!AgentWalletApiClient.isBackendConfigured()) {
      _backendErrorMessage.value = "Configure BACKEND_URL in Secrets before requesting tenant approval."
      return
    }
    viewModelScope.launch {
      _isBackendLoading.value = true
      _backendErrorMessage.value = null
      val res = AgentWalletApiClient.requestTenantAccess(organizationName.trim())
      res.fold(
        onSuccess = { info ->
          _provisioningInfo.value = info
          _backendStatusMessage.value = "Access request submitted for '${organizationName.trim()}'. A tenant administrator must approve your account."
        },
        onFailure = { err ->
          _backendErrorMessage.value = err.localizedMessage ?: "Failed to submit tenant access request."
        }
      )
      _isBackendLoading.value = false
    }
  }

  fun createWallet(
    name: String,
    org: String,
    bal: Long,
    monthly: Long,
    perReq: Long,
    vendors: String,
    chains: String,
    approval: Boolean,
    currency: String = "USD"
  ) {
    if (currentUser.value == null) {
      _backendErrorMessage.value = "Sign in with Firebase to create a wallet in the backend ledger."
      return
    }
    if (!AgentWalletApiClient.isBackendConfigured()) {
      _backendErrorMessage.value = "Configure BACKEND_URL in Secrets to create backend wallets."
      return
    }
    viewModelScope.launch {
      _isBackendLoading.value = true
      _backendErrorMessage.value = null
      _backendStatusMessage.value = null

      val res = AgentWalletApiClient.createWallet(
        name = name,
        initialBalanceUnits = bal,
        monthlyLimitUnits = monthly,
        perRequestLimitUnits = perReq,
        allowedVendors = vendors,
        allowedChains = chains,
        requireApproval = approval,
        currency = currency
      )
      res.fold(
        onSuccess = { wallet ->
          repository.upsertWalletCache(wallet)
          _backendStatusMessage.value = "Created ${wallet.currency} backend wallet '${wallet.name}'."
          refreshBackendFinancialData()
        },
        onFailure = { err ->
          _backendErrorMessage.value = err.localizedMessage ?: "Failed to create wallet."
        }
      )
      _isBackendLoading.value = false
    }
  }

  fun depositFunds(walletId: String, amount: Long) {
    if (currentUser.value == null) {
      _backendErrorMessage.value = "Sign in with Firebase to fund a wallet."
      return
    }
    if (!AgentWalletApiClient.isBackendConfigured()) {
      _backendErrorMessage.value = "Configure BACKEND_URL in Secrets to fund backend wallets."
      return
    }
    val targetCurrency = wallets.value.find { it.id == walletId }?.currency ?: "USD"
    viewModelScope.launch {
      _isBackendLoading.value = true
      _backendErrorMessage.value = null
      _backendStatusMessage.value = null

      val idempotencyKey = "fund_${UUID.randomUUID()}"
      val res = AgentWalletApiClient.fundWallet(
        walletId = walletId,
        amountUnits = amount,
        currency = targetCurrency,
        idempotencyKey = idempotencyKey
      )
      res.fold(
        onSuccess = { updatedWallet ->
          repository.upsertWalletCache(updatedWallet)
          _backendStatusMessage.value = "Funded ${updatedWallet.currency} wallet '${updatedWallet.name}'."
          refreshBackendFinancialData()
        },
        onFailure = { err ->
          _backendErrorMessage.value = err.localizedMessage ?: "Funding failed."
        }
      )
      _isBackendLoading.value = false
    }
  }

  fun simulatePaymentChallenge(
    walletId: String,
    vendorName: String,
    requestedAmount: Long,
    protocol: String,
    chain: String
  ) {
    if (currentUser.value == null) {
      _lastPaymentResult.value = PaymentResult(
        status = "UNAUTHORIZED",
        receiptHash = "",
        details = "Sign in with Firebase to authorize payments against the PostgreSQL backend.",
        policyPassed = false
      )
      return
    }
    if (!AgentWalletApiClient.isBackendConfigured()) {
      _lastPaymentResult.value = PaymentResult(
        status = "UNCONFIGURED",
        receiptHash = "",
        details = "Configure BACKEND_URL in Secrets to authorize live payments.",
        policyPassed = false
      )
      return
    }

    val wallet = wallets.value.find { it.id == walletId }
    val walletCurrency = wallet?.currency?.uppercase() ?: "USD"
    if (walletCurrency == "USDC") {
      _lastPaymentResult.value = PaymentResult(
        status = "DENIED",
        receiptHash = "",
        details = "PayU settles fiat USD/PLN only. USDC blockchain settlement is disabled until live submission & confirmation verification are implemented.",
        policyPassed = false
      )
      return
    }

    viewModelScope.launch {
      _isBackendLoading.value = true
      _backendErrorMessage.value = null

      val idempotencyKey = "pay_${UUID.randomUUID()}"
      val result = AgentWalletApiClient.authorizePayment(
        walletId = walletId,
        vendorName = vendorName,
        amountUnits = requestedAmount,
        currency = walletCurrency,
        protocol = protocol,
        idempotencyKey = idempotencyKey
      )

      result.fold(
        onSuccess = { paymentResult ->
          _lastPaymentResult.value = paymentResult
          val uid = currentUser.value?.uid
          if (!uid.isNullOrBlank()) {
            // Non-authoritative Firestore audit/presentation mirror for History tab
            val walletName = wallet?.name ?: "Agent Wallet"
            firestoreService.saveTransaction(
              FirestoreTransaction(
                id = idempotencyKey,
                timestamp = System.currentTimeMillis(),
                walletId = walletId,
                agentName = walletName,
                vendorName = vendorName,
                amountUsdc = requestedAmount,
                currency = walletCurrency,
                protocol = protocol,
                chain = "PAYU",
                status = paymentResult.status,
                receiptHash = paymentResult.receiptHash,
                challengeDetails = paymentResult.details,
                userId = uid
              )
            )
          }
          refreshBackendFinancialData()
        },
        onFailure = { err ->
          _lastPaymentResult.value = PaymentResult(
            status = "ERROR",
            receiptHash = "",
            details = err.localizedMessage ?: "Payment authorization failed.",
            policyPassed = false
          )
        }
      )
      _isBackendLoading.value = false
    }
  }
}
