package com.ppnam.station2aa.ui.rajoo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.navigation.NavRoutes

@Composable
fun PalletAllocScreen(
    machineCode: String,
    onDone: () -> Unit,
    viewModel: RajooViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(machineCode) {
        viewModel.startListeningForScans(machineCode)
    }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == NavRoutes.HOME) onDone()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Allocate Pallet",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Machine: $machineCode",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(24.dp))

        when (val state = uiState) {
            is RajooUiState.Idle -> {
                Text(
                    text = "Scan RFID tag to allocate pallet…",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            is RajooUiState.Loading -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Allocating…", style = MaterialTheme.typography.bodyMedium)
            }
            is RajooUiState.AllocationSuccess -> {
                Text(
                    text = "Allocated successfully",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Pre-Mix ID: ${state.record.preMixId}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done")
                }
            }
            is RajooUiState.Error -> {
                Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Scan again to retry",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            else -> Unit
        }
    }
}
