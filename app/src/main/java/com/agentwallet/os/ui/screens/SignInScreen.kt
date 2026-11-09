package com.agentwallet.os.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentwallet.os.firebase.FirebaseAuthService

@Composable
fun SignInScreen(
  isLoading: Boolean,
  errorMessage: String?,
  successMessage: String?,
  onSignIn: (email: String, password: String) -> Unit,
  onSignUp: (email: String, password: String, displayName: String, organization: String) -> Unit,
  onForgotPassword: (email: String) -> Unit,
  onContinueAsGuest: () -> Unit,
  onClearMessages: () -> Unit
) {
  val focusManager = LocalFocusManager.current
  var isSignUpMode by remember { mutableStateOf(false) }

  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var confirmPassword by remember { mutableStateOf("") }
  var displayName by remember { mutableStateOf("") }
  var organization by remember { mutableStateOf("Autonomous Agent Corp") }

  var passwordVisible by remember { mutableStateOf(false) }
  var showForgotDialog by remember { mutableStateOf(false) }
  var resetEmail by remember { mutableStateOf("") }
  var localValidationErr by remember { mutableStateOf<String?>(null) }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
  ) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
      contentPadding = PaddingValues(top = 40.dp, bottom = 40.dp)
    ) {
      // Header Brand Hero
      item {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Box(
            modifier = Modifier
              .size(76.dp)
              .clip(CircleShape)
              .background(
                Brush.linearGradient(
                  listOf(
                    Color(0xFFFFA000), // Firebase Amber
                    Color(0xFFF57C00), // Firebase Orange
                    MaterialTheme.colorScheme.primary
                  )
                )
              ),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Outlined.Lock,
              contentDescription = "Security Lock",
              tint = Color.White,
              modifier = Modifier.size(38.dp)
            )
          }

          Text(
            text = "Agent Wallet OS",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
          )

          Text(
            text = "Authenticate with Firebase to securely access your sovereign wallets, cryptographic keys, and ATW credentials.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
          )

          // Firebase Project Badge
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            modifier = Modifier.padding(top = 4.dp)
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Icon(
                Icons.Default.CloudSync,
                contentDescription = null,
                tint = Color(0xFFF57C00),
                modifier = Modifier.size(16.dp)
              )
              Text(
                text = "Firebase Backend: ${FirebaseAuthService.FIREBASE_PROJECT_ID}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
              )
            }
          }
        }
      }

      // Mode Switcher (Sign In vs Create Account)
      item {
        Card(
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
          ),
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
        ) {
          TabRow(
            selectedTabIndex = if (isSignUpMode) 1 else 0,
            containerColor = Color.Transparent,
            modifier = Modifier
              .padding(4.dp)
              .clip(RoundedCornerShape(12.dp))
          ) {
            Tab(
              selected = !isSignUpMode,
              onClick = {
                isSignUpMode = false
                localValidationErr = null
                onClearMessages()
              },
              modifier = Modifier.testTag("tab_signin_mode"),
              text = {
                Text(
                  "Sign In",
                  fontWeight = if (!isSignUpMode) FontWeight.Bold else FontWeight.Normal
                )
              }
            )
            Tab(
              selected = isSignUpMode,
              onClick = {
                isSignUpMode = true
                localValidationErr = null
                onClearMessages()
              },
              modifier = Modifier.testTag("tab_signup_mode"),
              text = {
                Text(
                  "Create Account",
                  fontWeight = if (isSignUpMode) FontWeight.Bold else FontWeight.Normal
                )
              }
            )
          }
        }
      }

      // Feedback Banners (Error or Success)
      item {
        val displayErr = localValidationErr ?: errorMessage
        AnimatedVisibility(
          visible = displayErr != null,
          enter = fadeIn(),
          exit = fadeOut()
        ) {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().testTag("auth_error_card")
          ) {
            Row(
              modifier = Modifier.padding(14.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
              Text(
                text = displayErr ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
              )
              IconButton(onClick = {
                localValidationErr = null
                onClearMessages()
              }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
              }
            }
          }
        }

        AnimatedVisibility(
          visible = successMessage != null,
          enter = fadeIn(),
          exit = fadeOut()
        ) {
          Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("auth_success_card")
          ) {
            Row(
              modifier = Modifier.padding(14.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
              Text(
                text = successMessage ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF1B5E20),
                modifier = Modifier.weight(1f)
              )
            }
          }
        }
      }

      // Input Form Fields
      item {
        Card(
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
          ) {
            if (isSignUpMode) {
              OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Full Name") },
                placeholder = { Text("e.g. Alex Rivera") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("input_display_name")
              )

              OutlinedTextField(
                value = organization,
                onValueChange = { organization = it },
                label = { Text("Organization / Agency") },
                placeholder = { Text("e.g. SecOps Enterprise") },
                leadingIcon = { Icon(Icons.Default.Business, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("input_organization")
              )
            }

            OutlinedTextField(
              value = email,
              onValueChange = { email = it; localValidationErr = null },
              label = { Text("Email Address") },
              placeholder = { Text("name@domain.com") },
              leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
              keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
              ),
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("input_email")
            )

            OutlinedTextField(
              value = password,
              onValueChange = { password = it; localValidationErr = null },
              label = { Text("Password") },
              placeholder = { Text("••••••••") },
              leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
              trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                  Icon(
                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (passwordVisible) "Hide password" else "Show password"
                  )
                }
              },
              visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (isSignUpMode) ImeAction.Next else ImeAction.Done
              ),
              keyboardActions = KeyboardActions(
                onDone = {
                  focusManager.clearFocus()
                  if (email.isNotBlank() && password.isNotBlank()) {
                    onSignIn(email.trim(), password.trim())
                  }
                }
              ),
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("input_password")
            )

            if (isSignUpMode) {
              OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; localValidationErr = null },
                label = { Text("Confirm Password") },
                placeholder = { Text("••••••••") },
                leadingIcon = { Icon(Icons.Default.LockReset, contentDescription = null) },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                  keyboardType = KeyboardType.Password,
                  imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                  onDone = { focusManager.clearFocus() }
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("input_confirm_password")
              )
            }

            if (!isSignUpMode) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
              ) {
                TextButton(
                  onClick = {
                    resetEmail = email.trim()
                    showForgotDialog = true
                  },
                  modifier = Modifier.testTag("btn_forgot_password")
                ) {
                  Text(
                    "Forgot Password?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                  )
                }
              }
            }

            // Primary Action Button
            Button(
              onClick = {
                focusManager.clearFocus()
                val e = email.trim()
                val p = password.trim()

                if (e.isBlank() || !e.contains("@")) {
                  localValidationErr = "Please enter a valid email address."
                  return@Button
                }
                if (p.length < 6) {
                  localValidationErr = "Password must be at least 6 characters long."
                  return@Button
                }

                if (isSignUpMode) {
                  if (p != confirmPassword.trim()) {
                    localValidationErr = "Passwords do not match."
                    return@Button
                  }
                  onSignUp(e, p, displayName.trim().ifBlank { "Agent Administrator" }, organization.trim().ifBlank { "AI Corp" })
                } else {
                  onSignIn(e, p)
                }
              },
              enabled = !isLoading,
              modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("btn_auth_submit"),
              shape = RoundedCornerShape(12.dp)
            ) {
              if (isLoading) {
                CircularProgressIndicator(
                  modifier = Modifier.size(22.dp),
                  color = MaterialTheme.colorScheme.onPrimary,
                  strokeWidth = 2.5.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(if (isSignUpMode) "Creating account..." else "Authenticating...")
              } else {
                Icon(
                  imageVector = if (isSignUpMode) Icons.Default.PersonAdd else Icons.Default.Login,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = if (isSignUpMode) "Create Account & Sign In" else "Sign In to Agent Wallet",
                  fontWeight = FontWeight.Bold,
                  fontSize = 15.sp
                )
              }
            }
          }
        }
      }

      // Quick Demo Credentials & Guest Access
      item {
        Card(
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Text(
              text = "Quick Demo / Testing",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedButton(
                onClick = {
                  email = "admin@agentwallet.os"
                  password = "AgentSecOps2026!"
                  confirmPassword = "AgentSecOps2026!"
                  displayName = "SecOps Lead"
                  organization = "Samsung ATW Testing Lab"
                  localValidationErr = null
                },
                modifier = Modifier
                  .weight(1f)
                  .testTag("btn_fill_demo_creds")
              ) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Fill Demo", maxLines = 1)
              }

              OutlinedButton(
                onClick = onContinueAsGuest,
                modifier = Modifier
                  .weight(1f)
                  .testTag("btn_continue_as_guest")
              ) {
                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Guest Mode", maxLines = 1)
              }
            }
          }
        }
      }

      // Security Badges Footer
      item {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            Icons.Default.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Secured by Firebase Auth • AES-256 GCM • Android KeyStore",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
          )
        }
      }
    }
  }

  // Password Reset Dialog
  if (showForgotDialog) {
    AlertDialog(
      onDismissRequest = { showForgotDialog = false },
      title = { Text("Reset Password") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            "Enter your email address to receive a secure Firebase password reset link.",
            style = MaterialTheme.typography.bodyMedium
          )
          OutlinedTextField(
            value = resetEmail,
            onValueChange = { resetEmail = it },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (resetEmail.isNotBlank()) {
              onForgotPassword(resetEmail.trim())
              showForgotDialog = false
            }
          }
        ) {
          Text("Send Reset Link")
        }
      },
      dismissButton = {
        TextButton(onClick = { showForgotDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }
}
