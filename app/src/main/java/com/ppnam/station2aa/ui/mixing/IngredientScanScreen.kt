package com.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun IngredientScanScreen(
    orderNo: String,
    onProceedToMixerCode: () -> Unit,
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scannedIngredients by viewModel.scannedIngredients.collectAsState()

    LaunchedEffect(orderNo) {
        viewModel.startListeningForScans(orderNo)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Scan Ingredients", style = MaterialTheme.typography.headlineSmall)
        Text("Order: $orderNo", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        when (val state = uiState) {
            is MixingUiState.Loading -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is MixingUiState.Error -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is MixingUiState.OrderLoaded -> {
                val order = state.order
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(order.lines) { bomLine ->
                        val scannedCount = scannedIngredients.count { it.itemCode == bomLine.itemCode }
                        val required = bomLine.requiredQty.toInt()
                        val satisfied = scannedCount >= required
                        val displayName = if (bomLine.itemName.isNotBlank()) bomLine.itemName else bomLine.itemCode
                        val rowBackground = if (satisfied) Color(0xFFB8F5B0) else Color.Transparent
                        ListItem(
                            modifier = Modifier.background(rowBackground),
                            headlineContent = { Text(displayName) },
                            trailingContent = { Text("$scannedCount / $required") }
                        )
                        HorizontalDivider()
                    }
                }
            }
            else -> {
                // Idle — nothing loaded yet; occupy space
                Spacer(Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onProceedToMixerCode,
            enabled = scannedIngredients.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Proceed to Mixer Code")
        }
    }
}
