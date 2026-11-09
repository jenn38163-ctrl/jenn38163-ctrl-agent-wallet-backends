package com.agentwallet.os.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agentwallet.os.data.*
import com.agentwallet.os.firebase.*
import com.agentwallet.os.network.GeminiApiClient
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import java.security.MessageDigest

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

  init {
    fetchFcmToken()
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

  val currentUser: StateFlow<FirebaseUser?> = authService.authState
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authService.currentUser)

  private val _isAuthLoading = MutableStateFlow(false)
  val isAuthLoading: StateFlow<Boolean> = _isAuthLoading.asStateFlow()

  private val _authErrorMessage = MutableStateFlow<String?>(null)
  val authErrorMessage: StateFlow<String?> = _authErrorMessage.asStateFlow()

  private val _authSuccessMessage = MutableStateFlow<String?>(null)
  val authSuccessMessage: StateFlow<String?> = _authSuccessMessage.asStateFlow()

  val firestoreVaultAssets: StateFlow<List<FirestoreVaultAsset>> = firestoreService.getVaultAssetsFlow()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
          // Save user profile to Firestore
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
      val rawPayload = "$logId:$eventType:$description:$actor:$timestamp"
      val digest = MessageDigest.getInstance("SHA-256").digest(rawPayload.toByteArray())
      val hash = "SHA256:" + digest.joinToString("") { "%02x".format(it) }
      val log = AuditLogEntity(
        id = logId,
        timestamp = timestamp,
        eventType = eventType,
        description = description,
        actor = actor,
        cryptographicHash = hash
      )
      dao.insertAuditLog(log)

      // Also replicate to Firestore
      val cloudLog = FirestoreAuditLog(
        id = logId,
        timestamp = log.timestamp,
        eventType = eventType,
        description = description,
        actor = actor,
        cryptographicHash = hash,
        userId = currentUser.value?.uid ?: ""
      )
      firestoreService.saveAuditLog(cloudLog)
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
      ChatMessage("Hello! I am your Samsung ATW Key Generator Copilot. I can help you configure X.509 certificates, RSA private keys (2048/4096 bit), Certificate Signing Requests (CSRs), and security compliance guidelines for Authorized Testing Workshops. How can I assist you today?", false)
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
        append("Firebase Backend: Connected to wallet-54645\n")
        append("Current User: ${currentUser.value?.email ?: "Guest"}\n")
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

  // Legacy compatibility methods for existing tabs/screens
  private val _lastPaymentResult = MutableStateFlow<PaymentResult?>(null)
  val lastPaymentResult: StateFlow<PaymentResult?> = _lastPaymentResult.asStateFlow()

  fun clearPaymentResult() {
    _lastPaymentResult.value = null
  }

  fun simulatePaymentChallenge(walletId: String, vendorName: String, requestedAmount: Long, protocol: String, chain: String) {}
  fun createWallet(name: String, org: String, bal: Long, monthly: Long, perReq: Long, vendors: String, chains: String, approval: Boolean) {}
  fun depositFunds(walletId: String, amount: Long) {}
}
