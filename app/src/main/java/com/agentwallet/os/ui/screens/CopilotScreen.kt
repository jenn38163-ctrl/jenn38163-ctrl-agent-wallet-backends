package com.agentwallet.os.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentwallet.os.ui.ChatMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopilotScreen(
  messages: List<ChatMessage>,
  isLoading: Boolean,
  onSendMessage: (String) -> Unit
) {
  var inputQuery by remember { mutableStateOf("") }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Samsung ATW Security & X.509 Copilot", fontWeight = FontWeight.Bold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
      )
    }
  ) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(paddingValues)
    ) {
      LazyColumn(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
      ) {
        items(messages) { msg ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
          ) {
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = if (msg.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
              modifier = Modifier.widthIn(max = 300.dp)
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Text(
                  text = msg.text,
                  color = if (msg.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                  style = MaterialTheme.typography.bodyMedium
                )
              }
            }
          }
        }
        if (isLoading) {
          item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
              Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
              ) {
                Row(
                  modifier = Modifier.padding(16.dp),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                  Text("Gemini is analyzing wallet policies...", style = MaterialTheme.typography.bodySmall)
                }
              }
            }
          }
        }
      }

      // Input Bar
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          OutlinedTextField(
            value = inputQuery,
            onValueChange = { inputQuery = it },
            placeholder = { Text("Ask about X.509 keys, CSR attributes, or ATW compliance...") },
            modifier = Modifier
              .weight(1f)
              .testTag("copilot_input"),
            maxLines = 3
          )
          IconButton(
            onClick = {
              if (inputQuery.isNotBlank()) {
                onSendMessage(inputQuery)
                inputQuery = ""
              }
            },
            modifier = Modifier
              .clip(RoundedCornerShape(12.dp))
              .background(MaterialTheme.colorScheme.primary)
              .testTag("copilot_send_btn")
          ) {
            Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
          }
        }
      }
    }
  }
}
