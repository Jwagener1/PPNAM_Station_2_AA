package com.ppnam.station2aa.ui.rajoo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.navigation.NavRoutes
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.components.LabelValueRow
import com.ppnam.station2aa.ui.components.ScanPromptCard
import com.ppnam.station2aa.ui.theme.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PalletAllocScreen(
    machineCode: String,
    onDone: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: RajooViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()

    LaunchedEffect(machineCode) { viewModel.startListeningForScans(machineCode) }
    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == NavRoutes.HOME) onDone()
        }
    }

    val formatter = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.systemDefault())

    AppScaffold(
        title = "Allocate — $machineCode",
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
                    is RajooUiState.Idle -> {
                        ScanPromptCard(message = "Scan RFID pallet tag")
                    }
                    is RajooUiState.Loading -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AmberPrimary)
                            Spacer(Modifier.height(12.dp))
                            Text("Allocating…", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        }
                    }
                    is RajooUiState.AllocationSuccess -> {
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
                                        Text("Allocated", style = MaterialTheme.typography.headlineSmall, color = SuccessGreen)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    LabelValueRow("Pre-Mix ID", state.record.preMixId)
                                    LabelValueRow("Machine", state.record.machineCode)
                                    LabelValueRow("Time", formatter.format(state.record.allocatedAt))
                                }
                            }
                        }
                    }
                    is RajooUiState.Error -> {
                        val isQueued = state.message.startsWith("Offline")
                        val accentColor = if (isQueued) WarningOrange else DangerRed
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.10f)),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.30f))
                        ) {
                            Row(Modifier.fillMaxWidth()) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .width(4.dp)
                                        .background(accentColor)
                                )
                                Column(modifier = Modifier.padding(16.dp)) {
                                    if (isQueued) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Schedule, null, tint = WarningOrange, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Queued", style = MaterialTheme.typography.headlineSmall, color = WarningOrange)
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            "Allocation queued — will send when online",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextMuted
                                        )
                                    } else {
                                        Text(state.message, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }

            Spacer(Modifier.height(16.dp))
            when (uiState) {
                is RajooUiState.AllocationSuccess, is RajooUiState.Error -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.startListeningForScans(machineCode) },
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) { Text("Allocate Another") }
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
