package com.ppnam.station2aa.ui.mixing.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ppnam.station2aa.domain.model.ActiveCycle
import com.ppnam.station2aa.domain.model.Equipment
import com.ppnam.station2aa.domain.model.MixingArea
import com.ppnam.station2aa.ui.components.AppScaffold
import com.ppnam.station2aa.ui.theme.AmberPrimary
import com.ppnam.station2aa.ui.theme.DangerRed
import com.ppnam.station2aa.ui.theme.GraphiteBorder
import com.ppnam.station2aa.ui.theme.GraphiteSurface
import com.ppnam.station2aa.ui.theme.SuccessGreen
import com.ppnam.station2aa.ui.theme.TextMuted
import com.ppnam.station2aa.ui.theme.TextPrimary
import com.ppnam.station2aa.ui.theme.WarningOrange

@Composable
fun MixingBoardScreen(
    area: MixingArea,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: MixingBoardViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val session by viewModel.session.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(area) { viewModel.openArea(area) }
    LaunchedEffect(Unit) { viewModel.logoutEvent.collect { onLogout() } }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val board = uiState as? MixingBoardUiState.Board

    // --- Dialogs (one at a time, owned by the ViewModel's sheet state) ---
    when (val sheet = board?.sheet) {
        is BoardSheet.StartConfirm -> StartConfirmDialog(sheet, board.selection, viewModel)
        is BoardSheet.CycleSheet -> CycleSheetDialog(sheet, viewModel)
        is BoardSheet.ForceCloseDialog -> ForceCloseDialog(sheet, viewModel)
        else -> Unit
    }

    AppScaffold(
        title = area.display,
        status = connectionStatus,
        onBack = onBack,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onLogout = viewModel::logout,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                is MixingBoardUiState.Loading -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = AmberPrimary) }

                is MixingBoardUiState.Error -> Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.message, color = DangerRed)
                    Button(onClick = { viewModel.openArea(area) }) { Text("Retry") }
                }

                is MixingBoardUiState.Board -> BoardContent(state, viewModel)

                else -> Unit
            }

            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun BoardContent(board: MixingBoardUiState.Board, viewModel: MixingBoardViewModel) {
    Column(Modifier.fillMaxSize()) {
        if (board.busy) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth(), color = AmberPrimary, trackColor = GraphiteBorder)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (val sel = board.selection) {
                    is BoardSelection.None -> "Select a collection or mix, then scan a machine"
                    is BoardSelection.Collection -> "Selected: ${sel.collectionId}"
                    is BoardSelection.Mixes -> "Selected: ${sel.mixBatchIds.joinToString()}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (board.selection is BoardSelection.None) TextMuted else AmberPrimary,
                modifier = Modifier.weight(1f),
            )
            if (board.selection !is BoardSelection.None) {
                TextButton(onClick = viewModel::clearSelection) { Text("Clear", color = TextPrimary) }
            }
            TextButton(onClick = viewModel::refresh, enabled = !board.busy) {
                Text("Refresh", color = TextPrimary)
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (board.readyCollections.isNotEmpty()) {
                item { SectionHeader("Collections ready to mix") }
                items(board.readyCollections, key = { it.collectionId }) { collection ->
                    val selected = (board.selection as? BoardSelection.Collection)
                        ?.collectionId == collection.collectionId
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !board.busy) { viewModel.selectCollection(collection.collectionId) },
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, if (selected) AmberPrimary else GraphiteBorder),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${collection.collectionId} · JC ${collection.jobCardNumber}",
                                style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            if (collection.productName.isNotBlank()) {
                                Text(collection.productName,
                                    style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            }
                        }
                    }
                }
            }

            if (board.overview.readyMixes.isNotEmpty()) {
                item { SectionHeader("Ready mixes") }
                items(board.overview.readyMixes, key = { it.mixBatchId }) { mix ->
                    val mixesSelection = board.selection as? BoardSelection.Mixes
                    val selected = mixesSelection?.mixBatchIds?.contains(mix.mixBatchId) == true
                    // Same-JC rule: once a mix is selected, other JCs grey out.
                    val selectable = mixesSelection == null || mixesSelection.jobCardNumber == mix.jobCardNumber
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = selectable && !board.busy) { viewModel.toggleMix(mix.mixBatchId) },
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, if (selected) AmberPrimary else GraphiteBorder),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${mix.mixBatchId} · JC ${mix.jobCardNumber}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selectable) TextPrimary else TextMuted)
                            Text("From ${mix.mixerDisplayName}",
                                style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            // Destinations render ONLY from validNextMachineCodes (§13.8).
                            Text("Next: ${mix.validNextMachineCodes.joinToString()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selectable) SuccessGreen else TextMuted)
                        }
                    }
                }
            }

            item { SectionHeader("Machines") }
            items(board.overview.equipment.chunked(2)) { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { machine ->
                        MachineCard(
                            machine = machine,
                            highlighted = machine.machineCode in board.highlightedMachineCodes,
                            hasCycle = board.overview.activeCycles.any { it.machineCode == machine.machineCode },
                            noSelection = board.selection is BoardSelection.None,
                            busy = board.busy,
                            onChosen = viewModel::machineChosen,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (board.overview.activeCycles.isNotEmpty()) {
                item { SectionHeader("Active cycles") }
                items(board.overview.activeCycles, key = { it.cycleId }) { cycle ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !board.busy && board.selection is BoardSelection.None) {
                                viewModel.machineChosen(cycle.machineCode)
                            },
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, GraphiteBorder),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${cycle.cycleId} on ${cycle.machineCode}",
                                style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            Text("JC ${cycle.jobCardNumber} · started ${cycle.startedAtUtc}",
                                style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = TextMuted,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun MachineCard(
    machine: Equipment,
    highlighted: Boolean,
    hasCycle: Boolean,
    noSelection: Boolean,
    busy: Boolean,
    onChosen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = when (machine.status) {
        "Available" -> SuccessGreen
        "InUse" -> WarningOrange
        else -> DangerRed
    }
    // Taps work on highlighted machines (start) or, with no selection, on busy
    // machines (cycle sheet). A SCAN reaches any machine via the ViewModel.
    val clickable = !busy && (highlighted || (noSelection && hasCycle))
    Card(
        modifier = modifier.clickable(enabled = clickable) { onChosen(machine.machineCode) },
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        border = BorderStroke(if (highlighted) 2.dp else 1.dp,
            if (highlighted) AmberPrimary else GraphiteBorder),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(machine.displayName, style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary, maxLines = 1)
            Text(machine.machineCode, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Spacer(Modifier.height(4.dp))
            // Rendered verbatim from areaStatus.equipment — never inferred locally (§13.7).
            Text(machine.status, style = MaterialTheme.typography.labelSmall, color = statusColor)
        }
    }
}

@Composable
private fun StartConfirmDialog(
    sheet: BoardSheet.StartConfirm,
    selection: BoardSelection,
    viewModel: MixingBoardViewModel,
) {
    AlertDialog(
        onDismissRequest = viewModel::dismissSheet,
        title = { Text("Start ${sheet.machine.displayName}", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when (selection) {
                        is BoardSelection.Collection -> "Collection ${selection.collectionId} · JC ${selection.jobCardNumber}"
                        is BoardSelection.Mixes -> "Mixes ${selection.mixBatchIds.joinToString()} · JC ${selection.jobCardNumber}"
                        is BoardSelection.None -> ""
                    },
                    color = TextMuted,
                )
                sheet.doseRows?.forEach { row ->
                    OutlinedTextField(
                        value = row.doseText,
                        onValueChange = { viewModel.updateDose(row.materialCode, it) },
                        label = { Text("${row.materialName} (≤ %.2f kg)".format(row.collectedQty)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                sheet.validationError?.let {
                    Text(it, color = DangerRed, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmStart) { Text("Start", color = AmberPrimary) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissSheet) { Text("Cancel", color = TextPrimary) }
        },
        containerColor = GraphiteSurface,
    )
}

@Composable
private fun CycleSheetDialog(sheet: BoardSheet.CycleSheet, viewModel: MixingBoardViewModel) {
    val cycle: ActiveCycle = sheet.cycle
    AlertDialog(
        onDismissRequest = viewModel::dismissSheet,
        title = { Text("Active cycle on ${sheet.machine.displayName}", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Cycle: ${cycle.cycleId}", color = TextPrimary)
                Text("Job card: ${cycle.jobCardNumber}", color = TextMuted)
                if (cycle.mixBatchIds.isNotEmpty()) {
                    Text("Mixes: ${cycle.mixBatchIds.joinToString()}", color = TextMuted)
                }
                Text("Started ${cycle.startedAtUtc} by ${cycle.startedByOperatorId}", color = TextMuted,
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = viewModel::openForceClose) {
                    Text("Force close…", color = DangerRed)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::finishCycle) { Text("Finish cycle", color = AmberPrimary) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissSheet) { Text("Cancel", color = TextPrimary) }
        },
        containerColor = GraphiteSurface,
    )
}

@Composable
private fun ForceCloseDialog(sheet: BoardSheet.ForceCloseDialog, viewModel: MixingBoardViewModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var auditReason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = viewModel::dismissSheet,
        title = { Text("Force close ${sheet.cycle.cycleId}", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Requires Manager/Admin approval; the cycle is released without completing.",
                    color = TextMuted)
                sheet.validationError?.let {
                    Text(it, color = DangerRed, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Manager/Admin Username") }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AmberPrimary, focusedLabelColor = AmberPrimary,
                        cursorColor = AmberPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AmberPrimary, focusedLabelColor = AmberPrimary,
                        cursorColor = AmberPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = auditReason, onValueChange = { auditReason = it },
                    label = { Text("Audit reason") }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AmberPrimary, focusedLabelColor = AmberPrimary,
                        cursorColor = AmberPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && password.isNotBlank() && auditReason.isNotBlank(),
                onClick = { viewModel.submitForceClose(username, password, auditReason) },
            ) { Text("Force close", color = DangerRed) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissSheet) { Text("Cancel", color = TextPrimary) }
        },
        containerColor = GraphiteSurface,
    )
}
