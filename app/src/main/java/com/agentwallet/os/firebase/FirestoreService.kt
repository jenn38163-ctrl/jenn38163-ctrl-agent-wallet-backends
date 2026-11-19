package com.agentwallet.os.firebase

import com.agentwallet.os.utils.CurrencyUtils
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class FirestoreService(
  private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
  companion object {
    const val COLLECTION_VAULT = "vault_assets"
    const val COLLECTION_WALLETS = "wallets"
    const val COLLECTION_AUDIT_LOGS = "audit_logs"
    const val COLLECTION_TRANSACTIONS = "transactions"
    const val COLLECTION_USERS = "user_profiles"

    internal fun DocumentSnapshot.toFirestoreTransactionSafe(): FirestoreTransaction? {
      val data = this.data ?: return null
      val idVal = (data["id"] as? String)?.takeIf { it.isNotBlank() } ?: this.id
      val rawTimestamp = data["timestamp"] ?: data["createdAt"]
      val timestampMillis = when (rawTimestamp) {
        is Number -> rawTimestamp.toLong()
        is Timestamp -> rawTimestamp.toDate().time
        else -> System.currentTimeMillis()
      }
      val rawAmount = data["amountUsdc"] ?: data["amount"]
      val amountUsdc = when (rawAmount) {
        is Number -> rawAmount.toLong()
        is String -> CurrencyUtils.parseCurrencyInput(rawAmount)
        else -> 0L
      }
      val vendorName = (data["vendorName"] as? String)
        ?: (data["vendor"] as? String)
        ?: (data["recipient"] as? String)
        ?: "Unknown Vendor"

      return FirestoreTransaction(
        id = idVal,
        timestamp = timestampMillis,
        walletId = (data["walletId"] as? String).orEmpty(),
        agentName = (data["agentName"] as? String).orEmpty(),
        vendorName = vendorName,
        amountUsdc = amountUsdc,
        currency = (data["currency"] as? String)?.takeIf { it.isNotBlank() } ?: "USDC",
        debitAccountId = (data["debitAccountId"] as? String).orEmpty(),
        creditAccountId = (data["creditAccountId"] as? String).orEmpty(),
        protocol = (data["protocol"] as? String).orEmpty(),
        chain = (data["chain"] as? String).orEmpty(),
        status = (data["status"] as? String)?.takeIf { it.isNotBlank() } ?: "PENDING",
        receiptHash = (data["receiptHash"] as? String).orEmpty(),
        challengeDetails = (data["challengeDetails"] as? String).orEmpty(),
        userId = (data["userId"] as? String).orEmpty()
      )
    }
  }

  // Vault Assets
  suspend fun saveVaultAsset(asset: FirestoreVaultAsset): Result<Unit> {
    return try {
      firestore.collection(COLLECTION_VAULT)
        .document(asset.id)
        .set(asset, SetOptions.merge())
        .await()
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun deleteVaultAsset(assetId: String): Result<Unit> {
    return try {
      firestore.collection(COLLECTION_VAULT)
        .document(assetId)
        .delete()
        .await()
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  fun getVaultAssetsFlow(): Flow<List<FirestoreVaultAsset>> = callbackFlow {
    val listenerRegistration = firestore.collection(COLLECTION_VAULT)
      .orderBy("createdAt", Query.Direction.DESCENDING)
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snapshot?.documents?.mapNotNull { doc ->
          doc.toObject(FirestoreVaultAsset::class.java)
        } ?: emptyList()
        trySend(list)
      }
    awaitClose { listenerRegistration.remove() }
  }

  // Transactions (User-scoped queries)
  fun observeUserTransactions(userId: String): Flow<List<FirestoreTransaction>> {
    require(userId.isNotBlank()) { "Authenticated userId is required to query transactions." }
    return firestore.collection(COLLECTION_TRANSACTIONS)
      .whereEqualTo("userId", userId)
      .snapshots()
      .map { snapshot ->
        snapshot.documents
          .mapNotNull { doc -> doc.toFirestoreTransactionSafe() }
          .sortedByDescending { it.timestamp }
      }
      .catch { error ->
        val firestoreError = generateSequence(error) { it.cause }
          .filterIsInstance<FirebaseFirestoreException>()
          .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        handleFirestoreError(firestoreError ?: error, OperationType.LIST, COLLECTION_TRANSACTIONS)
        throw error
      }
  }

  suspend fun fetchUserTransactions(userId: String): Result<List<FirestoreTransaction>> {
    return try {
      require(userId.isNotBlank()) { "Authenticated userId is required to query transactions." }
      val snapshot = firestore.collection(COLLECTION_TRANSACTIONS)
        .whereEqualTo("userId", userId)
        .get()
        .await()
      val list = snapshot.documents
        .mapNotNull { doc -> doc.toFirestoreTransactionSafe() }
        .sortedByDescending { it.timestamp }
      Result.success(list)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.LIST, COLLECTION_TRANSACTIONS)
      Result.failure(e)
    }
  }

  suspend fun saveTransaction(tx: FirestoreTransaction): Result<Unit> {
    return try {
      require(tx.userId.isNotBlank()) { "userId is required to write a transaction." }
      val docRef = if (tx.id.isNotBlank()) {
        firestore.collection(COLLECTION_TRANSACTIONS).document(tx.id)
      } else {
        firestore.collection(COLLECTION_TRANSACTIONS).document()
      }
      val persisted = if (tx.id.isBlank()) tx.copy(id = docRef.id) else tx
      docRef.set(persisted, SetOptions.merge()).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, "$COLLECTION_TRANSACTIONS/${tx.id}")
      Result.failure(e)
    }
  }

  // Wallets
  suspend fun saveWallet(wallet: FirestoreWallet): Result<Unit> {
    return try {
      firestore.collection(COLLECTION_WALLETS)
        .document(wallet.id)
        .set(wallet, SetOptions.merge())
        .await()
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  fun getWalletsFlow(): Flow<List<FirestoreWallet>> = callbackFlow {
    val listenerRegistration = firestore.collection(COLLECTION_WALLETS)
      .orderBy("createdAt", Query.Direction.DESCENDING)
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snapshot?.documents?.mapNotNull { doc ->
          doc.toObject(FirestoreWallet::class.java)
        } ?: emptyList()
        trySend(list)
      }
    awaitClose { listenerRegistration.remove() }
  }

  // Audit Logs
  suspend fun saveAuditLog(log: FirestoreAuditLog): Result<Unit> {
    return try {
      firestore.collection(COLLECTION_AUDIT_LOGS)
        .document(log.id)
        .set(log, SetOptions.merge())
        .await()
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  fun getAuditLogsFlow(): Flow<List<FirestoreAuditLog>> = callbackFlow {
    val listenerRegistration = firestore.collection(COLLECTION_AUDIT_LOGS)
      .orderBy("timestamp", Query.Direction.DESCENDING)
      .limit(100)
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list = snapshot?.documents?.mapNotNull { doc ->
          doc.toObject(FirestoreAuditLog::class.java)
        } ?: emptyList()
        trySend(list)
      }
    awaitClose { listenerRegistration.remove() }
  }

  // User Profile
  suspend fun saveUserProfile(profile: UserProfile): Result<Unit> {
    return try {
      firestore.collection(COLLECTION_USERS)
        .document(profile.uid)
        .set(profile, SetOptions.merge())
        .await()
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun getUserProfile(uid: String): Result<UserProfile?> {
    return try {
      val doc = firestore.collection(COLLECTION_USERS).document(uid).get().await()
      Result.success(doc.toObject(UserProfile::class.java))
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
}

