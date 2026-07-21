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
    onStartMixing: (collectionId: String) -> Unit,
    onRfidLookup: () -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: MixingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val upgradeRequired by viewModel.upgradeRequired.collectAsState()
    var showCancelDialog by rememberSaveable { mutableStateOf(false) }
    var showBackConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showApprovalDialog by rememberSaveable { mutableStateOf(false) }
    var managerUsername by remember { mutableStateOf("") }
    var managerPassword by remember { mutableStateOf("") }
    var selectedBagFraction by rememberSaveable { mutableStateOf(0.0) }
    var bagCountText by rememberSaveable { mutableStateOf("1") }
    var quantityText by rememberSaveable { mutableStateOf("") }
    var exceptionUsername by remember { mutableStateOf("") }
    var exceptionPassword by remember { mutableStateOf("") }
    var exceptionAuditReason by remember { mutableStateOf("") }
    var waiverShortBagCountText by rememberSaveable { mutableStateOf("") }
    var waiverUsername by remember { mutableStateOf("") }
    var waiverPassword by remember { mutableStateOf("") }
    var waiverAuditReason by remember { mutableStateOf("") }
    var rejectedWaiverUsername by remember { mutableStateOf("") }
    var rejectedWaiverPassword by remember { mutableStateOf("") }
    var rejectedWaiverAuditReason by remember { mutableStateOf("") }

    val allIngredientsSatisfied = (uiState as? MixingUiState.OrderLoaded)?.order?.lines?.all { bomLine ->
        bomLine.isSatisfied
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

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == MixingNavDestination.MIXING_BOARD) {
                (viewModel.uiState.value as? MixingUiState.OrderLoaded)
                    ?.order?.collectionId?.takeIf { it.isNotBlank() }
                    ?.let(onStartMixing)
            }
        }
    }

    val isCancelling = uiState is MixingUiState.Cancelling

    if (upgradeRequired) {
        AlertDialog(
            onDismissRequest = { /* blocking: only a new build clears this */ },
            title = { Text("App update required", color = TextPrimary) },
            text = {
                Text(
                    "Station 2 requires the 4.0 reader build for this workflow. " +
                        "Install the update, then log in again.",
                    color = TextMuted
                )
            },
            confirmButton = {},
            containerColor = GraphiteSurface
        )
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { if (!isCancelling) showCancelDialog = false },
            title = { Text("Cancel this job card?", color = TextPrimary) },
            text = {
                Text(
                    "This closes the job card if it hasn't had any activity yet (ingredients scanned, mixing started, etc). You'll be notified if it can't be cancelled.",
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isCancelling,
                    onClick = {
                        // Every cancel needs manager credentials in v3 — there is no direct path.
                        showCancelDialog = false
                        showApprovalDialog = true
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
                        "Cancelling a job card always needs a manager's approval. Ask a manager to enter their credentials — this is recorded against their name in the audit trail.",
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

    if (uiState is MixingUiState.EnteringQuantityDetails) {
        val palletTag = (uiState as MixingUiState.EnteringQuantityDetails).palletTag
        AlertDialog(
            onDismissRequest = { viewModel.cancelQuantityEntry() },
            title = { Text("Weight received", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Pallet: $palletTag", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    Text("Bulk material — enter the exact weight received.", color = TextMuted,
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        label = { Text("Quantity (kg)") },
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
                    enabled = quantityText.toDoubleOrNull()?.let { it > 0.0 } == true,
                    onClick = {
                        val qty = quantityText.toDoubleOrNull() ?: return@TextButton
                        viewModel.confirmQuantityScan(palletTag, qty)
                        quantityText = ""
                    }
                ) { Text("Confirm Weight", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelQuantityEntry() }) { Text("Cancel", color = TextPrimary) }
            },
            containerColor = GraphiteSurface
        )
    }

    if (uiState is MixingUiState.IngredientExceptionApproval) {
        val exceptionState = uiState as MixingUiState.IngredientExceptionApproval
        AlertDialog(
            onDismissRequest = {
                viewModel.cancelManagerApproval()
                exceptionUsername = ""
                exceptionPassword = ""
                exceptionAuditReason = ""
            },
            title = { Text("Manager or admin approval required", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(exceptionState.reason, color = TextMuted)
                    exceptionState.validationError?.let { validationError ->
                        Text(validationError, color = DangerRed, style = MaterialTheme.typography.labelMedium)
                    }
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
                    OutlinedTextField(
                        value = exceptionAuditReason,
                        onValueChange = { exceptionAuditReason = it },
                        label = { Text("Audit reason") },
                        singleLine = true,
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
                    enabled = exceptionUsername.isNotBlank() && exceptionPassword.isNotBlank() && exceptionAuditReason.isNotBlank(),
                    onClick = {
                        viewModel.submitManagerApproval(exceptionUsername, exceptionPassword, exceptionAuditReason)
                        exceptionUsername = ""
                        exceptionPassword = ""
                        exceptionAuditReason = ""
                    }
                ) { Text("Approve", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.cancelManagerApproval()
                        exceptionUsername = ""
                        exceptionPassword = ""
                        exceptionAuditReason = ""
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

    // First-attempt short-bag waiver entry: ViewModel state (SP3 gap 2), opened from the "Short
    // bags" button on a line. Credentials travel on this first submission (unlike a scan there is
    // no preceding attempt to reject first) — see MixingViewModel.submitShortBagWaiver.
    if (uiState is MixingUiState.ShortBagWaiverEntry) {
        val waiverMaterialCode = (uiState as MixingUiState.ShortBagWaiverEntry).requestedMaterialCode
        AlertDialog(
            onDismissRequest = {
                viewModel.dismissShortBagWaiverEntry()
                waiverShortBagCountText = ""
                waiverUsername = ""
                waiverPassword = ""
                waiverAuditReason = ""
            },
            title = { Text("Waive short bags", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Material: $waiverMaterialCode", color = TextMuted)
                    OutlinedTextField(
                        value = waiverShortBagCountText,
                        onValueChange = { waiverShortBagCountText = it },
                        label = { Text("Short bag count") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = waiverUsername,
                        onValueChange = { waiverUsername = it },
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
                        value = waiverPassword,
                        onValueChange = { waiverPassword = it },
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
                    OutlinedTextField(
                        value = waiverAuditReason,
                        onValueChange = { waiverAuditReason = it },
                        label = { Text("Audit reason") },
                        singleLine = true,
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
                    enabled = waiverShortBagCountText.toDoubleOrNull()?.let { it > 0.0 } == true &&
                        waiverUsername.isNotBlank() && waiverPassword.isNotBlank() && waiverAuditReason.isNotBlank(),
                    onClick = {
                        val count = waiverShortBagCountText.toDoubleOrNull() ?: return@TextButton
                        viewModel.submitShortBagWaiver(waiverMaterialCode, count, waiverUsername, waiverPassword, waiverAuditReason)
                        waiverShortBagCountText = ""
                        waiverUsername = ""
                        waiverPassword = ""
                        waiverAuditReason = ""
                    }
                ) { Text("Submit", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissShortBagWaiverEntry()
                        waiverShortBagCountText = ""
                        waiverUsername = ""
                        waiverPassword = ""
                        waiverAuditReason = ""
                    }
                ) { Text("Cancel", color = TextPrimary) }
            },
            containerColor = GraphiteSurface
        )
    }

    if (uiState is MixingUiState.ShortBagWaiverNeedsApproval) {
        val waiverState = uiState as MixingUiState.ShortBagWaiverNeedsApproval
        AlertDialog(
            onDismissRequest = {
                viewModel.cancelShortBagWaiver()
                rejectedWaiverUsername = ""
                rejectedWaiverPassword = ""
                rejectedWaiverAuditReason = ""
            },
            title = { Text("Manager or admin approval required", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Material: ${waiverState.requestedMaterialCode}", color = TextMuted)
                    Text("Short by %.2f bags".format(waiverState.shortBagCount), color = TextMuted)
                    Text(waiverState.reason, color = TextMuted)
                    OutlinedTextField(
                        value = rejectedWaiverUsername,
                        onValueChange = { rejectedWaiverUsername = it },
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
                        value = rejectedWaiverPassword,
                        onValueChange = { rejectedWaiverPassword = it },
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
                    OutlinedTextField(
                        value = rejectedWaiverAuditReason,
                        onValueChange = { rejectedWaiverAuditReason = it },
                        label = { Text("Audit reason") },
                        singleLine = true,
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
                    enabled = rejectedWaiverUsername.isNotBlank() && rejectedWaiverPassword.isNotBlank() && rejectedWaiverAuditReason.isNotBlank(),
                    onClick = {
                        viewModel.submitShortBagWaiver(
                            waiverState.requestedMaterialCode,
                            waiverState.shortBagCount,
                            rejectedWaiverUsername,
                            rejectedWaiverPassword,
                            rejectedWaiverAuditReason
                        )
                        rejectedWaiverUsername = ""
                        rejectedWaiverPassword = ""
                        rejectedWaiverAuditReason = ""
                    }
                ) { Text("Approve", color = AmberPrimary) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.cancelShortBagWaiver()
                        rejectedWaiverUsername = ""
                        rejectedWaiverPassword = ""
                        rejectedWaiverAuditReason = ""
                    }
                ) { Text("Cancel", color = TextPrimary) }
            },
            containerColor = GraphiteSurface
        )
    }

    AppScaffold(
        title = "Scan Ingredients",
        status = connectionStatus,
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
                        Column(Modifier.weight(1f).fillMaxWidth()) {
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
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.dismissError() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Dismiss") }
                        }
                    }
                    is MixingUiState.OrderLoaded -> {
                        val order = state.order
                        val satisfiedCount = order.lines.count { bomLine -> bomLine.isSatisfied }
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
                                if (order.summary.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        order.summary,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextMuted
                                    )
                                }
                                if (!allSatisfied && state.selectedLineNumber == null) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Tap a line below to arm it before scanning a pallet.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AmberPrimary
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(order.lines, key = { it.lineNumber }) { bomLine ->
                                val satisfied = bomLine.isSatisfied
                                val armed = state.selectedLineNumber == bomLine.lineNumber
                                val fraction = if (bomLine.requiredQty > 0.0) {
                                    (bomLine.collectedQty / bomLine.requiredQty).toFloat().coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                val displayName = bomLine.itemName.ifBlank { bomLine.itemCode }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.selectLine(bomLine.lineNumber) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = when {
                                            satisfied -> SuccessGreen.copy(alpha = 0.10f)
                                            armed -> AmberPrimary.copy(alpha = 0.10f)
                                            else -> GraphiteSurface
                                        }
                                    ),
                                    border = BorderStroke(
                                        if (armed) 2.dp else 1.dp,
                                        when {
                                            satisfied -> SuccessGreen.copy(alpha = 0.30f)
                                            armed -> AmberPrimary
                                            else -> GraphiteBorder
                                        }
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = "Line ${bomLine.lineNumber}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = TextMuted
                                                    )
                                                    if (armed) {
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(
                                                            text = "ARMED",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = AmberPrimary
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = displayName,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = TextPrimary
                                                )
                                            }
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
                                                text = if (bomLine.isSatisfied) {
                                                    "Fully Allocated"
                                                } else {
                                                    "%.2f %s".format(bomLine.remainingQty, bomLine.uom)
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (satisfied) SuccessGreen else TextMuted
                                            )
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = buildString {
                                                append("Available: %.2f %s".format(bomLine.availableQty, bomLine.uom))
                                                if (bomLine.isBagged) {
                                                    append(" · Bag size: ${bomLine.bagSize}")
                                                }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                        if (!bomLine.isSatisfied) {
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
                                        // Every element below is gated on isBagged: a bulk line has no
                                        // bag arithmetic (its bag fields are null, not zero) and must
                                        // never render bag figures or be treated as bag-incomplete.
                                        if (bomLine.isBagged) {
                                            val expectedBags = bomLine.expectedBags ?: 0.0
                                            val scannedBags = bomLine.scannedBags ?: 0.0
                                            val bagFraction = if (expectedBags > 0.0) {
                                                (scannedBags / expectedBags).toFloat().coerceIn(0f, 1f)
                                            } else {
                                                0f
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = "%.2f / %.2f full bags".format(scannedBags, expectedBags),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextMuted
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            LinearProgressIndicator(
                                                progress = { bagFraction },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp)),
                                                color = if (satisfied) SuccessGreen else AmberPrimary,
                                                trackColor = GraphiteBorder
                                            )
                                            if (!satisfied) {
                                                Spacer(Modifier.height(4.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.End
                                                ) {
                                                    TextButton(
                                                        onClick = { viewModel.openShortBagWaiver(bomLine.itemCode) }
                                                    ) { Text("Short bags", color = AmberPrimary) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> Spacer(Modifier.weight(1f))
                }

                val readyForMixing = (uiState as? MixingUiState.OrderLoaded)
                    ?.order?.collectionStatus == "ReadyForMixing"
                if ((readyForMixing || allIngredientsSatisfied) && uiState is MixingUiState.OrderLoaded) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Collection complete — ready for mixing.",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMuted
                    )
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
                        onClick = {
                            (uiState as? MixingUiState.OrderLoaded)
                                ?.order?.collectionId?.takeIf { it.isNotBlank() }
                                ?.let(onStartMixing)
                        },
                        enabled = readyForMixing,
                        modifier = Modifier.weight(2f).height(56.dp)
                    ) {
                        Text(if (readyForMixing) "Start Mixing" else "Mixing after collection")
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
