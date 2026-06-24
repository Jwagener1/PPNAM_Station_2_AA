package com.ppnam.station2aa.ui.rfid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun RfidRecoveryScreen(
    onDone: () -> Unit,
    viewModel: RfidViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.startListening() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("RFID Recovery", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        when (val state = uiState) {
            is RfidUiState.Idle -> {
                Text(
                    "Scan an RFID tag to look up a pallet",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            is RfidUiState.Loading -> {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Looking up pallet…", style = MaterialTheme.typography.bodyMedium)
            }
            is RfidUiState.PalletFound -> {
                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Pallet Found",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Tag ID: ${state.pallet.tagId}")
                        Text("Batch No: ${state.pallet.batchNo}")
                        Text("Item Code: ${state.pallet.itemCode}")
                        Text("Location: ${state.pallet.location}")
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { viewModel.resetToIdle() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Scan Another")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done")
                }
            }
            is RfidUiState.Error -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.resetToIdle() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Try Again")
                }
            }
        }
    }
}
