package com.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.theme.AmberPrimary
import com.ppnam.station2aa.ui.theme.DangerRed

@Composable
fun JobLookupScreen(
    onJobFound: (orderNo: String) -> Unit,
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var orderInput by remember { mutableStateOf("") }

    LaunchedEffect(uiState) {
        if (uiState is MixingUiState.OrderLoaded) {
            onJobFound((uiState as MixingUiState.OrderLoaded).order.docNo)
        }
    }

    val isLoading = uiState is MixingUiState.Loading
    val errorMessage = if (uiState is MixingUiState.Error) (uiState as MixingUiState.Error).message else null

    AppScaffold(
        title = "Job Lookup",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
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
