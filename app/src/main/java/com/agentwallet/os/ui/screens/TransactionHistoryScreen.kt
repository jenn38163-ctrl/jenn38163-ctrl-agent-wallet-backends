package com.agentwallet.os.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentwallet.os.R
import com.agentwallet.os.firebase.FirestoreTransaction
import com.agentwallet.os.firebase.PaymentStatusCategory
import com.agentwallet.os.ui.TransactionHistoryUiState
import com.agentwallet.os.utils.CurrencyUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class TransactionStatusFilter {
  ALL,
  PENDING,
  COMPLETED,
  FAILED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionHistoryScreen(
  uiState: TransactionHistoryUiState,
  userEmail: String?,
  onRefresh: () -> Unit,
  onNavigateToAuth: () -> Unit
) {
  var selectedFilter by remember { mutableStateOf(TransactionStatusFilter.ALL) }
  var searchQuery by remember { mutableStateOf("") }
  var selectedTransaction by remember { mutableStateOf<FirestoreTransaction?>(null) }
  val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = stringResource(R.string.tx_history_title),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = userEmail ?: stringResource(R.string.tx_history_subtitle),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        },
        actions = {
          if (uiState !is TransactionHistoryUiState.Unauthenticated) {
            IconButton(
              onClick = onRefresh,
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("tx_history_refresh_btn")
            ) {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.tx_history_refresh)
              )
            }
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface
        )
      )
    }
  ) { paddingValues ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(paddingValues),
      contentAlignment = Alignment.TopCenter
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .widthIn(max = 720.dp)
      ) {
        when (uiState) {
          is TransactionHistoryUiState.Unauthenticated -> {
            UnauthenticatedTransactionsView(
              onNavigateToAuth = onNavigateToAuth,
              modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
            )
          }

          is TransactionHistoryUiState.Loading -> {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .testTag("tx_history_loading"),
              contentAlignment = Alignment.Center
            ) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
              ) {
                CircularProgressIndicator()
                Text(
                  text = "Querying Firestore transaction history...",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }

          is TransactionHistoryUiState.Error -> {
            TransactionErrorView(
              message = uiState.message,
              onRetry = onRefresh,
              modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
            )
          }

          is TransactionHistoryUiState.Success -> {
            val allTransactions = uiState.transactions
            val pendingCount = remember(allTransactions) {
              allTransactions.count { it.statusCategory() == PaymentStatusCategory.PENDING }
            }
            val completedCount = remember(allTransactions) {
              allTransactions.count { it.statusCategory() == PaymentStatusCategory.COMPLETED }
            }
            val failedCount = remember(allTransactions) {
              allTransactions.count { it.statusCategory() == PaymentStatusCategory.FAILED }
            }
            val pendingTotal = remember(allTransactions) {
              allTransactions
                .filter { it.statusCategory() == PaymentStatusCategory.PENDING }
                .sumOf { it.amountUsdc }
            }
            val completedTotal = remember(allTransactions) {
              allTransactions
                .filter { it.statusCategory() == PaymentStatusCategory.COMPLETED }
                .sumOf { it.amountUsdc }
            }

            val filteredTransactions = remember(allTransactions, selectedFilter, searchQuery) {
              val byStatus = when (selectedFilter) {
                TransactionStatusFilter.ALL -> allTransactions
                TransactionStatusFilter.PENDING -> allTransactions.filter { it.statusCategory() == PaymentStatusCategory.PENDING }
                TransactionStatusFilter.COMPLETED -> allTransactions.filter { it.statusCategory() == PaymentStatusCategory.COMPLETED }
                TransactionStatusFilter.FAILED -> allTransactions.filter { it.statusCategory() == PaymentStatusCategory.FAILED }
              }
              val query = searchQuery.trim()
              if (query.isEmpty()) {
                byStatus
              } else {
                byStatus.filter { tx ->
                  tx.vendorName.contains(query, ignoreCase = true) ||
                    tx.agentName.contains(query, ignoreCase = true) ||
                    tx.protocol.contains(query, ignoreCase = true) ||
                    tx.chain.contains(query, ignoreCase = true) ||
                    tx.id.contains(query, ignoreCase = true) ||
                    tx.walletId.contains(query, ignoreCase = true) ||
                    tx.status.contains(query, ignoreCase = true)
                }
              }
            }

            AnimatedVisibility(visible = uiState.isRefreshing) {
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(
              modifier = Modifier
                .fillMaxSize()
                .testTag("tx_history_list"),
              contentPadding = PaddingValues(16.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              // Summary Hero Card
              item(key = "summary_card") {
                TransactionSummaryHeroCard(
                  totalCount = allTransactions.size,
                  pendingCount = pendingCount,
                  pendingTotal = pendingTotal,
                  completedCount = completedCount,
                  completedTotal = completedTotal
                )
              }

              // Search Field
              item(key = "search_bar") {
                OutlinedTextField(
                  value = searchQuery,
                  onValueChange = { searchQuery = it },
                  placeholder = { Text(stringResource(R.string.tx_history_search_hint)) },
                  leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                  trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                      IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                      }
                    }
                  },
                  singleLine = true,
                  shape = RoundedCornerShape(14.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tx_history_search_input")
                )
              }

              // Status Filter Chips
              item(key = "filter_chips") {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  FilterChip(
                    selected = selectedFilter == TransactionStatusFilter.ALL,
                    onClick = { selectedFilter = TransactionStatusFilter.ALL },
                    label = { Text("${stringResource(R.string.tx_history_filter_all)} (${allTransactions.size})") },
                    modifier = Modifier.testTag("tx_filter_all")
                  )
                  FilterChip(
                    selected = selectedFilter == TransactionStatusFilter.PENDING,
                    onClick = { selectedFilter = TransactionStatusFilter.PENDING },
                    leadingIcon = {
                      Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                      )
                    },
                    label = { Text("${stringResource(R.string.tx_history_filter_pending)} ($pendingCount)") },
                    modifier = Modifier.testTag("tx_filter_pending")
                  )
                  FilterChip(
                    selected = selectedFilter == TransactionStatusFilter.COMPLETED,
                    onClick = { selectedFilter = TransactionStatusFilter.COMPLETED },
                    leadingIcon = {
                      Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                      )
                    },
                    label = { Text("${stringResource(R.string.tx_history_filter_completed)} ($completedCount)") },
                    modifier = Modifier.testTag("tx_filter_completed")
                  )
                  if (failedCount > 0 || selectedFilter == TransactionStatusFilter.FAILED) {
                    FilterChip(
                      selected = selectedFilter == TransactionStatusFilter.FAILED,
                      onClick = { selectedFilter = TransactionStatusFilter.FAILED },
                      leadingIcon = {
                        Icon(
                          Icons.Default.Cancel,
                          contentDescription = null,
                          modifier = Modifier.size(16.dp),
                          tint = MaterialTheme.colorScheme.error
                        )
                      },
                      label = { Text("${stringResource(R.string.tx_history_filter_failed)} ($failedCount)") },
                      modifier = Modifier.testTag("tx_filter_failed")
                    )
                  }
                }
              }

              // Empty or List Records
              if (filteredTransactions.isEmpty()) {
                item(key = "empty_state") {
                  Card(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(top = 8.dp)
                      .testTag("tx_history_empty"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                      containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                  ) {
                    Column(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                      horizontalAlignment = Alignment.CenterHorizontally,
                      verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                      Icon(
                        imageVector = Icons.Default.ReceiptLong,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                      )
                      Text(
                        text = stringResource(R.string.tx_history_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                      )
                      Text(
                        text = stringResource(R.string.tx_history_empty_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                      OutlinedButton(
                        onClick = onRefresh,
                        modifier = Modifier.minimumInteractiveComponentSize()
                      ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.tx_history_refresh))
                      }
                    }
                  }
                }
              } else {
                items(
                  items = filteredTransactions,
                  key = { it.id }
                ) { tx ->
                  TransactionRecordCard(
                    transaction = tx,
                    formattedDate = dateFormat.format(Date(tx.timestamp)),
                    onClick = { selectedTransaction = tx }
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  selectedTransaction?.let { tx ->
    TransactionDetailDialog(
      transaction = tx,
      formattedDate = dateFormat.format(Date(tx.timestamp)),
      onDismiss = { selectedTransaction = null }
    )
  }
}

@Composable
private fun TransactionSummaryHeroCard(
  totalCount: Int,
  pendingCount: Int,
  pendingTotal: Long,
  completedCount: Int,
  completedTotal: Long
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(20.dp))
      .background(
        Brush.linearGradient(
          listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            MaterialTheme.colorScheme.secondary
          )
        )
      )
      .padding(18.dp)
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "FIRESTORE PAYMENT LEDGER",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = Color.White.copy(alpha = 0.9f)
        )
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = Color.Black.copy(alpha = 0.22f)
        ) {
          Text(
            text = "$totalCount Records",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Surface(
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(14.dp),
          color = Color.White.copy(alpha = 0.16f)
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "Pending payments",
                tint = Color(0xFFFDE68A),
                modifier = Modifier.size(16.dp)
              )
              Text(
                text = "Pending ($pendingCount)",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.9f)
              )
            }
            Text(
              text = CurrencyUtils.formatUsd(pendingTotal),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
          }
        }

        Surface(
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(14.dp),
          color = Color.White.copy(alpha = 0.16f)
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Completed payments",
                tint = Color(0xFFA7F3D0),
                modifier = Modifier.size(16.dp)
              )
              Text(
                text = "Completed ($completedCount)",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.9f)
              )
            }
            Text(
              text = CurrencyUtils.formatUsd(completedTotal),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
          }
        }
      }
    }
  }
}

