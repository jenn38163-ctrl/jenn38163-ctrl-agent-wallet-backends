package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.AgentWalletEntity

data class SystemClient(
  val name: String,
  val description: String,
  val defaultVendor: String,
  val defaultAmount: Double,
  val defaultProtocol: String,
  val defaultChain: String,
  val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(
  wallets: List<AgentWalletEntity>,
  onExecuteClientPayment: (walletId: String, vendor: String, amount: Double, protocol: String, chain: String) -> Unit
) {
  val clients = remember {
    listOf(
      SystemClient(
        name = "Rewards Gateway",
        description = "Distributes perks and gift cards via Giftbit for verified agent loyalty.",
        defaultVendor = "Giftbit API",
        defaultAmount = 15.00,
        defaultProtocol = "x402",
        defaultChain = "Base",
        icon = Icons.Default.CardGiftcard
      ),
      SystemClient(
        name = "REMIC OS",
        description = "Real Estate Mortgage Investment Conduit agent paying high-frequency data feeds.",
        defaultVendor = "Polygon.io Real Estate Data",
        defaultAmount = 25.00,
        defaultProtocol = "x402",
        defaultChain = "Base",
        icon = Icons.Default.Home
      ),
      SystemClient(
        name = "Sovereign Trust OS",
        description = "Autonomous compliance and regulatory auditing agent paying verification APIs.",
        defaultVendor = "Chainalysis Compliance API",
        defaultAmount = 5.00,
        defaultProtocol = "HTTP 402",
        defaultChain = "Solana",
        icon = Icons.Default.VerifiedUser
      ),
      SystemClient(
        name = "Research Agent",
        description = "Autonomous web search and literature review agent paying semantic search engines.",
        defaultVendor = "You.com Paid Search",
        defaultAmount = 0.01,
        defaultProtocol = "MPP",
        defaultChain = "Tempo",
        icon = Icons.Default.Search
      )
    )
  }

  var selectedClient by remember { mutableStateOf(clients[0]) }
  var selectedWalletId by remember { mutableStateOf(wallets.firstOrNull()?.id ?: "") }
  var executionResult by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(wallets) {
    if (selectedWalletId.isEmpty() && wallets.isNotEmpty()) {
      selectedWalletId = wallets.first().id
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Client Systems Integration", fontWeight = FontWeight.Bold) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
      )
    }
  ) { paddingValues ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(paddingValues)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Text(
              text = "Decoupled Financial Control Plane & API Gateway",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "External client systems (Rewards Gateway, REMIC OS, Trust OS, Research) never manage private keys. They invoke POST /v1/agents/{agentId}/payments/authorize, allowing the Policy Engine and Ledger to govern transactions.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }

      item {
        Text("Select Client System", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
      }

      items(clients) { client ->
        val isSelected = selectedClient.name == client.name
        Card(
          onClick = { selectedClient = client },
          colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface
          ),
          border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(client.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.weight(1f)) {
              Text(client.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
              Text(client.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isSelected) {
              Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
          }
        }
      }

      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Text("API Request Simulator: ${selectedClient.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Endpoint: POST /v1/agents/{agentId}/payments/authorize", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)

            Text("Select Wallet Identity", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              wallets.forEach { w ->
                val isWSelected = w.id == selectedWalletId
                Surface(
                  onClick = { selectedWalletId = w.id },
                  shape = RoundedCornerShape(8.dp),
                  color = if (isWSelected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(w.name, fontWeight = FontWeight.Medium)
                    Text("$${w.usdcBalance} USDC", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                  }
                }
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text("Target Vendor: ${selectedClient.defaultVendor}", style = MaterialTheme.typography.bodySmall)
              Text("Amount: $${selectedClient.defaultAmount} USDC", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text("Protocol: ${selectedClient.defaultProtocol}", style = MaterialTheme.typography.bodySmall)
              Text("Chain: ${selectedClient.defaultChain}", style = MaterialTheme.typography.bodySmall)
            }

            Button(
              onClick = {
                onExecuteClientPayment(
                  selectedWalletId,
                  selectedClient.defaultVendor,
                  selectedClient.defaultAmount,
                  selectedClient.defaultProtocol,
                  selectedClient.defaultChain
                )
                executionResult = "Successfully invoked API for ${selectedClient.name} -> Authorized $${selectedClient.defaultAmount} to ${selectedClient.defaultVendor}!"
              },
              modifier = Modifier.fillMaxWidth().testTag("execute_client_api_btn")
            ) {
              Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text("Send API Payment Authorization Request")
            }
          }
        }
      }

      executionResult?.let { res ->
        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
          ) {
            Row(
              modifier = Modifier.padding(16.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
              Text(res, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
          }
        }
      }
    }
  }
}
