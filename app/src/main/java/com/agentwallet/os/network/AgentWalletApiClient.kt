package com.agentwallet.os.network

import com.agentwallet.os.BuildConfig
import com.agentwallet.os.data.AgentWalletEntity
import com.agentwallet.os.data.AuditLogEntity
import com.agentwallet.os.data.LedgerTransactionEntity
import com.agentwallet.os.data.PaymentResult
import com.agentwallet.os.utils.CurrencyUtils
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class TenantProvisioningInfo(
  val subject: String,
  val email: String?,
  val organizationId: String,
  val role: String,
  val provisioningStatus: String,
  val scopes: List<String>,
  val requestedOrganization: String?
)

object AgentWalletApiClient {
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

  fun resolvedBackendUrl(): String = BuildConfig.BACKEND_URL.trim().trimEnd('/')

  fun isBackendConfigured(): Boolean {
    val url = resolvedBackendUrl()
    return url.isNotBlank() && url != "http://localhost:8080"
  }

  private suspend fun requireFirebaseIdToken(): String {
    val user = FirebaseAuth.getInstance().currentUser
      ?: throw IllegalStateException("Please sign in with Firebase to access your backend ledger.")
    val tokenResult = user.getIdToken(false).await()
    return tokenResult.token
      ?: throw IllegalStateException("Unable to obtain Firebase ID token.")
  }

