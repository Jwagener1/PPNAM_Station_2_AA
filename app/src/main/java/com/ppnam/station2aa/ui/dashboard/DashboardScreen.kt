package com.ppnam.station2aa.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.components.LabelValueRow
import com.ppnam.station2aa.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBack: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Pallet", "Pre-Mix", "Allocation", "Exceptions")

    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            1 -> viewModel.loadPreMixList()
            3 -> viewModel.loadExceptions()
        }
    }

    AppScaffold(
        title = "Dashboard",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = GraphiteSurface,
                contentColor = AmberPrimary,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = AmberPrimary,
                        height = 3.dp
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selectedTab == index) AmberPrimary else TextMuted
                            )
                        }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                when (selectedTab) {
                    0 -> PalletTab(
                        tagInput = state.palletTagInput,
                        result = state.palletLocation,
                        isLoading = state.isLoading,
                        onTagChange = viewModel::setPalletTagInput,
                        onLookup = viewModel::lookupPallet
                    )
                    1 -> JsonTab(json = state.preMixList, isLoading = state.isLoading, emptyMessage = "No Pre-Mix data")
                    2 -> PlaceholderTab("No allocation history available")
                    3 -> JsonTab(json = state.exceptions, isLoading = state.isLoading, emptyMessage = "No exceptions", isError = true)
                }
                state.error?.let { err ->
                    Snackbar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        containerColor = DangerRed
                    ) { Text(err, color = TextPrimary) }
                }
            }
        }
    }
}

@Composable
private fun PalletTab(
    tagInput: String,
    result: String,
    isLoading: Boolean,
    onTagChange: (String) -> Unit,
    onLookup: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = tagInput,
            onValueChange = onTagChange,
            label = { Text("Tag ID") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AmberPrimary,
                focusedLabelColor = AmberPrimary,
                cursorColor = AmberPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onLookup,
            enabled = tagInput.isNotBlank() && !isLoading,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
            else Text("Look Up")
        }
        if (result.isNotBlank()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = GraphiteSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    LabelValueRow("Result", result)
                }
            }
        }
    }
}

@Composable
private fun JsonTab(json: String, isLoading: Boolean, emptyMessage: String, isError: Boolean = false) {
    when {
        isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AmberPrimary)
        }
        json.isBlank() -> PlaceholderTab(emptyMessage)
        else -> ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (isError) DangerRed.copy(alpha = 0.1f) else GraphiteSurface
            )
        ) {
            Text(
                text = json,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) DangerRed else TextPrimary,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
private fun PlaceholderTab(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
    }
}
