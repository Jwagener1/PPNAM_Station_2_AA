package com.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.theme.*

@Composable
fun IngredientScanScreen(
    orderNo: String,
    onProceedToMixerCode: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scannedIngredients by viewModel.scannedIngredients.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()

    LaunchedEffect(orderNo) { viewModel.startListeningForScans(orderNo) }

    AppScaffold(
        title = "Scan Ingredients",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                        Text(state.message, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is MixingUiState.OrderLoaded -> {
                    val order = state.order
                    val satisfiedCount = order.lines.count { bomLine ->
                        scannedIngredients.count { it.itemCode == bomLine.itemCode } >= bomLine.requiredQty.toInt()
                    }

                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = GraphiteSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Order $orderNo", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            Text(
                                "$satisfiedCount of ${order.lines.size} satisfied",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted
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

                            ElevatedCard(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (satisfied) SuccessGreen.copy(alpha = 0.12f) else GraphiteSurface
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
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = "$scannedCount / $required",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { fraction },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = if (satisfied) SuccessGreen else AmberPrimary,
                                        trackColor = GraphiteBorder
                                    )
                                }
                            }
                        }
                    }
                }
                else -> Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onProceedToMixerCode,
                enabled = scannedIngredients.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Proceed to Mixer Code")
            }
        }
    }
}
