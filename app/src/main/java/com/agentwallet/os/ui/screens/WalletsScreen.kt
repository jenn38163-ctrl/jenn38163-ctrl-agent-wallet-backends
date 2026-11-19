package com.agentwallet.os.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import com.agentwallet.os.data.AgentWalletEntity
import com.agentwallet.os.network.TenantProvisioningInfo
import com.agentwallet.os.utils.CurrencyUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletsScreen(
  wallets: List<AgentWalletEntity>,
  provisioningInfo: TenantProvisioningInfo? = null,
  isLoading: Boolean = false,
  errorMessage: String? = null,
  statusMessage: String? = null,
  onRefresh: () -> Unit = {},
  onDismissMessages: () -> Unit = {},
  onRequestTenantAccess: (String) -> Unit = {},
  onCreateWallet: (String, String, Long, Long, Long, String, String, Boolean, String) -> Unit,
  onDeposit: (String, Long) -> Unit
) {
  var showCreateDialog by remember { mutableStateOf(false) }
  var depositWallet by remember { mutableStateOf<AgentWalletEntity?>(null) }

  val isProvisioningBlocked = provisioningInfo != null && provisioningInfo.provisioningStatus != "ACTIVE"

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Sovereign Agent Wallets", fontWeight = FontWeight.Bold) },
        actions = {
          IconButton(
            onClick = onRefresh,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("wallets_refresh_button")
          ) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh Wallets")
          }
          IconButton(
            onClick = { showCreateDialog = true },
            enabled = !isProvisioningBlocked,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("add_wallet_button")
          ) {
            Icon(Icons.Default.Add, contentDescription = "Add Wallet")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
      )
    },
    floatingActionButton = {
      if (!isProvisioningBlocked) {
        FloatingActionButton(
          onClick = { showCreateDialog = true },
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.testTag("fab_add_wallet")
        ) {
          Icon(Icons.Default.Add, contentDescription = "Create Agent Wallet")
        }
      }
    }
  ) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(paddingValues)
    ) {
      AnimatedVisibility(visible = isLoading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }

      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        if (provisioningInfo != null && provisioningInfo.provisioningStatus != "ACTIVE") {
          item(key = "tenant_provisioning_gate") {
            var orgRequestInput by remember(provisioningInfo.requestedOrganization) {
              mutableStateOf(provisioningInfo.requestedOrganization ?: "")
            }
            Card(
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
              modifier = Modifier.fillMaxWidth().testTag("tenant_approval_card")
            ) {
              Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Icon(
                    Icons.Default.AdminPanelSettings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                  )
                  Text(
                    text = if (provisioningInfo.provisioningStatus == "PENDING_APPROVAL") {
                      "Tenant Approval Pending"
                    } else {
                      "Tenant Access ${provisioningInfo.provisioningStatus}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                  )
                }
                Text(
                  text = "New Firebase accounts start with zero tenant privileges (no automatic admin or write scopes). Submit an organization access request so a tenant administrator can approve your role and scopes.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                if (!provisioningInfo.requestedOrganization.isNullOrBlank()) {
                  Text(
                    text = "Requested organization: ${provisioningInfo.requestedOrganization}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                  )
                }
                OutlinedTextField(
                  value = orgRequestInput,
                  onValueChange = { orgRequestInput = it },
                  label = { Text("Organization Name") },
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth().testTag("tenant_org_request_input")
                )
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                  OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    modifier = Modifier.minimumInteractiveComponentSize()
                  ) {
                    Text("Check Status")
                  }
                  Button(
                    onClick = { onRequestTenantAccess(orgRequestInput) },
                    enabled = !isLoading && orgRequestInput.isNotBlank(),
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("request_tenant_access_btn")
                  ) {
                    Text("Request Access")
                  }
                }
              }
            }
          }
        }

        if (!errorMessage.isNullOrBlank()) {
          item(key = "wallets_error") {
            Card(
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
              modifier = Modifier.fillMaxWidth().testTag("wallets_error_banner")
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = errorMessage,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  style = MaterialTheme.typography.bodySmall,
                  modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismissMessages) {
                  Icon(Icons.Default.Close, contentDescription = "Dismiss error")
                }
              }
            }
          }
        }

        if (!statusMessage.isNullOrBlank()) {
          item(key = "wallets_status") {
            Card(
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
              modifier = Modifier.fillMaxWidth().testTag("wallets_status_banner")
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = statusMessage,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                  style = MaterialTheme.typography.bodySmall,
                  modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismissMessages) {
                  Icon(Icons.Default.Close, contentDescription = "Dismiss message")
                }
              }
            }
          }
        }

        item {
          Text(
            text = "PostgreSQL-backed wallets enforce atomic balance reserves, strict single-currency accounting, monthly spending policies, and per-request limits.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        if (wallets.isEmpty() && !isLoading && !isProvisioningBlocked) {
          item {
            Card(
              modifier = Modifier.fillMaxWidth().testTag("wallets_empty_state"),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
              Column(
                modifier = Modifier.fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text("No Wallets Found", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Create an Agent Wallet in your PostgreSQL tenant ledger to begin authorizing payments.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { showCreateDialog = true }, modifier = Modifier.minimumInteractiveComponentSize()) {
                  Text("Create Wallet")
                }
              }
            }
          }
        }

        items(wallets, key = { it.id }) { wallet ->
          Card(
            modifier = Modifier.fillMaxWidth().testTag("wallet_card_${wallet.id}"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.weight(1f)
                ) {
                  Box(
                    modifier = Modifier
                      .size(40.dp)
                      .clip(RoundedCornerShape(10.dp))
                      .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                  ) {
                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                  }
                  Column {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                      Text(wallet.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                      Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                      ) {
                        Text(
                          text = wallet.currency,
                          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                          style = MaterialTheme.typography.labelSmall,
                          fontWeight = FontWeight.Bold,
                          color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                      }
                    }
                    Text(wallet.organization, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }
                Column(horizontalAlignment = Alignment.End) {
                  Text(
                    text = CurrencyUtils.formatAmount(wallet.usdcBalance, wallet.currency),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                  )
                  Text("Available Balance", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }

              HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Column {
                  Text("Monthly Budget", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  Text(
                    "${CurrencyUtils.formatAmount(wallet.monthlySpent, wallet.currency)} / ${CurrencyUtils.formatAmount(wallet.monthlyLimit, wallet.currency)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                  )
                }
                Column {
                  Text("Max / Request", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  Text(
                    CurrencyUtils.formatAmount(wallet.perRequestLimit, wallet.currency),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                  )
                }
                Column {
                  Text("Settlement Rail", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  Text(wallet.allowedChains.ifBlank { "PAYU" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
              }

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
              ) {
                OutlinedButton(
                  onClick = { depositWallet = wallet },
                  enabled = !isLoading,
                  modifier = Modifier.minimumInteractiveComponentSize().testTag("deposit_btn_${wallet.id}")
                ) {
                  Icon(Icons.Default.AddCard, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Fund Wallet (${wallet.currency})")
                }
              }
            }
          }
        }
      }
    }
  }

  // Create Wallet Dialog
  if (showCreateDialog) {
    var name by remember { mutableStateOf("") }
    val org = provisioningInfo?.organizationId?.takeIf { it != "unassigned" } ?: "Tenant Managed"
    var currency by remember { mutableStateOf("USD") }
    var balance by remember { mutableStateOf("100.00") }
    var monthlyLimit by remember { mutableStateOf("500.00") }
    var perReq by remember { mutableStateOf("50.00") }
    var vendors by remember { mutableStateOf("You.com,OpenAI,PayU") }
    var chains by remember { mutableStateOf("PAYU") }
    val requireApproval = false

    AlertDialog(
      onDismissRequest = { showCreateDialog = false },
      title = { Text("Create Agent Wallet") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Agent Name") },
            modifier = Modifier.fillMaxWidth().testTag("create_wallet_name_input")
          )
          Text("Ledger Currency", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("USD", "PLN", "USDC").forEach { option ->
              FilterChip(
                selected = currency == option,
                onClick = { currency = option },
                label = { Text(option) },
                modifier = Modifier.testTag("currency_chip_$option")
              )
            }
          }
          OutlinedTextField(value = balance, onValueChange = { balance = it }, label = { Text("Initial Funding ($currency)") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = monthlyLimit, onValueChange = { monthlyLimit = it }, label = { Text("Monthly Limit ($currency)") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = perReq, onValueChange = { perReq = it }, label = { Text("Per-Request Limit ($currency)") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = vendors, onValueChange = { vendors = it }, label = { Text("Allowed Vendors") }, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = chains, onValueChange = { chains = it }, label = { Text("Settlement Rail (PAYU)") }, modifier = Modifier.fillMaxWidth())
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (name.isNotBlank()) {
              onCreateWallet(
                name.trim(),
                org,
                CurrencyUtils.parseCurrencyInput(balance),
                CurrencyUtils.parseCurrencyInput(monthlyLimit),
                CurrencyUtils.parseCurrencyInput(perReq),
                vendors,
                chains,
                requireApproval,
                currency
              )
              showCreateDialog = false
            }
          },
          modifier = Modifier.testTag("confirm_create_wallet")
        ) {
          Text("Create Wallet")
        }
      },
      dismissButton = {
        TextButton(onClick = { showCreateDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }

  // Deposit Dialog
  depositWallet?.let { targetWallet ->
    var amountStr by remember { mutableStateOf("50.00") }
    AlertDialog(
      onDismissRequest = { depositWallet = null },
      title = { Text("Fund ${targetWallet.name} (${targetWallet.currency})") },
      text = {
        OutlinedTextField(
          value = amountStr,
          onValueChange = { amountStr = it },
          label = { Text("Amount (${targetWallet.currency})") },
          modifier = Modifier.fillMaxWidth().testTag("fund_wallet_amount_input")
        )
      },
      confirmButton = {
        Button(
          onClick = {
            val amt = CurrencyUtils.parseCurrencyInput(amountStr)
            if (amt > 0) {
              onDeposit(targetWallet.id, amt)
              depositWallet = null
            }
          },
          modifier = Modifier.testTag("confirm_fund_wallet")
        ) {
          Text("Confirm Funding")
        }
      },
      dismissButton = {
        TextButton(onClick = { depositWallet = null }) {
          Text("Cancel")
        }
      }
    )
  }
}
