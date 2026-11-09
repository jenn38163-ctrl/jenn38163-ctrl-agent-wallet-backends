package com.agentwallet.os.firebase

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.agentwallet.os.MainActivity
import com.agentwallet.os.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FcmNotificationMessage(
  val id: String = System.currentTimeMillis().toString(),
  val title: String,
  val body: String,
  val data: Map<String, String> = emptyMap(),
  val timestamp: Long = System.currentTimeMillis()
)

class AppFirebaseMessagingService : FirebaseMessagingService() {

  companion object {
    private const val TAG = "AppFCMService"
    const val CHANNEL_ID = "agent_wallet_fcm_channel"
    const val CHANNEL_NAME = "Agent Wallet Notifications"

    private val _deviceToken = MutableStateFlow<String?>(null)
    val deviceToken = _deviceToken.asStateFlow()

    private val _incomingMessages = MutableStateFlow<List<FcmNotificationMessage>>(emptyList())
    val incomingMessages = _incomingMessages.asStateFlow()

    fun updateToken(token: String) {
      _deviceToken.value = token
    }
  }

  override fun onNewToken(token: String) {
    super.onNewToken(token)
    Log.d(TAG, "New FCM Token received: $token")
    _deviceToken.value = token

    // Optionally mirror token to Firestore
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val firestoreService = FirestoreService()
        val authService = FirebaseAuthService()
        val currentUser = authService.currentUser
        val uid = currentUser?.uid ?: "anonymous_device"
        firestoreService.saveAuditLog(
          FirestoreAuditLog(
            id = "fcm_token_" + System.currentTimeMillis(),
            eventType = "FCM_TOKEN_REFRESH",
            description = "Device registered new FCM push token: ${token.take(16)}...",
            actor = currentUser?.email ?: "device",
            cryptographicHash = "FCM:" + token.hashCode(),
            userId = uid
          )
        )
      } catch (e: Exception) {
        Log.e(TAG, "Failed to save token update to audit logs", e)
      }
    }
  }

  override fun onMessageReceived(remoteMessage: RemoteMessage) {
    super.onMessageReceived(remoteMessage)
    Log.d(TAG, "From: ${remoteMessage.from}")

    val title = remoteMessage.notification?.title 
      ?: remoteMessage.data["title"] 
      ?: "Agent Wallet Security Alert"
    
    val body = remoteMessage.notification?.body 
      ?: remoteMessage.data["body"] 
      ?: "New cryptographic authorization / ATW event received."

    val newMsg = FcmNotificationMessage(
      id = remoteMessage.messageId ?: System.currentTimeMillis().toString(),
      title = title,
      body = body,
      data = remoteMessage.data,
      timestamp = remoteMessage.sentTime.takeIf { it > 0 } ?: System.currentTimeMillis()
    )

    _incomingMessages.value = listOf(newMsg) + _incomingMessages.value.take(20)

    showNotification(title, body)
  }

  private fun showNotification(title: String, body: String) {
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        CHANNEL_NAME,
        NotificationManager.IMPORTANCE_HIGH
      ).apply {
        description = "Push notifications for Agent Wallet operations and ATW security events"
        enableLights(true)
        enableVibration(true)
      }
      notificationManager.createNotificationChannel(channel)
    }

    val intent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
      this,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(body)
      .setAutoCancel(true)
      .setContentIntent(pendingIntent)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .build()

    notificationManager.notify(System.currentTimeMillis().toInt(), notification)
  }
}
