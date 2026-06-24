package com.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun MixerCodeScreen(
    orderNo: String,
    onProceed: () -> Unit,
    viewModel: MixingViewModel = hiltViewModel()
) {
    val mixerCode by viewModel.mixerCode.collectAsState()

    // Sync scans on this screen (barcode scans auto-fill the mixer code field)
    LaunchedEffect(orderNo) {
        viewModel.startListeningForScans(orderNo)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Enter Mixer Code", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("Scan barcode or type manually", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = mixerCode,
            onValueChange = { viewModel.setMixerCode(it) },
            label = { Text("Mixer Code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onProceed,
            enabled = mixerCode.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Review & Complete")
        }
    }
}
