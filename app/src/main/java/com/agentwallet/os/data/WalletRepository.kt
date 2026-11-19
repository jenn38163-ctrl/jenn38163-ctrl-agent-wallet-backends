package com.agentwallet.os.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.agentwallet.os.crypto.X509DerBuilder
import kotlinx.coroutines.flow.Flow
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.UUID

class WalletRepository(private val dao: WalletDao) {
  val allWallets: Flow<List<AgentWalletEntity>> = dao.getAllWallets()
  val allTransactions: Flow<List<LedgerTransactionEntity>> = dao.getAllTransactions()
  val allAuditLogs: Flow<List<AuditLogEntity>> = dao.getAllAuditLogs()
  val allAtwAssets: Flow<List<AtwAssetEntity>> = dao.getAllAtwAssets()

  companion object {
    fun computeSha256Hex(raw: String): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
      return "SHA256:" + digest.joinToString("") { "%02x".format(it) }
    }
  }

  suspend fun replaceBackendWallets(wallets: List<AgentWalletEntity>) {
    dao.replaceWallets(wallets)
  }

  suspend fun upsertWalletCache(wallet: AgentWalletEntity) {
    dao.insertWallet(wallet)
  }

  suspend fun replaceBackendTransactions(transactions: List<LedgerTransactionEntity>) {
    dao.replaceTransactions(transactions)
  }

  suspend fun replaceBackendAuditLogs(logs: List<AuditLogEntity>) {
    dao.replaceAuditLogs(logs)
  }

  suspend fun clearFinancialCache() {
    dao.deleteAllWallets()
    dao.deleteAllTransactions()
    dao.deleteAllAuditLogs()
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

    val b64Pub = X509DerBuilder.encodeBase64(kp.public.encoded)
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
    val csrPem = X509DerBuilder.generateCsrPem(
      keyPair = kp,
      commonName = cn,
      organization = o,
      organizationalUnit = ou,
      country = c,
      state = st,
      locality = l,
      email = email
    )
    val pem = "$csrPem\n-----BEGIN PRIVATE KEY REFERENCE-----\n[SECURED_IN_ANDROID_KEYSTORE]\nAlias: $alias\n-----END PRIVATE KEY REFERENCE-----"

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
    val certPem = X509DerBuilder.generateSelfSignedCertificatePem(
      keyPair = kp,
      commonName = cn,
      organization = o,
      organizationalUnit = ou,
      country = c,
      state = st,
      locality = l,
      email = email,
      validityDays = validityDays
    )

    val pem = "$certPem\n-----BEGIN PRIVATE KEY REFERENCE-----\n[SECURED_IN_ANDROID_KEYSTORE]\nAlias: $alias\n-----END PRIVATE KEY REFERENCE-----"

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
    val csrPem = X509DerBuilder.generateCsrPem(
      keyPair = kp,
      commonName = cn,
      organization = o,
      organizationalUnit = ou,
      country = c,
      state = st,
      locality = l,
      email = email
    )

    val pem = "$csrPem\n-----BEGIN PRIVATE KEY REFERENCE-----\n[SECURED_IN_ANDROID_KEYSTORE]\nAlias: $alias\n-----END PRIVATE KEY REFERENCE-----"

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
}

data class PaymentResult(
  val status: String,
  val receiptHash: String,
  val details: String,
  val policyPassed: Boolean
)
