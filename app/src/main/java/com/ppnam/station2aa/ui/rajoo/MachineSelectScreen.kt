package com.ppnam.station2aa.ui.rajoo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun MachineSelectScreen(
    onMachineSelected: (machineCode: String) -> Unit,
    viewModel: RajooViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadMachines()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Select Machine", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        when (val state = uiState) {
            is RajooUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is RajooUiState.MachinesLoaded -> {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(state.machines) { machine ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onMachineSelected(machine) }
                        ) {
                            Text(
                                text = machine,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
            is RajooUiState.Error -> {
                Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.loadMachines() }) {
                    Text("Retry")
                }
            }
            else -> Unit
        }
    }
}
