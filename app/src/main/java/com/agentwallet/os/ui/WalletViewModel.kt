package com.agentwallet.os.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agentwallet.os.data.*
import com.agentwallet.os.network.GeminiApiClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatMessage(
  val text: String,
  val isUser: Boolean,
  val timestamp: Long = System.currentTimeMillis()
)

data class GPayKeyPair(
  val id: String,
  val name: String,
  val privateKey: String,
  val publicKey: String,
  val createdAt: Long = System.currentTimeMillis(),
  val isActive: Boolean = true
)

class WalletViewModel(application: Application) : AndroidViewModel(application) {
  private val dao = WalletDatabase.getDatabase(application).walletDao()
  private val repository = WalletRepository(dao)

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
        privateKey = "PENDING_SECURE_PROVISIONING",
        publicKey = "BOdoXP+9Aq473SnGwg3JU1aiNpsd9vH2ognq4PtDtlLGa3Kj8TPf+jaQNPyDSkh3JUhiS0KyrrlWhAgNZKHYF2Y=",
        isActive = true
      ),
      GPayKeyPair(
        id = "key_secondary_02",
        name = "Secondary Rotation Key (Grace Period)",
        privateKey = "PENDING_SECURE_PROVISIONING_2",
        publicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE/1+3HBVSbdv+j7NaArdgMyoSAM43yRydzqdg1TxodSzA96Dj4Mc1EiKroxxunavVIvdxGnJeFViTzFvzFRxyCw==",
        isActive = true
      )
    )
  )
  val gpayKeyPairs: StateFlow<List<GPayKeyPair>> = _gpayKeyPairs.asStateFlow()

  fun addGPayKeyPair(name: String, privateKey: String, publicKey: String) {
    val newPair = GPayKeyPair(
      id = "key_" + UUID.randomUUID().toString().substring(0, 8),
      name = name,
      privateKey = privateKey,
      publicKey = publicKey,
      isActive = true
    )
    _gpayKeyPairs.value = listOf(newPair) + _gpayKeyPairs.value
    viewModelScope.launch {
      dao.insertAuditLog(
        AuditLogEntity(
          id = "audit_" + UUID.randomUUID().toString().substring(0, 8),
          timestamp = System.currentTimeMillis(),
          eventType = "KEY_ROTATION",
          description = "Added Google Pay decryption key pair '$name' for graceful key rotation",
          actor = "did:agent:merchant:admin",
          cryptographicHash = "SHA256:" + UUID.randomUUID().toString()
        )
      )
    }
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
      dao.insertAuditLog(
        AuditLogEntity(
          id = "audit_" + UUID.randomUUID().toString().substring(0, 8),
          timestamp = System.currentTimeMillis(),
          eventType = eventType,
          description = description,
          actor = "did:agent:enterprise:system",
          cryptographicHash = "SHA256:" + UUID.randomUUID().toString()
        )
      )
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
    }
  }

  fun generateCsr(name: String, keySize: Int, cn: String, o: String, ou: String, c: String, st: String, l: String, email: String) {
    viewModelScope.launch {
      repository.generateCsrAsset(name, keySize, cn, o, ou, c, st, l, email)
    }
  }

  fun generateSelfSignedCert(name: String, keySize: Int, cn: String, o: String, ou: String, c: String, st: String, l: String, email: String, validityDays: Int) {
    viewModelScope.launch {
      repository.generateSelfSignedCertAsset(name, keySize, cn, o, ou, c, st, l, email, validityDays)
    }
  }

  fun generateKeyAndCsr(name: String, keySize: Int, cn: String, o: String, ou: String, c: String, st: String, l: String, email: String) {
    viewModelScope.launch {
      repository.generateKeyAndCsrAsset(name, keySize, cn, o, ou, c, st, l, email)
    }
  }

  fun deleteAsset(assetId: String) {
    viewModelScope.launch {
      repository.deleteAsset(assetId)
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
