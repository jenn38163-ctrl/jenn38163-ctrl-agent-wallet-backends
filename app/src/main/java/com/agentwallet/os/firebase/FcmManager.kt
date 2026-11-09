package com.agentwallet.os.firebase

import android.content.Context
import android.util.Log
import com.agentwallet.os.BuildConfig
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

object FcmManager {
  private const val TAG = "FcmManager"

  suspend fun getDeviceToken(): String? {
    return try {
      val token = FirebaseMessaging.getInstance().token.await()
      AppFirebaseMessagingService.updateToken(token)
      token
    } catch (e: Exception) {
      Log.e(TAG, "Failed to fetch FCM device token", e)
      null
    }
  }

  suspend fun subscribeToTopic(topic: String): Boolean {
    return try {
      FirebaseMessaging.getInstance().subscribeToTopic(topic).await()
      Log.d(TAG, "Subscribed to FCM topic: $topic")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to subscribe to topic: $topic", e)
      false
    }
  }

  suspend fun unsubscribeFromTopic(topic: String): Boolean {
    return try {
      FirebaseMessaging.getInstance().unsubscribeFromTopic(topic).await()
      Log.d(TAG, "Unsubscribed from FCM topic: $topic")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to unsubscribe from topic: $topic", e)
      false
    }
  }
}
