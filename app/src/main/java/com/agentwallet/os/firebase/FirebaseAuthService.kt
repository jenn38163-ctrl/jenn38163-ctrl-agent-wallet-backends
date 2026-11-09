package com.agentwallet.os.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirebaseAuthService(
  private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
  companion object {
    const val FIREBASE_PROJECT_ID = "wallet-54645"
    const val FIREBASE_APP_ID = "1:4985888379:android:3614380906847bfd244cde"
  }

  val currentUser: FirebaseUser?
    get() = auth.currentUser

  val authState: Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
      trySend(firebaseAuth.currentUser)
    }
    auth.addAuthStateListener(listener)
    // Initial emit
    trySend(auth.currentUser)
    awaitClose {
      auth.removeAuthStateListener(listener)
    }
  }

  suspend fun signUpWithEmailPassword(email: String, password: String, displayName: String = ""): Result<FirebaseUser> {
    return try {
      val authResult = auth.createUserWithEmailAndPassword(email.trim(), password).await()
      val user = authResult.user ?: throw Exception("User creation failed: null user")
      if (displayName.isNotBlank()) {
        val profileUpdates = UserProfileChangeRequest.Builder()
          .setDisplayName(displayName.trim())
          .build()
        user.updateProfile(profileUpdates).await()
      }
      Result.success(user)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun signInWithEmailPassword(email: String, password: String): Result<FirebaseUser> {
    return try {
      val authResult = auth.signInWithEmailAndPassword(email.trim(), password).await()
      val user = authResult.user ?: throw Exception("Sign in failed: null user")
      Result.success(user)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun sendPasswordResetEmail(email: String): Result<Unit> {
    return try {
      auth.sendPasswordResetEmail(email.trim()).await()
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  fun signOut() {
    auth.signOut()
  }
}
