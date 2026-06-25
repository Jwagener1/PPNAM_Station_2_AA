package com.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.components.LabelValueRow
import com.ppnam.station2aa.ui.theme.*

@Composable
fun PreMixCompleteScreen(
    orderNo: String,
    onCompleted: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scannedIngredients by viewModel.scannedIngredients.collectAsState()
    val mixerCode by viewModel.mixerCode.collectAsState()
    val isQueuedOffline by viewModel.isQueuedOffline.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var showConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == MixingNavDestination.PREMIX_COMPLETE) showConfirmation = true
        }
    }

    if (showConfirmation) {
        PremixConfirmedContent(
            isQueuedOffline = isQueuedOffline,
            connectionState = connectionState,
            pendingCount = pendingCount,
            onDone = onCompleted
        )
        return
    }

    val isLoading = uiState is MixingUiState.Loading
    val errorMessage = if (uiState is MixingUiState.Error) (uiState as MixingUiState.Error).message else null

    AppScaffold(
        title = "Review Pre-Mix",
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip(onClick = {}, label = { Text("Order $orderNo") })
                SuggestionChip(onClick = {}, label = { Text("Mixer: $mixerCode") })
            }
            Spacer(Modifier.height(16.dp))
            ElevatedCard(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = GraphiteSurface)
            ) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    items(scannedIngredients) { ingredient ->
                        LabelValueRow(label = ingredient.itemCode, value = "Qty: ${ingredient.qty.toInt()}")
                        HorizontalDivider(color = GraphiteBorder)
                    }
                }
            }
            errorMessage?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(err, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.completePremix(orderNo) },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text("Confirm & Complete")
            }
        }
    }
}

@Composable
private fun PremixConfirmedContent(
    isQueuedOffline: Boolean,
    connectionState: MqttConnectionState,
    pendingCount: Int,
    onDone: () -> Unit
) {
    AppScaffold(
        title = "Pre-Mix Complete",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = null
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isQueuedOffline) {
                Icon(Icons.Filled.Schedule, null, tint = AmberPrimary, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(24.dp))
                Text("Pre-mix queued", style = MaterialTheme.typography.displaySmall, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text("Will send when online", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            } else {
                Icon(Icons.Filled.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(24.dp))
                Text("Pre-mix confirmed by WPF", style = MaterialTheme.typography.displaySmall, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text("Order sent successfully", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }
            Spacer(Modifier.height(40.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Done")
            }
        }
    }
}
