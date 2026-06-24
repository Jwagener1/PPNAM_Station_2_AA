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
fun PreMixCompleteScreen(
    orderNo: String,
    onCompleted: () -> Unit,
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scannedIngredients by viewModel.scannedIngredients.collectAsState()
    val mixerCode by viewModel.mixerCode.collectAsState()

    // Use the navigationEvent channel instead of keying off uiState to avoid
    // re-triggering when the user back-navigates to this screen.
    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == MixingNavDestination.PREMIX_COMPLETE) {
                onCompleted()
            }
        }
    }

    val isLoading = uiState is MixingUiState.Loading
    val errorMessage = if (uiState is MixingUiState.Error) (uiState as MixingUiState.Error).message else null

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Review Pre-Mix", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Order: $orderNo · Mixer: $mixerCode",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(scannedIngredients) { ingredient ->
                ListItem(
                    headlineContent = { Text(ingredient.itemCode) },
                    supportingContent = { Text("Qty: ${ingredient.qty}") }
                )
                HorizontalDivider()
            }
        }
        errorMessage?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { viewModel.completePremix(orderNo) },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) CircularProgressIndicator(Modifier.size(20.dp))
            else Text("Confirm & Complete")
        }
    }
}
