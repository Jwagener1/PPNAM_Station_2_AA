package com.ppnam.station2aa.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.ui.components.ConnectionStatusBar

@Composable
fun HomeScreen(
    onNavigateMixing: () -> Unit,
    onNavigateRajoo: () -> Unit,
    onNavigateRfidRecovery: () -> Unit,
    onNavigateDashboard: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionStatusBar(state = connectionState, pendingCount = pendingCount)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            Text("PPNAM Station 2", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onNavigateMixing, modifier = Modifier.fillMaxWidth()) {
                Text("Mixing")
            }
            Button(onClick = onNavigateRajoo, modifier = Modifier.fillMaxWidth()) {
                Text("Rajoo Allocation")
            }
            Button(onClick = onNavigateRfidRecovery, modifier = Modifier.fillMaxWidth()) {
                Text("RFID Recovery")
            }
            OutlinedButton(onClick = onNavigateDashboard, modifier = Modifier.fillMaxWidth()) {
                Text("Dashboard")
            }
        }
    }
}
