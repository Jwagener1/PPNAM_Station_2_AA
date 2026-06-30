package com.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.ui.components.AppScaffold

// NOTE: This screen is superseded by the hopper workflow and will be removed in Task 5.
@Composable
fun MixerCodeScreen(
    orderNo: String,
    onProceed: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()

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
            Text("This screen is no longer in use.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onProceed, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Continue")
            }
        }
    }
}