  private fun parseIsoTimestampMillis(raw: String?): Long {
    if (raw.isNullOrBlank()) return System.currentTimeMillis()
    val patterns = listOf(
      "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
      "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
      "yyyy-MM-dd'T'HH:mm:ss'Z'"
    )
    for (pattern in patterns) {
      try {
        val sdf = SimpleDateFormat(pattern, Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val date = sdf.parse(raw)
        if (date != null) return date.time
      } catch (_: Exception) {
      }
    }
    return System.currentTimeMillis()
  }

  private fun JSONObject.toAgentWalletEntity(): AgentWalletEntity {
    return AgentWalletEntity(
      id = optString("id"),
      name = optString("name"),
      organization = optString("organization"),
      usdcBalance = CurrencyUtils.parseCurrencyInput(optString("balance", "0")),
      monthlyLimit = CurrencyUtils.parseCurrencyInput(optString("monthly_limit", "0")),
      monthlySpent = CurrencyUtils.parseCurrencyInput(optString("monthly_spent", "0")),
      perRequestLimit = CurrencyUtils.parseCurrencyInput(optString("per_request_limit", "0")),
      allowedVendors = optString("allowed_vendors", "*"),
      allowedChains = optString("allowed_chains", "PAYU"),
      requireApproval = optBoolean("require_approval", false),
      didIdentity = optString("did_identity", ""),
      currency = optString("currency", "USD").trim().uppercase(Locale.US).ifBlank { "USD" }
    )
  }

  suspend fun fetchMe(): Result<TenantProvisioningInfo> = withContext(Dispatchers.IO) {
    runCatching {
      val baseUrl = resolvedBackendUrl()
      require(baseUrl.isNotBlank()) { "BACKEND_URL is not configured." }
      val idToken = requireFirebaseIdToken()

      val request = Request.Builder()
        .url("$baseUrl/v1/me")
        .header("Authorization", "Bearer $idToken")
        .get()
        .build()

      client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          throw IllegalStateException(extractErrorMessage(raw, response.code))
        }
        val json = JSONObject(raw)
        val scopesArray = json.optJSONArray("scopes")
        val scopes = buildList {
          if (scopesArray != null) {
            for (i in 0 until scopesArray.length()) {
              val item = scopesArray.optString(i).trim()
              if (item.isNotEmpty()) add(item)
            }
          }
        }
        TenantProvisioningInfo(
          subject = json.optString("subject"),
          email = json.optString("email").ifBlank { null },
          organizationId = json.optString("organizationId", "unassigned"),
          role = json.optString("role", "PENDING_USER"),
          provisioningStatus = json.optString("provisioningStatus", "PENDING_APPROVAL"),
          scopes = scopes,
          requestedOrganization = json.optString("requestedOrganization").ifBlank { null }
        )
      }
    }
  }

  suspend fun requestTenantAccess(organizationName: String): Result<TenantProvisioningInfo> =
    withContext(Dispatchers.IO) {
      runCatching {
        val baseUrl = resolvedBackendUrl()
        require(baseUrl.isNotBlank()) { "BACKEND_URL is not configured." }
        val idToken = requireFirebaseIdToken()

        val payload = JSONObject().apply {
          put("organizationName", organizationName.trim())
        }

        val request = Request.Builder()
          .url("$baseUrl/v1/tenant-access/request")
          .header("Authorization", "Bearer $idToken")
          .post(payload.toString().toRequestBody(jsonMediaType))
          .build()

        client.newCall(request).execute().use { response ->
          val raw = response.body?.string().orEmpty()
          if (!response.isSuccessful) {
            throw IllegalStateException(extractErrorMessage(raw, response.code))
          }
        }

        fetchMe().getOrThrow()
      }
    }

  suspend fun fetchWallets(): Result<List<AgentWalletEntity>> = withContext(Dispatchers.IO) {
    runCatching {
      val baseUrl = resolvedBackendUrl()
      require(baseUrl.isNotBlank()) { "BACKEND_URL is not configured." }
      val idToken = requireFirebaseIdToken()

      val request = Request.Builder()
        .url("$baseUrl/v1/wallets")
        .header("Authorization", "Bearer $idToken")
        .get()
        .build()

      client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          throw IllegalStateException(extractErrorMessage(raw, response.code))
        }
        val arr = JSONObject(raw).optJSONArray("wallets")
        val list = mutableListOf<AgentWalletEntity>()
        if (arr != null) {
          for (i in 0 until arr.length()) {
            list.add(arr.getJSONObject(i).toAgentWalletEntity())
          }
        }
        list
      }
    }
  }

  suspend fun createWallet(
    name: String,
    initialBalanceUnits: Long,
    monthlyLimitUnits: Long,
    perRequestLimitUnits: Long,
    allowedVendors: String,
    allowedChains: String,
    requireApproval: Boolean,
    currency: String = "USD"
  ): Result<AgentWalletEntity> = withContext(Dispatchers.IO) {
    runCatching {
      val baseUrl = resolvedBackendUrl()
      require(baseUrl.isNotBlank()) { "BACKEND_URL is not configured." }
      val idToken = requireFirebaseIdToken()

      val payload = JSONObject().apply {
        put("name", name)
        put("currency", currency.trim().uppercase(Locale.US).ifBlank { "USD" })
        put("initialBalance", CurrencyUtils.formatDecimalForApi(initialBalanceUnits))
        put("monthlyLimit", CurrencyUtils.formatDecimalForApi(monthlyLimitUnits))
        put("perRequestLimit", CurrencyUtils.formatDecimalForApi(perRequestLimitUnits))
        put("allowedVendors", allowedVendors)
        put("allowedChains", allowedChains)
        put("requireApproval", requireApproval)
      }

      val request = Request.Builder()
        .url("$baseUrl/v1/wallets")
        .header("Authorization", "Bearer $idToken")
        .post(payload.toString().toRequestBody(jsonMediaType))
        .build()

      client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          throw IllegalStateException(extractErrorMessage(raw, response.code))
        }
        JSONObject(raw).toAgentWalletEntity()
      }
    }
  }

  suspend fun fundWallet(
    walletId: String,
    amountUnits: Long,
    currency: String = "USD",
    idempotencyKey: String
  ): Result<AgentWalletEntity> = withContext(Dispatchers.IO) {
    runCatching {
      val baseUrl = resolvedBackendUrl()
      require(baseUrl.isNotBlank()) { "BACKEND_URL is not configured." }
      val idToken = requireFirebaseIdToken()

      val normalizedCurrency = currency.trim().uppercase(Locale.US).ifBlank { "USD" }
      val payload = JSONObject().apply {
        put("amount", CurrencyUtils.formatDecimalForApi(amountUnits))
        put("currency", normalizedCurrency)
        put("source", "android_treasury_deposit")
      }

      val request = Request.Builder()
        .url("$baseUrl/v1/wallets/$walletId/fund")
        .header("Authorization", "Bearer $idToken")
        .header("Idempotency-Key", idempotencyKey)
        .post(payload.toString().toRequestBody(jsonMediaType))
        .build()

      client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          throw IllegalStateException(extractErrorMessage(raw, response.code))
        }
        val walletJson = JSONObject(raw).getJSONObject("wallet")
        walletJson.toAgentWalletEntity()
      }
    }
  }

  suspend fun authorizePayment(
    walletId: String,
    vendorName: String,
    amountUnits: Long,
    currency: String = "USD",
    protocol: String,
    idempotencyKey: String
  ): Result<PaymentResult> = withContext(Dispatchers.IO) {
    runCatching {
      val baseUrl = resolvedBackendUrl()
      require(baseUrl.isNotBlank()) { "BACKEND_URL is not configured." }
      val idToken = requireFirebaseIdToken()

      val normalizedCurrency = currency.trim().uppercase(Locale.US).ifBlank { "USD" }
      val payload = JSONObject().apply {
        put("walletId", walletId)
        put("vendor", vendorName)
        put("amount", CurrencyUtils.formatDecimalForApi(amountUnits))
        put("currency", normalizedCurrency)
        put("protocol", protocol)
        put("provider", "PAYU")
      }

      val request = Request.Builder()
        .url("$baseUrl/v1/payments/authorize")
        .header("Authorization", "Bearer $idToken")
        .header("Idempotency-Key", idempotencyKey)
        .post(payload.toString().toRequestBody(jsonMediaType))
        .build()

      client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        if (!response.isSuccessful) {
          val errMsg = extractErrorMessage(raw, response.code)
          return@runCatching PaymentResult(
            status = obj?.optString("status", "DENIED") ?: "DENIED",
            receiptHash = "",
            details = errMsg,
            policyPassed = false
          )
        }
        val json = obj ?: JSONObject()
        val status = json.optString("status", "PENDING")
        val auditHash = json.optString("auditHash")
        val intentId = json.optString("settlementIntentId")
        val entryId = json.optString("journalEntryId")
        val confirmedCurrency = json.optString("currency", normalizedCurrency)
        PaymentResult(
          status = status,
          receiptHash = auditHash,
          details = "Ledger Entry: $entryId\nSettlement Intent: $intentId (PAYU Outbox, $confirmedCurrency)\nProtocol: $protocol | Vendor: $vendorName",
          policyPassed = true
        )
      }
    }
  }

  suspend fun fetchTransactions(): Result<List<LedgerTransactionEntity>> = withContext(Dispatchers.IO) {
    runCatching {
      val baseUrl = resolvedBackendUrl()
      require(baseUrl.isNotBlank()) { "BACKEND_URL is not configured." }
      val idToken = requireFirebaseIdToken()

      val request = Request.Builder()
        .url("$baseUrl/v1/transactions")
        .header("Authorization", "Bearer $idToken")
        .get()
        .build()

      client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          throw IllegalStateException(extractErrorMessage(raw, response.code))
        }
        val arr = JSONObject(raw).optJSONArray("transactions")
        val list = mutableListOf<LedgerTransactionEntity>()
        if (arr != null) {
          for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val reference = item.optString("reference")
            val vendor = reference.substringAfter("payment:", reference)
            val effectiveStatus = item.optString("settlement_status").ifBlank {
              item.optString("ledger_status", "PENDING")
            }
            val providerRef = item.optString("provider_ref")
            val txCurrency = item.optString("currency", "USD").trim().uppercase(Locale.US).ifBlank { "USD" }
            val details = buildString {
              append("Ledger: ${item.optString("ledger_status")} ($txCurrency)")
              if (item.optString("provider").isNotBlank()) {
                append(" • Provider: ${item.optString("provider")}")
              }
              if (providerRef.isNotBlank()) {
                append(" • Ref: $providerRef")
              }
            }
            list.add(
              LedgerTransactionEntity(
                id = item.optString("id"),
                timestamp = parseIsoTimestampMillis(item.optString("created_at")),
                walletId = item.optString("wallet_id"),
                agentName = item.optString("wallet_name", "Agent Wallet"),
                vendorName = vendor,
                amountUsdc = CurrencyUtils.parseCurrencyInput(item.optString("amount", "0")),
                debitAccountId = item.optString("debit_account_id"),
                creditAccountId = item.optString("credit_account_id"),
                protocol = item.optString("protocol"),
                chain = item.optString("provider").ifBlank { "POSTGRES_LEDGER" },
                status = effectiveStatus,
                receiptHash = providerRef.ifBlank { item.optString("settlement_intent_id").ifBlank { item.optString("id") } },
                challengeDetails = details,
                currency = txCurrency
              )
            )
          }
        }
        list
      }
    }
  }

  suspend fun fetchAuditEvents(): Result<List<AuditLogEntity>> = withContext(Dispatchers.IO) {
    runCatching {
      val baseUrl = resolvedBackendUrl()
      require(baseUrl.isNotBlank()) { "BACKEND_URL is not configured." }
      val idToken = requireFirebaseIdToken()

      val request = Request.Builder()
        .url("$baseUrl/v1/audit-events")
        .header("Authorization", "Bearer $idToken")
        .get()
        .build()

      client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          throw IllegalStateException(extractErrorMessage(raw, response.code))
        }
        val arr = JSONObject(raw).optJSONArray("auditEvents")
        val list = mutableListOf<AuditLogEntity>()
        if (arr != null) {
          for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            list.add(
              AuditLogEntity(
                id = item.optString("id"),
                timestamp = parseIsoTimestampMillis(item.optString("created_at")),
                eventType = item.optString("event_type"),
                description = item.optString("payload"),
                actor = item.optString("actor"),
                cryptographicHash = "SHA256:${item.optString("event_hash")}"
              )
            )
          }
        }
        list
      }
    }
  }

  private fun extractErrorMessage(raw: String, httpCode: Int): String {
    return runCatching {
      val json = JSONObject(raw)
      json.optString("message").ifBlank {
        json.optString("error").ifBlank { "HTTP $httpCode" }
      }
    }.getOrDefault("HTTP $httpCode${if (raw.isNotBlank()) ": $raw" else ""}")
  }
}
