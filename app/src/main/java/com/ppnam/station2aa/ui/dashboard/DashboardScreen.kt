package com.ppnam.station2aa.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Pallet", "Pre-Mix", "Allocation", "Exceptions")

    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            1 -> viewModel.loadPreMixList()
            3 -> viewModel.loadExceptions()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            when (selectedTab) {
                0 -> PalletLocationTab(
                    tagInput = state.palletTagInput,
                    result = state.palletLocation,
                    onTagChange = viewModel::setPalletTagInput,
                    onLookup = viewModel::lookupPallet
                )
                1 -> SimpleJsonTab(label = "Pre-Mix List", json = state.preMixList, isLoading = state.isLoading)
                2 -> SimpleJsonTab(label = "Allocation History", json = "", isLoading = false)
                3 -> SimpleJsonTab(label = "Exceptions", json = state.exceptions, isLoading = state.isLoading)
            }
            state.error?.let {
                Snackbar(modifier = Modifier.align(Alignment.BottomCenter)) {
                    Text(it)
                }
            }
        }
    }
}

@Composable
private fun PalletLocationTab(
    tagInput: String,
    result: String,
    onTagChange: (String) -> Unit,
    onLookup: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = tagInput,
            onValueChange = onTagChange,
            label = { Text("Tag ID") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = onLookup, modifier = Modifier.fillMaxWidth()) { Text("Look Up") }
        if (result.isNotBlank()) Text(result)
    }
}

@Composable
private fun SimpleJsonTab(label: String, json: String, isLoading: Boolean) {
    Column {
        if (isLoading) CircularProgressIndicator()
        else if (json.isBlank()) Text("No data")
        else Text(json)
    }
}
