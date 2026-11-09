package com.agentwallet.os.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
