package com.example.data

import kotlinx.coroutines.flow.Flow
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.UUID
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class WalletRepository(private val dao: WalletDao) {
  val allWallets: Flow<List<AgentWalletEntity>> = dao.getAllWallets()
  val allTransactions: Flow<List<LedgerTransactionEntity>> = dao.getAllTransactions()
  val allAuditLogs: Flow<List<AuditLogEntity>> = dao.getAllAuditLogs()
  val allAtwAssets: Flow<List<AtwAssetEntity>> = dao.getAllAtwAssets()

  suspend fun insertWallet(wallet: AgentWalletEntity) {
    dao.insertWallet(wallet)
    dao.insertAuditLog(
      AuditLogEntity(
        id = "audit_" + UUID.randomUUID().toString().substring(0, 8),
        timestamp = System.currentTimeMillis(),
        eventType = "WALLET_CREATED",
        description = "Created sovereign agent wallet '${wallet.name}' for organization '${wallet.organization}'",
        actor = wallet.didIdentity,
        cryptographicHash = "SHA256:" + UUID.randomUUID().toString()
      )
    )
  }

  suspend fun generatePrivateKeyAsset(name: String, keySize: Int, encrypted: Boolean) {
    val alias = "key_" + UUID.randomUUID().toString().substring(0, 8)
    val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
    val parameterSpec = KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    ).setKeySize(keySize)
     .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
     .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
     .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
     .build()
    kpg.initialize(parameterSpec)
    val kp = kpg.generateKeyPair()

    val b64Pub = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
    val privateRef = "-----BEGIN PRIVATE KEY REFERENCE-----\n[SECURED_IN_ANDROID_KEYSTORE]\nAlias: $alias\n-----END PRIVATE KEY REFERENCE-----"
    val pem = "$privateRef\n\n-----BEGIN PUBLIC KEY-----\n" + b64Pub.chunked(64).joinToString("\n") + "\n-----END PUBLIC KEY-----"

    val asset = AtwAssetEntity(
      id = alias,
      name = name,
      assetType = "PRIVATE_KEY",
      keySize = keySize,
      encrypted = encrypted,
      commonName = "",
      organization = "",
      organizationalUnit = "",
      country = "",
      state = "",
      locality = "",
      email = "",
      validityDays = 0,
      pemData = pem,
      createdAt = System.currentTimeMillis()
    )
    dao.insertAtwAsset(asset)
  }

  suspend fun generateCsrAsset(name: String, keySize: Int, cn: String, o: String, ou: String, c: String, st: String, l: String, email: String) {
    val alias = "csr_" + UUID.randomUUID().toString().substring(0, 8)
    val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
    val parameterSpec = KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
    ).setKeySize(keySize)
     .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
     .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
     .build()
    kpg.initialize(parameterSpec)
    val kp = kpg.generateKeyPair()
    val dn = "CN=$cn, O=$o, OU=$ou, C=$c, ST=$st, L=$l, EMAIL=$email"
    // Production Blocked: CSR Generation moved to Backend Boundary
    val raw = "ATW_CSR:PENDING_BACKEND_GENERATION"
    val b64 = Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)
    val pem = "-----BEGIN CERTIFICATE REQUEST-----\n" + b64.chunked(64).joinToString("\n") + "\n-----END CERTIFICATE REQUEST-----\n" +
              "-----BEGIN PRIVATE KEY REFERENCE-----\n[SECURED_IN_ANDROID_KEYSTORE]\nAlias: $alias\n-----END PRIVATE KEY REFERENCE-----"

    val asset = AtwAssetEntity(
      id = alias,
      name = name,
      assetType = "CSR",
      keySize = keySize,
      encrypted = false,
      commonName = cn,
      organization = o,
      organizationalUnit = ou,
      country = c,
      state = st,
      locality = l,
      email = email,
      validityDays = 0,
      pemData = pem,
      createdAt = System.currentTimeMillis()
    )
    dao.insertAtwAsset(asset)
  }

  suspend fun generateSelfSignedCertAsset(name: String, keySize: Int, cn: String, o: String, ou: String, c: String, st: String, l: String, email: String, validityDays: Int) {
    val alias = "cert_" + UUID.randomUUID().toString().substring(0, 8)
    val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
    val parameterSpec = KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
    ).setKeySize(keySize)
     .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
     .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
     .build()
    kpg.initialize(parameterSpec)
    val kp = kpg.generateKeyPair()
    val dn = "CN=$cn, O=$o, OU=$ou, C=$c, ST=$st, L=$l, EMAIL=$email"
    val raw = "ATW_CERT:$dn:DAYS_$validityDays:${java.util.Arrays.toString(kp.public.encoded)}"
    val b64Cert = Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP)

    val pem = "-----BEGIN CERTIFICATE-----\n" + b64Cert.chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n" +
              "-----BEGIN PRIVATE KEY REFERENCE-----\n[SECURED_IN_ANDROID_KEYSTORE]\nAlias: $alias\n-----END PRIVATE KEY REFERENCE-----"

    val asset = AtwAssetEntity(
      id = alias,
      name = name,
      assetType = "SELF_SIGNED_CERT",
      keySize = keySize,
      encrypted = false,
      commonName = cn,
      organization = o,
      organizationalUnit = ou,
      country = c,
      state = st,
      locality = l,
      email = email,
      validityDays = validityDays,
      pemData = pem,
      createdAt = System.currentTimeMillis()
    )
    dao.insertAtwAsset(asset)
  }

  suspend fun generateKeyAndCsrAsset(name: String, keySize: Int, cn: String, o: String, ou: String, c: String, st: String, l: String, email: String) {
    val alias = "bundle_" + UUID.randomUUID().toString().substring(0, 8)
    val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
    val parameterSpec = KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
    ).setKeySize(keySize)
     .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
     .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
     .build()
    kpg.initialize(parameterSpec)
    val kp = kpg.generateKeyPair()
    val dn = "CN=$cn, O=$o, OU=$ou, C=$c, ST=$st, L=$l, EMAIL=$email"
    // Production Blocked: CSR Generation moved to Backend Boundary
    // The previous implementation used simulated ATW_CSR string concatenation.
    // Real PKI generation/signing is now handled securely out-of-band by the backend.
    
    val rawCsr = "ATW_CSR:PENDING_BACKEND_GENERATION"
    val b64Csr = Base64.encodeToString(rawCsr.toByteArray(), Base64.NO_WRAP)

    val pem = "-----BEGIN CERTIFICATE REQUEST-----\n" + b64Csr.chunked(64).joinToString("\n") + "\n-----END CERTIFICATE REQUEST-----\n" +
              "-----BEGIN PRIVATE KEY REFERENCE-----\n[SECURED_IN_ANDROID_KEYSTORE]\nAlias: $alias\n-----END PRIVATE KEY REFERENCE-----"

    val asset = AtwAssetEntity(
      id = alias,
      name = name,
      assetType = "KEY_CSR_BUNDLE",
      keySize = keySize,
      encrypted = false,
      commonName = cn,
      organization = o,
      organizationalUnit = ou,
      country = c,
      state = st,
      locality = l,
      email = email,
      validityDays = 0,
      pemData = pem,
      createdAt = System.currentTimeMillis()
    )
    dao.insertAtwAsset(asset)
  }

  suspend fun deleteAsset(assetId: String) {
    dao.deleteAtwAsset(assetId)
  }

  suspend fun processPaymentChallenge(
    wallet: AgentWalletEntity,
    vendorName: String,
    requestedAmount: Double,
    protocol: String,
    chain: String
  ): PaymentResult {
    // Policy Evaluation Engine
    val vendorAllowed = wallet.allowedVendors.split(",").map { it.trim() }.any { 
      vendorName.contains(it, ignoreCase = true) || it.contains(vendorName, ignoreCase = true) 
    }
    val chainAllowed = wallet.allowedChains.split(",").map { it.trim() }.any { 
      it.equals(chain, ignoreCase = true) 
    }
    val underPerRequestLimit = requestedAmount <= wallet.perRequestLimit
    val sufficientBalance = wallet.usdcBalance >= requestedAmount
    val monthlyRemaining = (wallet.monthlyLimit - wallet.monthlySpent)
    val underMonthlyLimit = requestedAmount <= monthlyRemaining

    val passed = vendorAllowed && chainAllowed && underPerRequestLimit && sufficientBalance && underMonthlyLimit

    val status = if (passed) {
      if (wallet.requireApproval && requestedAmount > 10.0) "PENDING_APPROVAL" else "APPROVED"
    } else {
      "DENIED"
    }

    val txId = "tx_" + UUID.randomUUID().toString().substring(0, 8)
    val receiptHash = "0x" + UUID.randomUUID().toString().replace("-", "").substring(0, 32)
    val now = System.currentTimeMillis()

    val debitAccountId = "asset:usdc:${wallet.id}"
    val creditAccountId = "liability:vendor:${vendorName.lowercase().replace(" ", "_")}"

    val challengeDetails = buildString {
      append("Protocol: $protocol | Chain: $chain | Vendor: $vendorName | ")
      append("PerReqLimit: ${wallet.perRequestLimit} ($underPerRequestLimit) | ")
      append("VendorApproved: $vendorAllowed | Balance: ${wallet.usdcBalance} ($sufficientBalance) | ")
      append("Status: $status")
    }

    val tx = LedgerTransactionEntity(
      id = txId,
      timestamp = now,
      walletId = wallet.id,
      agentName = wallet.name,
      vendorName = vendorName,
      amountUsdc = requestedAmount,
      debitAccountId = debitAccountId,
      creditAccountId = creditAccountId,
      protocol = protocol,
      chain = chain,
      status = status,
      receiptHash = receiptHash,
      challengeDetails = challengeDetails
    )

    dao.insertTransaction(tx)

    if (status == "APPROVED") {
      dao.deductBalance(wallet.id, requestedAmount)
    }

    dao.insertAuditLog(
      AuditLogEntity(
        id = "audit_" + UUID.randomUUID().toString().substring(0, 8),
        timestamp = now,
        eventType = if (status == "APPROVED") "PAYMENT_SUCCESS" else "POLICY_DENIAL",
        description = "Evaluated 402 challenge for $vendorName ($requestedAmount USDC) via $protocol on $chain. Result: $status",
        actor = wallet.didIdentity,
        cryptographicHash = receiptHash
      )
    )

    return PaymentResult(status, receiptHash, challengeDetails, passed)
  }

  suspend fun depositToWallet(walletId: String, amount: Double) {
    dao.addBalance(walletId, amount)
    dao.insertAuditLog(
      AuditLogEntity(
        id = "audit_" + UUID.randomUUID().toString().substring(0, 8),
        timestamp = System.currentTimeMillis(),
        eventType = "DEPOSIT",
        description = "Deposited $amount USDC into agent wallet $walletId",
        actor = "Organization Treasury",
        cryptographicHash = "SHA256:" + UUID.randomUUID().toString()
      )
    )
  }
}

data class PaymentResult(
  val status: String,
  val receiptHash: String,
  val details: String,
  val policyPassed: Boolean
)
