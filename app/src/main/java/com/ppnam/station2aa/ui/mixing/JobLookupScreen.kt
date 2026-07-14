package com.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.theme.AmberPrimary
import com.ppnam.station2aa.ui.theme.DangerRed
import com.ppnam.station2aa.ui.theme.GraphiteBorder
import com.ppnam.station2aa.ui.theme.GraphiteSurface
import com.ppnam.station2aa.ui.theme.TextMuted
import com.ppnam.station2aa.ui.theme.TextPrimary

@Composable
fun JobLookupScreen(
    onJobFound: (orderNo: String) -> Unit,
    onSettings: () -> Unit = {},
    onLogout: () -> Unit = {},
    onRfidLookup: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val activeJobs by viewModel.activeJobs.collectAsState()
    val activeJobsError by viewModel.activeJobsError.collectAsState()
    val session by viewModel.session.collectAsState()
    var orderInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadActiveJobs() }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == MixingNavDestination.JOB_LOADED) {
                (viewModel.uiState.value as? MixingUiState.OrderLoaded)?.let { onJobFound(it.order.docNo) }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.logoutEvent.collect { onLogout() }
    }

    val isLoading = uiState is MixingUiState.Loading
    val errorMessage = if (uiState is MixingUiState.Error) (uiState as MixingUiState.Error).message else null

    AppScaffold(
        title = "Job Lookup",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = null,
        onRfidLookup = onRfidLookup,
        onSettings = onSettings,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (activeJobs.isNotEmpty()) {
                Text(
                    "Active Jobs",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(activeJobs) { job ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isLoading) { viewModel.lookupJob(job.jobCardNumber, job.preMixId) },
                            colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                            border = BorderStroke(1.dp, GraphiteBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(job.jobCardNumber, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                                if (job.productName.isNotBlank()) {
                                    Text(job.productName, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            } else if (activeJobsError != null) {
                Text(
                    text = activeJobsError ?: "",
                    color = DangerRed,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = orderInput,
                onValueChange = { orderInput = it },
                label = { Text("Production Order No.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.lookupJob(orderInput) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AmberPrimary,
                    focusedLabelColor = AmberPrimary,
                    cursorColor = AmberPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.lookupJob(orderInput) },
                enabled = orderInput.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text("Look Up")
            }
            errorMessage?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(text = err, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
