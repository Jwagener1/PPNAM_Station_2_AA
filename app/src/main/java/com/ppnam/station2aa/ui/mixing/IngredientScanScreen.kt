package com.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.theme.*

@Composable
fun IngredientScanScreen(
    orderNo: String,
    onProceedToHopperScan: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scannedIngredients by viewModel.scannedIngredients.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var showCancelDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.supervisorError.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(orderNo) { viewModel.startListeningForScans(orderNo) }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel this job card?", color = TextPrimary) },
            text = {
                Text(
                    "Any scanned ingredients on this job will be discarded. You can look up the correct job card afterwards.",
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    viewModel.cancelJob()
                    onBack()
                }) { Text("Cancel Job", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("Keep Scanning") }
            },
            containerColor = GraphiteSurface
        )
    }

    AppScaffold(
        title = "Scan Ingredients",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = { showCancelDialog = true }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                when (val state = uiState) {
                    is MixingUiState.Loading -> {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AmberPrimary)
                        }
                    }
                    is MixingUiState.Error -> {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.12f)),
                                border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    state.message,
                                    color = DangerRed,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                    is MixingUiState.OrderLoaded -> {
                        val order = state.order
                        val satisfiedCount = order.lines.count { bomLine ->
                            scannedIngredients.count { it.itemCode == bomLine.itemCode } >= bomLine.requiredQty.toInt()
                        }
                        val allSatisfied = satisfiedCount == order.lines.size

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (allSatisfied) SuccessGreen.copy(alpha = 0.12f) else GraphiteSurfaceVariant
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (allSatisfied) SuccessGreen.copy(alpha = 0.35f) else GraphiteBorder
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Order $orderNo", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                                order.productBeingMade?.let { productName ->
                                    Spacer(Modifier.height(2.dp))
                                    Text(productName, style = MaterialTheme.typography.bodyMedium, color = AmberPrimary)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "$satisfiedCount of ${order.lines.size} lines satisfied",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (allSatisfied) SuccessGreen else TextMuted
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(order.lines) { bomLine ->
                                val scannedCount = scannedIngredients.count { it.itemCode == bomLine.itemCode }
                                val required = bomLine.requiredQty.toInt().coerceAtLeast(1)
                                val satisfied = scannedCount >= required
                                val fraction = (scannedCount.toFloat() / required.toFloat()).coerceIn(0f, 1f)
                                val displayName = bomLine.itemName.ifBlank { bomLine.itemCode }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (satisfied) SuccessGreen.copy(alpha = 0.10f) else GraphiteSurface
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (satisfied) SuccessGreen.copy(alpha = 0.30f) else GraphiteBorder
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = displayName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = TextPrimary,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (satisfied) {
                                                Icon(
                                                    imageVector = Icons.Filled.CheckCircle,
                                                    contentDescription = "Satisfied",
                                                    tint = SuccessGreen,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Text(
                                                text = "$scannedCount / $required",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (satisfied) SuccessGreen else TextMuted
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        LinearProgressIndicator(
                                            progress = { fraction },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = if (satisfied) SuccessGreen else AmberPrimary,
                                            trackColor = GraphiteBorder
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is MixingUiState.IngredientInvalid -> {
                        Spacer(Modifier.weight(1f))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = WarningOrange.copy(alpha = 0.12f)),
                            border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.35f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Warning, null, tint = WarningOrange, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Invalid Ingredient", style = MaterialTheme.typography.titleMedium, color = WarningOrange)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("Tag: ${state.tagId}", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                Text(state.reason, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.discardInvalidIngredient() },
                                modifier = Modifier.weight(1f).height(56.dp),
                                border = BorderStroke(1.dp, GraphiteBorder)
                            ) {
                                Text("Discard", color = TextPrimary)
                            }
                            Button(
                                onClick = { viewModel.requestSupervisorOverride(state.tagId, state.reason) },
                                modifier = Modifier.weight(1f).height(56.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = WarningOrange)
                            ) {
                                Text("Override")
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    is MixingUiState.WaitingForSupervisor -> {
                        Spacer(Modifier.weight(1f))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = AmberPrimary.copy(alpha = 0.08f)),
                            border = BorderStroke(1.dp, AmberPrimary.copy(alpha = 0.3f))
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Filled.WifiTethering, null, tint = AmberPrimary, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Scan supervisor tag to approve",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextPrimary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Tag: ${state.tagId}  •  ${state.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { viewModel.discardInvalidIngredient() },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            border = BorderStroke(1.dp, GraphiteBorder)
                        ) {
                            Text("Cancel Override", color = TextPrimary)
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    else -> Spacer(Modifier.weight(1f))
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onProceedToHopperScan,
                    enabled = scannedIngredients.isNotEmpty() && uiState is MixingUiState.OrderLoaded,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("Proceed to Hopper Scan")
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
