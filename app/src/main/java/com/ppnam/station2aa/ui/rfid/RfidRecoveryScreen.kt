package com.ppnam.station2aa.ui.rfid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.components.LabelValueRow
import com.ppnam.station2aa.ui.components.ScanPromptCard
import com.ppnam.station2aa.ui.theme.*

@Composable
fun RfidRecoveryScreen(
    onDone: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: RfidViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()

    LaunchedEffect(Unit) { viewModel.startListening() }

    AppScaffold(
        title = "RFID Recovery",
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
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val state = uiState) {
                    is RfidUiState.Idle -> {
                        ScanPromptCard(message = "Scan an RFID tag to look up a pallet")
                    }
                    is RfidUiState.Loading -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AmberPrimary)
                            Spacer(Modifier.height(12.dp))
                            Text("Looking up pallet…", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        }
                    }
                    is RfidUiState.PalletFound -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                            border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.35f))
                        ) {
                            Row(Modifier.fillMaxWidth()) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .width(4.dp)
                                        .background(SuccessGreen)
                                )
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Pallet Found", style = MaterialTheme.typography.headlineSmall, color = SuccessGreen)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    LabelValueRow("Tag ID", state.pallet.tagId)
                                    LabelValueRow("Batch No", state.pallet.batchNo)
                                    LabelValueRow("Item Code", state.pallet.itemCode)
                                    LabelValueRow("Location", state.pallet.location)
                                }
                            }
                        }
                    }
                    is RfidUiState.Error -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.10f)),
                            border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.30f))
                        ) {
                            Text(
                                text = state.message,
                                color = DangerRed,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            when (uiState) {
                is RfidUiState.PalletFound, is RfidUiState.Error -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.resetToIdle() },
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) {
                            Text(if (uiState is RfidUiState.Error) "Try Again" else "Scan Another")
                        }
                        OutlinedButton(
                            onClick = onDone,
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) { Text("Done") }
                    }
                }
                else -> Unit
            }
        }
    }
}