@Composable
private fun TransactionRecordCard(
  transaction: FirestoreTransaction,
  formattedDate: String,
  onClick: () -> Unit
) {
  val category = transaction.statusCategory()
  val accentColor = when (category) {
    PaymentStatusCategory.PENDING -> MaterialTheme.colorScheme.tertiary
    PaymentStatusCategory.COMPLETED -> MaterialTheme.colorScheme.secondary
    PaymentStatusCategory.FAILED -> MaterialTheme.colorScheme.error
  }

  Card(
    onClick = onClick,
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.22f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("tx_card_${transaction.id}")
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
      ) {
        Row(
          modifier = Modifier.weight(1f),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(
            modifier = Modifier
              .size(44.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(accentColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = when (category) {
                PaymentStatusCategory.PENDING -> Icons.Default.Schedule
                PaymentStatusCategory.COMPLETED -> Icons.Default.CheckCircle
                PaymentStatusCategory.FAILED -> Icons.Default.Cancel
              },
              contentDescription = transaction.statusDisplayLabel(),
              tint = accentColor,
              modifier = Modifier.size(22.dp)
            )
          }

          Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
              text = transaction.vendorName.ifBlank { "Payment Transfer" },
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = buildString {
                if (transaction.agentName.isNotBlank()) {
                  append(transaction.agentName)
                } else if (transaction.walletId.isNotBlank()) {
                  append("Wallet ${transaction.walletId.take(10)}")
                } else {
                  append("Tx ${transaction.id.take(12)}")
                }
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        Column(
          horizontalAlignment = Alignment.End,
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(
            text = CurrencyUtils.formatAmount(transaction.amountUsdc, transaction.currency),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
          )
          PaymentStatusBadge(
            category = category,
            label = transaction.statusDisplayLabel(),
            rawStatus = transaction.status
          )
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          if (transaction.protocol.isNotBlank()) {
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            ) {
              Text(
                text = transaction.protocol,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
              )
            }
          }
          if (transaction.chain.isNotBlank()) {
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = MaterialTheme.colorScheme.surfaceVariant
            ) {
              Text(
                text = transaction.chain,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
              )
            }
          }
        }

        Text(
          text = formattedDate,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
private fun PaymentStatusBadge(
  category: PaymentStatusCategory,
  label: String,
  rawStatus: String
) {
  val statusColor = when (category) {
    PaymentStatusCategory.PENDING -> MaterialTheme.colorScheme.tertiary
    PaymentStatusCategory.COMPLETED -> MaterialTheme.colorScheme.secondary
    PaymentStatusCategory.FAILED -> MaterialTheme.colorScheme.error
  }

  val pulseAlpha = if (category == PaymentStatusCategory.PENDING) {
    val transition = rememberInfiniteTransition(label = "pending_pulse")
    transition.animateFloat(
      initialValue = 0.35f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(
        animation = tween(durationMillis = 900),
        repeatMode = RepeatMode.Reverse
      ),
      label = "pending_dot_alpha"
    ).value
  } else {
    1f
  }

  Surface(
    shape = RoundedCornerShape(999.dp),
    color = statusColor.copy(alpha = 0.14f),
    modifier = Modifier.testTag("tx_status_badge_${rawStatus.lowercase()}")
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Box(
        modifier = Modifier
          .size(8.dp)
          .alpha(pulseAlpha)
          .clip(CircleShape)
          .background(statusColor)
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = statusColor
      )
    }
  }
}

@Composable
private fun UnauthenticatedTransactionsView(
  onNavigateToAuth: () -> Unit,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier.testTag("tx_history_unauthenticated"),
    contentAlignment = Alignment.Center
  ) {
    Card(
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
      Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Box(
          modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
          )
        }
        Text(
          text = stringResource(R.string.tx_history_auth_title),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = stringResource(R.string.tx_history_auth_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
          onClick = onNavigateToAuth,
          modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .testTag("tx_history_signin_btn")
        ) {
          Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(R.string.tx_history_sign_in_cta))
        }
      }
    }
  }
}

