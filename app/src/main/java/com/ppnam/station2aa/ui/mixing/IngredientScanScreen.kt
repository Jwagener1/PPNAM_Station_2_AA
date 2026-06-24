package com.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

    val errorMessage = if (uiState is MixingUiState.Error) (uiState as MixingUiState.Error).message else null

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Scan Ingredients", style = MaterialTheme.typography.headlineSmall)
        Text("Order: $orderNo", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(scannedIngredients) { ingredient ->
                ListItem(
                    headlineContent = { Text(ingredient.itemCode) },
                    supportingContent = { Text("Tag: ${ingredient.tagId}") }
                )
                HorizontalDivider()
            }
        }
        errorMessage?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error)
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
