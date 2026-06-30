package com.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val mixerCode by viewModel.hopperCode.collectAsState()
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
                SuggestionChip(
                    onClick = {},
                    label = { Text("Order $orderNo", color = TextPrimary) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = GraphiteSurfaceVariant
                    ),
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = GraphiteBorder,
                        disabledBorderColor = GraphiteBorder
                    )
                )
                SuggestionChip(
                    onClick = {},
                    label = { Text("Mixer: $mixerCode", color = TextPrimary) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = GraphiteSurfaceVariant
                    ),
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = AmberPrimary.copy(alpha = 0.4f),
                        disabledBorderColor = GraphiteBorder
                    )
                )
            }
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                border = BorderStroke(1.dp, GraphiteBorder)
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
    val accentColor = if (isQueuedOffline) WarningOrange else SuccessGreen
    val icon = if (isQueuedOffline) Icons.Filled.Schedule else Icons.Filled.CheckCircle
    val headline = if (isQueuedOffline) "Pre-mix queued" else "Pre-mix confirmed"
    val subtext = if (isQueuedOffline) "Will send when online" else "Order received by WPF"

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
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.10f))
                    .border(1.dp, accentColor.copy(alpha = 0.30f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accentColor, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text(headline, style = MaterialTheme.typography.displaySmall, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(subtext, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            Spacer(Modifier.height(48.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Done")
            }
        }
    }
}
