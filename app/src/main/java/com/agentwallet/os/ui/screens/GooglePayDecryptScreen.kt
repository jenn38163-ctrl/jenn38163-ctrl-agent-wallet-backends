package com.agentwallet.os.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GooglePayDecryptScreen() {
  var recipientId by remember { mutableStateOf("") }
  var encryptedJson by remember { mutableStateOf("") }
  var inspectionResult by remember { mutableStateOf<String?>(null) }
  var inspectionError by remember { mutableStateOf<String?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Google Pay ECv2 Envelope Inspector", fontWeight = FontWeight.Bold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
      )
    }
  ) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(paddingValues)
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f))
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.Top
        ) {
          Icon(
            Icons.Default.Block,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer
          )
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              "Google Pay Direct Card Funding Disabled (HTTP 501)",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
              "Backend endpoint /v1/wallets/:id/fund/google-pay returns HTTP 501 NOT_IMPLEMENTED. Client-side private-key PAN decryption is disabled by policy so raw card PANs are never processed on-device.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer
            )
          }
        }
      }

      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text("ECv2 Payload Envelope Validator", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Text(
            "Paste a Google Pay PaymentData token JSON to validate its envelope structure (protocolVersion, signature, intermediateSigningKey, signedMessage) without decrypting or storing cardholder PANs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          OutlinedTextField(
            value = recipientId,
            onValueChange = { recipientId = it },
            label = { Text("Merchant Recipient ID (optional)") },
            placeholder = { Text("merchant:your-merchant-id") },
            modifier = Modifier.fillMaxWidth().testTag("recipient_id_input")
          )

          OutlinedTextField(
            value = encryptedJson,
            onValueChange = { encryptedJson = it },
            label = { Text("Google Pay Encrypted Token (JSON)") },
            placeholder = { Text("{\"protocolVersion\":\"ECv2\",\"signature\":\"...\",\"signedMessage\":\"...\"}") },
            modifier = Modifier.fillMaxWidth().height(160.dp).testTag("gpay_json_input")
          )

          Button(
            onClick = {
              inspectionError = null
              inspectionResult = null
              try {
                val root = JSONObject(encryptedJson)
                val proto = root.optString("protocolVersion")
                val hasSignature = root.optString("signature").isNotBlank()
                val intermediate = root.optJSONObject("intermediateSigningKey")
                val hasIntermediate = intermediate != null &&
                  intermediate.optString("signedKey").isNotBlank() &&
                  (intermediate.optJSONArray("signatures")?.length() ?: 0) > 0
                val signedMessageRaw = root.optString("signedMessage")
                val signedMsgJson = if (signedMessageRaw.isNotBlank()) JSONObject(signedMessageRaw) else null
                val hasEphemeralKey = signedMsgJson?.optString("ephemeralPublicKey")?.isNotBlank() == true
                val hasTag = signedMsgJson?.optString("tag")?.isNotBlank() == true
                val hasCiphertext = signedMsgJson?.optString("encryptedMessage")?.isNotBlank() == true

                inspectionResult = buildString {
                  if (recipientId.isNotBlank()) appendLine("Recipient ID: ${recipientId.trim()}")
                  appendLine("Protocol Version: ${proto.ifBlank { "MISSING" }}")
                  appendLine("Top-Level Signature Present: $hasSignature")
                  appendLine("Intermediate Signing Key Block: $hasIntermediate")
                  appendLine("SignedMessage -> ephemeralPublicKey Present: $hasEphemeralKey")
                  appendLine("SignedMessage -> tag Present: $hasTag")
                  appendLine("SignedMessage -> encryptedMessage Length: ${signedMsgJson?.optString("encryptedMessage")?.length ?: 0} chars ($hasCiphertext)")
                  appendLine("Decryption Status: Delegated to certified backend gateway only (HTTP 501).")
                }.trim()
              } catch (e: Exception) {
                inspectionError = "Invalid Google Pay ECv2 JSON envelope: ${e.message ?: "Parse error"}"
              }
            },
            enabled = encryptedJson.isNotBlank(),
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("decrypt_token_btn")
          ) {
            Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Inspect ECv2 Envelope Structure")
          }
        }
      }

      inspectionError?.let { err ->
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
          Text(
            text = err,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
          )
        }
      }

      inspectionResult?.let { result ->
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.FactCheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Text("Envelope Inspection Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Surface(
              modifier = Modifier.fillMaxWidth(),
              color = MaterialTheme.colorScheme.surface,
              shape = RoundedCornerShape(8.dp)
            ) {
              Box(modifier = Modifier.padding(12.dp)) {
                Text(result, style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        }
      }
    }
  }
}
