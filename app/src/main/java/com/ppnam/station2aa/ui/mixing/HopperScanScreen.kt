package com.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.domain.model.HopperAvailability
import com.ppnam.station2aa.domain.model.HopperStatus
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.theme.*

@Composable
fun HopperScanScreen(
    orderNo: String,
    onProceed: () -> Unit,
    onRfidLookup: () -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val hopperStatuses = remember { mutableStateListOf<HopperStatus>() }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == MixingNavDestination.PREMIX_COMPLETE) onProceed()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startListeningForHopperBarcode(orderNo)
    }

    AppScaffold(
        title = "Scan Hopper",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack,
        onRfidLookup = onRfidLookup
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hopperStatuses.isNotEmpty()) {
                Text("Hopper Status", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(hopperStatuses) { hopper ->
                        val chipColor = when (hopper.status) {
                            HopperAvailability.AVAILABLE -> SuccessGreen
                            HopperAvailability.IN_USE -> DangerRed
                            HopperAvailability.OFFLINE -> TextMuted
                        }
                        SuggestionChip(
                            onClick = {},
                            label = { Text(hopper.hopperCode, color = chipColor) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = chipColor.copy(alpha = 0.10f)
                            ),
                            border = SuggestionChipDefaults.suggestionChipBorder(
                                enabled = true,
                                borderColor = chipColor.copy(alpha = 0.35f),
                                disabledBorderColor = GraphiteBorder
                            )
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            Spacer(Modifier.weight(1f))

            when (val state = uiState) {
                is MixingUiState.Loading -> {
                    CircularProgressIndicator(color = AmberPrimary)
                }
                is MixingUiState.HopperUnavailable -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.12f)),
                        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Hopper ${state.hopperCode} unavailable",
                                style = MaterialTheme.typography.bodyLarge,
                                color = DangerRed
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(state.reason, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurfaceVariant),
                        border = BorderStroke(1.dp, GraphiteBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Wifi, null, tint = AmberPrimary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Scan another hopper barcode", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                        }
                    }
                }
                else -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurfaceVariant),
                        border = BorderStroke(1.dp, GraphiteBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Wifi, null, tint = AmberPrimary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Scan hopper barcode to allocate", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }
}
