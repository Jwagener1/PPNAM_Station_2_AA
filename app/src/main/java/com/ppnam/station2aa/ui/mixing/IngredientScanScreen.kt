package com.ppnam.station2aa.ui.mixing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.theme.*

@Composable
fun IngredientScanScreen(
    orderNo: String,
    onProceedToHopperScan: () -> Unit,
    onRfidLookup: () -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var showCancelDialog by rememberSaveable { mutableStateOf(false) }
    var showBackConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showApprovalDialog by rememberSaveable { mutableStateOf(false) }
    var managerUsername by remember { mutableStateOf("") }
    var managerPassword by remember { mutableStateOf("") }
    var selectedBagFraction by rememberSaveable { mutableStateOf(0.0) }
    var bagCountText by rememberSaveable { mutableStateOf("1") }
    var exceptionUsername by remember { mutableStateOf("") }
    var exceptionPassword by remember { mutableStateOf("") }

    val allIngredientsSatisfied = (uiState as? MixingUiState.OrderLoaded)?.order?.lines?.all { bomLine ->
        bomLine.isBagFullyAllocated
    } ?: false

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.supervisorError.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.cancelOutcome.collect { outcome ->
            when (outcome) {
                is CancelOutcome.Confirmed -> onBack()
                is CancelOutcome.Failed -> {
                    managerUsername = ""
                    managerPassword = ""
                    snackbarHostState.showSnackbar(outcome.reason)
                }
            }
        }
    }

    LaunchedEffect(orderNo) { viewModel.startListeningForPalletScans(orderNo) }

    val isCancelling = uiState is MixingUiState.Cancelling

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { if (!isCancelling) showCancelDialog = false },
            title = { Text("Cancel this job card?", color = TextPrimary) },
            text = {
                Text(
                    "This closes the job card if it hasn't had any activity yet (ingredients scanned, hopper assigned, SAP issue, etc). You'll be notified if it can't be cancelled.",
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isCancelling,
                    onClick = {
                        if (viewModel.operatorCanCancelDirectly()) {
                            showCancelDialog = false
                            viewModel.cancelJob()
                        } else {
                            showCancelDialog = false
                            showApprovalDialog = true
                        }
                    }
                ) { Text("Cancel Job", color = DangerRed) }
            },
            dismissButton = {
                TextButton(enabled = !isCancelling, onClick = { showCancelDialog = false }) { Text("Keep Scanning") }
            },
            containerColor = GraphiteSurface
        )
    }

    if (showBackConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBackConfirmDialog = false },
            title = { Text("Go back?", color = TextPrimary) },
            text = { Text("You'll leave the ingredient scanning screen. Your progress is saved.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showBackConfirmDialog = false
                    onBack()
                }) { Text("Go Back", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { showBackConfirmDialog = false }) { Text("Stay") }
            },
            containerColor = GraphiteSurface
        )
    }

    if (showApprovalDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isCancelling) {
                    showApprovalDialog = false
                    managerUsername = ""
                    managerPassword = ""
                }
            },
            title = { Text("Manager or admin approval required", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Your role can't cancel a job card directly. Ask a manager or admin to enter their credentials to approve this cancellation.",
                        color = TextMuted
                    )
                    OutlinedTextField(
                        value = managerUsername,
                        onValueChange = { managerUsername = it },
                        label = { Text("Manager/Admin Username") },
                        singleLine = true,
                        enabled = !isCancelling,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = managerPassword,
                        onValueChange = { managerPassword = it },
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !isCancelling,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isCancelling && managerUsername.isNotBlank() && managerPassword.isNotBlank(),
                    onClick = { viewModel.cancelJob(managerUsername, managerPassword) }
                ) {
                    if (isCancelling) CircularProgressIndicator(Modifier.size(16.dp), color = AmberPrimary)
                    else Text("Confirm Cancel", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isCancelling,
                    onClick = {
                        showApprovalDialog = false
                        managerUsername = ""
                        managerPassword = ""
                    }
                ) { Text("Back") }
            },
            containerColor = GraphiteSurface
        )
    }

    val bagFractionOptions = listOf("0" to 0.0, "1/4" to 0.25, "1/2" to 0.5, "3/4" to 0.75)

    if (uiState is MixingUiState.EnteringBagDetails) {
        val palletTag = (uiState as MixingUiState.EnteringBagDetails).palletTag
        AlertDialog(
            onDismissRequest = { viewModel.cancelBagEntry() },
            title = { Text("Bag size & count", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Pallet: $palletTag", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        bagFractionOptions.forEach { (label, value) ->
                            val selected = selectedBagFraction == value
                            Text(
                                text = label,
                                color = if (selected) GraphiteSurface else TextMuted,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (selected) AmberPrimary else GraphiteSurfaceVariant)
                                    .clickable { selectedBagFraction = value }
                                    .padding(vertical = 10.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                    OutlinedTextField(
                        value = bagCountText,
                        onValueChange = { bagCountText = it },
                        label = { Text("Bag count") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = bagCountText.toDoubleOrNull()?.let { it > 0.0 } == true,
                    onClick = {
                        val count = bagCountText.toDoubleOrNull() ?: return@TextButton
                        viewModel.confirmIngredientScan(palletTag, "full", count + selectedBagFraction)
                        bagCountText = "1"
                        selectedBagFraction = 0.0
                    }
                ) { Text("Confirm Scan", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelBagEntry() }) { Text("Cancel", color = TextPrimary) }
            },
            containerColor = GraphiteSurface
        )
    }

    if (uiState is MixingUiState.IngredientExceptionApproval) {
        val exceptionReason = (uiState as MixingUiState.IngredientExceptionApproval).reason
        AlertDialog(
            onDismissRequest = {
                viewModel.cancelManagerApproval()
                exceptionUsername = ""
                exceptionPassword = ""
            },
            title = { Text("Manager or admin approval required", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(exceptionReason, color = TextMuted)
                    OutlinedTextField(
                        value = exceptionUsername,
                        onValueChange = { exceptionUsername = it },
                        label = { Text("Manager/Admin Username") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = exceptionPassword,
                        onValueChange = { exceptionPassword = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = exceptionUsername.isNotBlank() && exceptionPassword.isNotBlank(),
                    onClick = {
                        viewModel.submitManagerApproval(exceptionUsername, exceptionPassword)
                        exceptionUsername = ""
                        exceptionPassword = ""
                    }
                ) { Text("Approve", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.cancelManagerApproval()
                        exceptionUsername = ""
                        exceptionPassword = ""
                    }
                ) { Text("Cancel", color = TextPrimary) }
            },
            containerColor = GraphiteSurface
        )
    }

    if (uiState is MixingUiState.PalletRecoveryPrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPalletRecovery() },
            title = { Text("Pallet not in Holding", color = TextPrimary) },
            text = { Text("This pallet isn't currently in Holding or Mixing. Recover it into Holding?", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPalletRecovery() }) { Text("Recover", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPalletRecovery() }) { Text("No", color = TextPrimary) }
            },
            containerColor = GraphiteSurface
        )
    }

    AppScaffold(
        title = "Scan Ingredients",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = { showBackConfirmDialog = true },
        onRfidLookup = onRfidLookup
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                when (val state = uiState) {
                    is MixingUiState.Loading -> {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AmberPrimary)
                        }
                    }
                    is MixingUiState.Error -> {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.12f)),
                                border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    state.message,
                                    color = DangerRed,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                    is MixingUiState.OrderLoaded -> {
                        val order = state.order
                        val satisfiedCount = order.lines.count { bomLine -> bomLine.isBagFullyAllocated }
                        val allSatisfied = satisfiedCount == order.lines.size

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (allSatisfied) SuccessGreen.copy(alpha = 0.12f) else GraphiteSurfaceVariant
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (allSatisfied) SuccessGreen.copy(alpha = 0.35f) else GraphiteBorder
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Order $orderNo", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                                order.productBeingMade?.let { productName ->
                                    Spacer(Modifier.height(2.dp))
                                    Text(productName, style = MaterialTheme.typography.bodyMedium, color = AmberPrimary)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "$satisfiedCount of ${order.lines.size} lines satisfied",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (allSatisfied) SuccessGreen else TextMuted
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(order.lines) { bomLine ->
                                val satisfied = bomLine.isBagFullyAllocated
                                val fraction = if (bomLine.requiredQty > 0.0) {
                                    (bomLine.scannedQty / bomLine.requiredQty).toFloat().coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                val displayName = bomLine.itemName.ifBlank { bomLine.itemCode }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (satisfied) SuccessGreen.copy(alpha = 0.10f) else GraphiteSurface
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (satisfied) SuccessGreen.copy(alpha = 0.30f) else GraphiteBorder
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = displayName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = TextPrimary,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (satisfied) {
                                                Icon(
                                                    imageVector = Icons.Filled.CheckCircle,
                                                    contentDescription = "Satisfied",
                                                    tint = SuccessGreen,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Text(
                                                text = if (bomLine.isBagFullyAllocated) {
                                                    "Fully Allocated"
                                                } else {
                                                    "%.2f %s".format(bomLine.remainingQty, bomLine.uom)
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (satisfied) SuccessGreen else TextMuted
                                            )
                                        }
                                        if (!bomLine.isBagFullyAllocated) {
                                            Spacer(Modifier.height(8.dp))
                                            LinearProgressIndicator(
                                                progress = { fraction },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp)),
                                                color = if (satisfied) SuccessGreen else AmberPrimary,
                                                trackColor = GraphiteBorder
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> Spacer(Modifier.weight(1f))
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { showCancelDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onProceedToHopperScan,
                        enabled = allIngredientsSatisfied && uiState is MixingUiState.OrderLoaded,
                        modifier = Modifier.weight(2f).height(56.dp)
                    ) {
                        Text("Proceed to Hopper Scan")
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
