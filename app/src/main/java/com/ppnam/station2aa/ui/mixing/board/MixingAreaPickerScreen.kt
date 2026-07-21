package com.ppnam.station2aa.ui.mixing.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ppnam.station2aa.domain.model.MixingArea
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.theme.AmberPrimary
import com.ppnam.station2aa.ui.theme.DangerRed
import com.ppnam.station2aa.ui.theme.GraphiteBorder
import com.ppnam.station2aa.ui.theme.GraphiteSurface
import com.ppnam.station2aa.ui.theme.SuccessGreen
import com.ppnam.station2aa.ui.theme.TextMuted
import com.ppnam.station2aa.ui.theme.TextPrimary

@Composable
fun MixingAreaPickerScreen(
    pendingCollectionId: String?,
    onAreaChosen: (MixingArea) -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: MixingBoardViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val session by viewModel.session.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadAreaPicker(pendingCollectionId) }
    LaunchedEffect(Unit) { viewModel.logoutEvent.collect { onLogout() } }

    AppScaffold(
        title = "Mixing",
        status = connectionStatus,
        onBack = onBack,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout,
    ) { padding ->
        when (val state = uiState) {
            is MixingBoardUiState.Loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = AmberPrimary) }

            is MixingBoardUiState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.message, color = DangerRed)
                Button(onClick = { viewModel.loadAreaPicker(pendingCollectionId) }) { Text("Retry") }
            }

            is MixingBoardUiState.AreaPicker -> LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.pendingCollectionId?.let { pending ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                            border = BorderStroke(1.dp, AmberPrimary),
                        ) {
                            Text(
                                "$pending ready to mix — pick an area",
                                Modifier.padding(12.dp),
                                color = AmberPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                items(MixingArea.entries) { area ->
                    val equipment = state.overview.equipment.filter { it.area == area }
                    val available = equipment.count { it.isEnabled && it.status == "Available" }
                    val cycles = state.overview.activeCycles.count { it.area == area }
                    val mixes = state.overview.readyMixes.count { it.area == area }
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onAreaChosen(area) },
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, GraphiteBorder),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(area.display, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$available machine(s) available · $cycles active cycle(s) · $mixes ready mix(es)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (mixes > 0) SuccessGreen else TextMuted,
                            )
                        }
                    }
                }
            }

            // The Board state belongs to the area-board route; nothing to render here.
            is MixingBoardUiState.Board -> Unit
        }
    }
}

// Replaced by the real board screen in the next task; nav wiring compiles today.
@Composable
fun MixingBoardScreen(
    area: MixingArea,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: MixingBoardViewModel,
) {
    Text("Board for ${area.display}", color = TextPrimary)
}
