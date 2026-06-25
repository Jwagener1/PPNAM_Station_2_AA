package com.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.theme.AmberPrimary
import com.ppnam.station2aa.ui.theme.GraphiteSurfaceVariant
import com.ppnam.station2aa.ui.theme.TextPrimary

@Composable
fun MixerCodeScreen(
    orderNo: String,
    onProceed: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val mixerCode by viewModel.mixerCode.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()

    LaunchedEffect(Unit) { viewModel.startListeningForBarcode() }

    AppScaffold(
        title = "Mixer Code",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = GraphiteSurfaceVariant)
            ) {
                Text(
                    text = "Scan barcode or enter the mixer code manually",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = mixerCode,
                onValueChange = { viewModel.setMixerCode(it) },
                label = { Text("Mixer Code") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AmberPrimary,
                    focusedLabelColor = AmberPrimary,
                    cursorColor = AmberPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onProceed,
                enabled = mixerCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Review & Complete")
            }
        }
    }
}