@Composable
private fun TransactionErrorView(
  message: String,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier.testTag("tx_history_error"),
    contentAlignment = Alignment.Center
  ) {
    Card(
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
      Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Icon(
          imageVector = Icons.Default.ErrorOutline,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(40.dp)
        )
        Text(
          text = "Unable to Load Transactions",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onErrorContainer
        )
        Text(
          text = message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onErrorContainer
        )
        Button(
          onClick = onRetry,
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("tx_history_retry_btn")
        ) {
          Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(R.string.tx_history_retry))
        }
      }
    }
  }
}

@Composable
private fun TransactionDetailDialog(
  transaction: FirestoreTransaction,
  formattedDate: String,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(transaction.vendorName.ifBlank { "Transaction Record" }, fontWeight = FontWeight.Bold)
        PaymentStatusBadge(
          category = transaction.statusCategory(),
          label = transaction.statusDisplayLabel(),
          rawStatus = transaction.status
        )
      }
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailRow("Transaction ID", transaction.id, monospace = true)
        DetailRow("Amount", CurrencyUtils.formatAmount(transaction.amountUsdc, transaction.currency))
        DetailRow("Raw Status", transaction.status.ifBlank { "PENDING" })
        DetailRow("Timestamp", formattedDate)
        if (transaction.walletId.isNotBlank()) DetailRow("Wallet ID", transaction.walletId, monospace = true)
        if (transaction.agentName.isNotBlank()) DetailRow("Agent", transaction.agentName)
        if (transaction.protocol.isNotBlank()) DetailRow("Protocol", transaction.protocol)
        if (transaction.chain.isNotBlank()) DetailRow("Network / Chain", transaction.chain)
        if (transaction.debitAccountId.isNotBlank()) DetailRow("Debit Account", transaction.debitAccountId, monospace = true)
        if (transaction.creditAccountId.isNotBlank()) DetailRow("Credit Account", transaction.creditAccountId, monospace = true)
        if (transaction.receiptHash.isNotBlank()) DetailRow("Receipt Hash", transaction.receiptHash, monospace = true)
        if (transaction.challengeDetails.isNotBlank()) DetailRow("Details", transaction.challengeDetails)
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Close")
      }
    }
  )
}

@Composable
private fun DetailRow(
  label: String,
  value: String,
  monospace: Boolean = false
) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall,
      fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
      fontWeight = FontWeight.Medium
    )
  }
}
