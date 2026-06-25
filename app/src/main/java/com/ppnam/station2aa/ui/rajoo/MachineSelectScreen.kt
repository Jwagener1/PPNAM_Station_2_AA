package com.ppnam.station2aa.ui.rajoo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.theme.*

@Composable
fun MachineSelectScreen(
    onMachineSelected: (machineCode: String) -> Unit,
    onBack: () -> Unit = {},
    viewModel: RajooViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadMachines() }

    AppScaffold(
        title = "Select Machine",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when (val state = uiState) {
                is RajooUiState.Loading -> {
                    CircularProgressIndicator(color = AmberPrimary, modifier = Modifier.align(Alignment.Center))
                }
                is RajooUiState.MachinesLoaded -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.machines) { machine ->
                            ElevatedCard(
                                onClick = { onMachineSelected(machine) },
                                modifier = Modifier.height(120.dp),
                                colors = CardDefaults.elevatedCardColors(containerColor = GraphiteSurface)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Factory,
                                        contentDescription = null,
                                        tint = AmberPrimary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = machine,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
                is RajooUiState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ElevatedCard(
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = DangerRed.copy(alpha = 0.12f)
                            )
                        ) {
                            Text(
                                text = state.message,
                                color = DangerRed,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadMachines() }) { Text("Retry") }
                    }
                }
                else -> Unit
            }
        }
    }
}
