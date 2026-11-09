package com.agentwallet.os.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentwallet.os.firebase.FirebaseAuthService
import com.agentwallet.os.firebase.FcmNotificationMessage
import com.agentwallet.os.firebase.FirestoreVaultAsset
import com.google.firebase.auth.FirebaseUser

@Composable
fun FirebaseAuthScreen(
  currentUser: FirebaseUser?,
  isAuthLoading: Boolean,
  authErrorMessage: String?,
  authSuccessMessage: String?,
  firestoreAssets: List<FirestoreVaultAsset>,
  fcmToken: String? = null,
  fcmMessages: List<FcmNotificationMessage> = emptyList(),
  onSignIn: (String, String) -> Unit,
  onSignUp: (String, String, String, String) -> Unit,
  onSignOut: () -> Unit,
  onForgotPassword: (String) -> Unit,
  onSyncLocalToCloud: () -> Unit,
  onClearAuthMessages: () -> Unit,
  onRefreshFcmToken: () -> Unit = {},
  onSubscribeTopic: (String) -> Unit = {}
) {
  val clipboardManager = LocalClipboardManager.current
  var isRegisterMode by remember { mutableStateOf(false) }
  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var displayName by remember { mutableStateOf("") }
  var organization by remember { mutableStateOf("AI Corp Enterprises") }
  var passwordVisible by remember { mutableStateOf(false) }
  var showResetDialog by remember { mutableStateOf(false) }
  var resetEmail by remember { mutableStateOf("") }
  var customTopic by remember { mutableStateOf("atw-alerts") }
  var tokenCopied by remember { mutableStateOf(false) }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    contentPadding = PaddingValues(vertical = 24.dp)
  ) {
    // Header Hero Banner
    item {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(24.dp))
          .background(
            Brush.linearGradient(
              listOf(
                Color(0xFFFFA000), // Firebase Amber
                Color(0xFFF57C00), // Firebase Orange
                Color(0xFFFFCA28)
              )
            )
          )
          .padding(24.dp)
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Icon(
                Icons.Default.CloudSync,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
              )
              Text(
                text = "FIREBASE BACKEND",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
              )
            }
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = Color.Black.copy(alpha = 0.25f)
            ) {
              Text(
                text = if (currentUser != null) "Authenticated" else "Guest Mode",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
              )
            }
          }

          Text(
            text = "Auth & Cloud Firestore",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
          )

          Text(
            text = "Secure enterprise authentication and live Firestore database persistence for Agent Wallet.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.95f)
          )

          Spacer(modifier = Modifier.height(4.dp))

          // Firebase Project Config Details
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.2f),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Project ID:", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                Text(FirebaseAuthService.FIREBASE_PROJECT_ID, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("App ID:", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                Text("1:4985888379:android:...4cde", style = MaterialTheme.typography.labelSmall, color = Color.White, fontFamily = FontFamily.Monospace)
              }
            }
          }
        }
      }
    }

    // Feedback Messages
    if (!authErrorMessage.isNullOrBlank()) {
      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
              authErrorMessage,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClearAuthMessages) {
              Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
            }
          }
        }
      }
    }

    if (!authSuccessMessage.isNullOrBlank()) {
      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
              authSuccessMessage,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClearAuthMessages) {
              Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
            }
          }
        }
      }
    }

    // Main Content: User Profile or Auth Forms
    if (currentUser != null) {
      // Authenticated View
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
          Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                  modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                  contentAlignment = Alignment.Center
                ) {
                  Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                  )
                }
                Column {
                  Text(
                    text = currentUser.displayName?.takeIf { it.isNotBlank() } ?: "Agent Wallet Admin",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                  )
                  Text(
                    text = currentUser.email ?: "Unknown Email",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }

              IconButton(
                onClick = onSignOut,
                modifier = Modifier.testTag("firebase_sign_out_btn")
              ) {
                Icon(Icons.Default.Logout, contentDescription = "Sign Out", tint = MaterialTheme.colorScheme.error)
              }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Firebase UID:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                  currentUser.uid.take(16) + "...",
                  style = MaterialTheme.typography.bodySmall,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Medium
                )
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Auth Provider:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Email / Password", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
              }
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Firestore Status:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Connected & Synced", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
              }
            }

            Button(
              onClick = onSyncLocalToCloud,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("sync_firestore_btn")
            ) {
              Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text("Sync All Local Assets to Firestore")
            }
          }
        }
      }

      // Firestore Database Live Assets
      item {
        Text(
          text = "Firestore Cloud Collections (wallet-54645)",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(top = 8.dp)
        )
      }

      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("vault_assets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
              }
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer
              ) {
                Text(
                  "${firestoreAssets.size} docs",
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                  fontWeight = FontWeight.Bold
                )
              }
            }
            Text(
              "Real-time synchronized ATW Keys, CSRs, and X.509 Certificates stored securely in Cloud Firestore.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    } else {
      // Unauthenticated Auth Card: Sign In / Sign Up Tabs
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            TabRow(
              selectedTabIndex = if (isRegisterMode) 1 else 0,
              containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
              modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
              Tab(
                selected = !isRegisterMode,
                onClick = { isRegisterMode = false },
                text = { Text("Sign In", fontWeight = FontWeight.Bold) }
              )
              Tab(
                selected = isRegisterMode,
                onClick = { isRegisterMode = true },
                text = { Text("Create Account", fontWeight = FontWeight.Bold) }
              )
            }

            if (isRegisterMode) {
              OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Full Name / Display Name") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("auth_display_name_input")
              )

              OutlinedTextField(
                value = organization,
                onValueChange = { organization = it },
                label = { Text("Organization") },
                leadingIcon = { Icon(Icons.Default.Business, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("auth_org_input")
              )
            }

            OutlinedTextField(
              value = email,
              onValueChange = { email = it },
              label = { Text("Email Address") },
              leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("auth_email_input")
            )

            OutlinedTextField(
              value = password,
              onValueChange = { password = it },
              label = { Text("Password") },
              leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
              trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                  Icon(
                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = "Toggle password visibility"
                  )
                }
              },
              visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("auth_password_input")
            )

            if (!isRegisterMode) {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                  resetEmail = email
                  showResetDialog = true
                }) {
                  Text("Forgot Password?", style = MaterialTheme.typography.bodySmall)
                }
              }
            }

            Button(
              onClick = {
                if (isRegisterMode) {
                  onSignUp(email, password, displayName, organization)
                } else {
                  onSignIn(email, password)
                }
              },
              enabled = !isAuthLoading && email.isNotBlank() && password.isNotBlank(),
              modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("auth_submit_btn")
            ) {
              if (isAuthLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
              } else {
                Text(if (isRegisterMode) "Register with Firebase" else "Sign In")
              }
            }

            // Quick Demo Credentials Helper
            OutlinedCard(
              modifier = Modifier.fillMaxWidth(),
              colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
              Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                  "Firebase Project Configuration:",
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  "Connected directly to existing Firebase project: wallet-54645",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }
        }
      }
    }
    // Firebase Cloud Messaging (FCM) Section
    item {
      Card(
        modifier = Modifier.fillMaxWidth().testTag("fcm_management_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier = Modifier
                  .size(38.dp)
                  .clip(RoundedCornerShape(10.dp))
                  .background(Color(0xFFFF9800).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
              ) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = Color(0xFFF57C00), modifier = Modifier.size(22.dp))
              }
              Column {
                Text("Firebase Cloud Messaging (FCM)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Push notifications & remote agent triggers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }

          HorizontalDivider()

          // Device Registration Token
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text("FCM Device Registration Token", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
              TextButton(onClick = onRefreshFcmToken) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Refresh")
              }
            }

            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                  text = fcmToken ?: "Fetching FCM registration token from Google Play Services...",
                  style = MaterialTheme.typography.bodySmall,
                  fontFamily = FontFamily.Monospace,
                  color = if (fcmToken != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (fcmToken != null) {
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                      onClick = {
                        clipboardManager.setText(AnnotatedString(fcmToken))
                        tokenCopied = true
                      },
                      colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                      modifier = Modifier.testTag("copy_fcm_token_btn")
                    ) {
                      Icon(if (tokenCopied) Icons.Default.Check else Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                      Spacer(modifier = Modifier.width(6.dp))
                      Text(if (tokenCopied) "Token Copied!" else "Copy Token")
                    }
                  }
                }
              }
            }
          }

          // Topic Subscriptions
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Push Notification Topics", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              OutlinedTextField(
                value = customTopic,
                onValueChange = { customTopic = it },
                label = { Text("Topic name") },
                singleLine = true,
                modifier = Modifier.weight(1f)
              )
              Button(
                onClick = {
                  if (customTopic.isNotBlank()) {
                    onSubscribeTopic(customTopic.trim())
                  }
                },
                modifier = Modifier.testTag("fcm_subscribe_topic_btn")
              ) {
                Text("Subscribe")
              }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              SuggestionChip(
                onClick = {
                  customTopic = "atw-alerts"
                  onSubscribeTopic("atw-alerts")
                },
                label = { Text("atw-alerts") }
              )
              SuggestionChip(
                onClick = {
                  customTopic = "security-audits"
                  onSubscribeTopic("security-audits")
                },
                label = { Text("security-audits") }
              )
              SuggestionChip(
                onClick = {
                  customTopic = "wallet-events"
                  onSubscribeTopic("wallet-events")
                },
                label = { Text("wallet-events") }
              )
            }
          }

          // Secret Status badge
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier.padding(10.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
              Text(
                text = "FIREBASE_FCM secret connected in AI Studio Secrets",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
              )
            }
          }

          // Recent FCM Notifications
          if (fcmMessages.isNotEmpty()) {
            HorizontalDivider()
            Text("Recent In-App Push Messages (${fcmMessages.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              fcmMessages.take(5).forEach { msg ->
                Card(
                  colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(msg.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(msg.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  // Password Reset Dialog
  if (showResetDialog) {
    AlertDialog(
      onDismissRequest = { showResetDialog = false },
      title = { Text("Reset Password") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("Enter your email address and we'll send you a password reset link via Firebase Authentication:")
          OutlinedTextField(
            value = resetEmail,
            onValueChange = { resetEmail = it },
            label = { Text("Email Address") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (resetEmail.isNotBlank()) {
              onForgotPassword(resetEmail)
              showResetDialog = false
            }
          }
        ) {
          Text("Send Reset Link")
        }
      },
      dismissButton = {
        TextButton(onClick = { showResetDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }
}
