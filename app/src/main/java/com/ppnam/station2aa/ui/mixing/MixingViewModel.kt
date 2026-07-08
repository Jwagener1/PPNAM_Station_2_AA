package com.ppnam.station2aa.ui.mixing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppnam.station2aa.data.local.OfflineQueueRepository
import com.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.ppnam.station2aa.data.rfid.ScanEvent
import com.ppnam.station2aa.data.rfid.ScanEventBus
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.domain.model.HopperStatus
import com.ppnam.station2aa.domain.model.IngredientScanOutcome
import com.ppnam.station2aa.domain.model.IngredientValidationResult
import com.ppnam.station2aa.domain.model.ProductionOrder
import com.ppnam.station2aa.domain.model.ScannedIngredient
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
import com.ppnam.station2aa.domain.usecase.MixingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class MixingUiState {
    object Idle : MixingUiState()
    object Loading : MixingUiState()
    object Cancelling : MixingUiState()
    data class OrderLoaded(val order: ProductionOrder) : MixingUiState()
    data class IngredientInvalid(val tagId: String, val reason: String) : MixingUiState()
    data class WaitingForSupervisor(val tagId: String, val reason: String) : MixingUiState()
    data class EnteringBagDetails(val palletTag: String) : MixingUiState()
    data class IngredientExceptionApproval(val exceptionId: String, val reason: String) : MixingUiState()
    data class PalletRecoveryPrompt(val palletTag: String) : MixingUiState()
    data class HopperUnavailable(val hopperCode: String, val reason: String) : MixingUiState()
    data class Error(val message: String) : MixingUiState()
}

object MixingNavDestination {
    const val PREMIX_COMPLETE = "premix_complete"
    const val HOME = "home"
}

sealed class CancelOutcome {
    object Confirmed : CancelOutcome()
    data class Failed(val reason: String) : CancelOutcome()
}

@HiltViewModel
class MixingViewModel @Inject constructor(
    private val useCase: MixingUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val offlineQueueRepository: OfflineQueueRepository,
    private val sessionHolder: OperatorSessionHolder
) : ViewModel() {

    private val _uiState = MutableStateFlow<MixingUiState>(MixingUiState.Idle)
    val uiState: StateFlow<MixingUiState> = _uiState.asStateFlow()

    private val _scannedIngredients = MutableStateFlow<List<ScannedIngredient>>(emptyList())
    val scannedIngredients: StateFlow<List<ScannedIngredient>> = _scannedIngredients.asStateFlow()

    private val _hopperCode = MutableStateFlow("")
    val hopperCode: StateFlow<String> = _hopperCode.asStateFlow()

    private val _isQueuedOffline = MutableStateFlow(false)
    val isQueuedOffline: StateFlow<Boolean> = _isQueuedOffline.asStateFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val pendingCount: StateFlow<Int> = offlineQueueRepository.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _activeJobs = MutableStateFlow<List<ActiveJobCardSummary>>(emptyList())
    val activeJobs: StateFlow<List<ActiveJobCardSummary>> = _activeJobs.asStateFlow()

    private val _activeJobsError = MutableStateFlow<String?>(null)
    val activeJobsError: StateFlow<String?> = _activeJobsError.asStateFlow()

    val hopperStatusUpdates: SharedFlow<HopperStatus> = mqttRepository.hopperStatusUpdates

    private val _navigationEvent = Channel<String>(Channel.BUFFERED)
    val navigationEvent: Flow<String> = _navigationEvent.receiveAsFlow()

    private val _supervisorError = Channel<String>(Channel.BUFFERED)
    val supervisorError: Flow<String> = _supervisorError.receiveAsFlow()

    private val _cancelOutcome = Channel<CancelOutcome>(Channel.BUFFERED)
    val cancelOutcome: Flow<CancelOutcome> = _cancelOutcome.receiveAsFlow()

    private var scanJob: Job? = null
    private var currentOrderNo: String = ""
    private var cachedOrder: ProductionOrder? = null

    fun lookupJob(orderNo: String, preMixId: String = "") {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.lookupJob(orderNo, preMixId)
                .onSuccess { order ->
                    currentOrderNo = orderNo
                    cachedOrder = order
                    _uiState.value = MixingUiState.OrderLoaded(order)
                }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Unknown error") }
        }
    }

    fun loadActiveJobs() {
        viewModelScope.launch {
            useCase.fetchActiveJobCards()
                .onSuccess { jobs ->
                    _activeJobs.value = jobs
                    _activeJobsError.value = null
                }
                .onFailure { e -> _activeJobsError.value = e.message ?: "Could not load active jobs" }
        }
    }

    fun startListeningForScans(orderNo: String) {
        currentOrderNo = orderNo
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                useCase.validateIngredient(orderNo, event.tagId)
                    .onSuccess { validation ->
                        when (validation) {
                            is IngredientValidationResult.Valid -> {
                                val ingredient = ScannedIngredient(
                                    tagId = event.tagId,
                                    itemCode = validation.bomLine.itemCode,
                                    qty = 1.0
                                )
                                _scannedIngredients.update { it + ingredient }
                            }
                            is IngredientValidationResult.Invalid -> {
                                scanJob?.cancel()
                                _uiState.value = MixingUiState.IngredientInvalid(
                                    tagId = validation.tagId,
                                    reason = validation.reason
                                )
                            }
                        }
                    }
                    .onFailure { e ->
                        _uiState.value = MixingUiState.Error(e.message ?: "Validation failed")
                    }
            }
        }
    }

    fun discardInvalidIngredient() {
        val order = cachedOrder ?: run {
            _uiState.value = MixingUiState.Error("Session lost — please re-scan job card")
            return
        }
        startListeningForScans(currentOrderNo)
        _uiState.value = MixingUiState.OrderLoaded(order)
    }

    fun requestSupervisorOverride(tagId: String, reason: String) {
        scanJob?.cancel()
        _uiState.value = MixingUiState.WaitingForSupervisor(tagId, reason)
        scanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                val pendingState = _uiState.value
                if (pendingState is MixingUiState.WaitingForSupervisor) {
                    submitSupervisorTag(currentOrderNo, pendingState.tagId, event.tagId)
                }
            }
        }
    }

    fun submitSupervisorTag(orderNo: String, tagId: String, supervisorTagId: String) {
        scanJob?.cancel()
        viewModelScope.launch {
            useCase.approveIngredientException(orderNo, tagId, supervisorTagId)
                .onSuccess { ingredient ->
                    _scannedIngredients.update { it + ingredient }
                    startListeningForScans(orderNo)
                    cachedOrder?.let { _uiState.value = MixingUiState.OrderLoaded(it) }
                }
                .onFailure { e ->
                    _supervisorError.trySend(e.message ?: "Approval failed")
                    requestSupervisorOverride(tagId, (_uiState.value as? MixingUiState.WaitingForSupervisor)?.reason ?: "")
                }
        }
    }

    private data class PendingIngredientScan(
        val palletRfidTag: String,
        val bagSizeOption: String,
        val bagCount: Double
    )

    private var pendingScan: PendingIngredientScan? = null
    private var pendingExceptionId: String = ""

    fun startListeningForPalletScans(orderNo: String) {
        currentOrderNo = orderNo
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                _uiState.value = MixingUiState.EnteringBagDetails(event.tagId)
            }
        }
    }

    fun cancelBagEntry() {
        val order = cachedOrder ?: return
        _uiState.value = MixingUiState.OrderLoaded(order)
    }

    fun confirmIngredientScan(palletTag: String, bagSizeOption: String, bagCount: Double) {
        val order = cachedOrder ?: return
        pendingScan = PendingIngredientScan(palletTag, bagSizeOption, bagCount)
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.scanIngredient(order.preMixId, palletTag, bagSizeOption, bagCount)
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Scan failed") }
        }
    }

    fun submitManagerApproval(managerUsername: String, managerPassword: String) {
        val order = cachedOrder ?: return
        val scan = pendingScan ?: return
        viewModelScope.launch {
            useCase.approveManagerException(
                exceptionId = pendingExceptionId,
                preMixId = order.preMixId,
                palletRfidTag = scan.palletRfidTag,
                managerUsername = managerUsername,
                managerPassword = managerPassword,
                reason = "Operator-requested exception approval"
            )
                .onSuccess { approvalId -> retryPendingScan(order, approvalId) }
                .onFailure { e -> _supervisorError.trySend(e.message ?: "Approval failed") }
        }
    }

    fun cancelManagerApproval() {
        pendingScan = null
        pendingExceptionId = ""
        val order = cachedOrder ?: return
        _uiState.value = MixingUiState.OrderLoaded(order)
    }

    fun confirmPalletRecovery() {
        val order = cachedOrder ?: return
        val scan = pendingScan ?: return
        viewModelScope.launch {
            useCase.recoverHolding(order.preMixId, scan.palletRfidTag)
                .onSuccess { retryPendingScan(order, "") }
                .onFailure { e ->
                    pendingScan = null
                    _supervisorError.trySend(e.message ?: "Recovery failed")
                    _uiState.value = MixingUiState.OrderLoaded(order)
                }
        }
    }

    fun dismissPalletRecovery() {
        pendingScan = null
        val order = cachedOrder ?: return
        _uiState.value = MixingUiState.OrderLoaded(order)
    }

    private fun handleScanOutcome(order: ProductionOrder, outcome: IngredientScanOutcome) {
        when (outcome) {
            is IngredientScanOutcome.Accepted -> {
                val updatedOrder = order.copy(lines = outcome.updatedLines)
                cachedOrder = updatedOrder
                pendingScan = null
                _uiState.value = MixingUiState.OrderLoaded(updatedOrder)
            }
            is IngredientScanOutcome.NeedsManagerApproval -> {
                pendingExceptionId = outcome.exceptionId
                _uiState.value = MixingUiState.IngredientExceptionApproval(outcome.exceptionId, outcome.reason)
            }
            is IngredientScanOutcome.NeedsRecovery -> {
                _uiState.value = MixingUiState.PalletRecoveryPrompt(pendingScan?.palletRfidTag ?: "")
            }
            is IngredientScanOutcome.Rejected -> {
                pendingScan = null
                _supervisorError.trySend(outcome.reason)
                _uiState.value = MixingUiState.OrderLoaded(order)
            }
        }
    }

    private fun retryPendingScan(order: ProductionOrder, approvalId: String) {
        val scan = pendingScan
        if (scan == null) {
            _uiState.value = MixingUiState.OrderLoaded(order)
            return
        }
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.scanIngredient(order.preMixId, scan.palletRfidTag, scan.bagSizeOption, scan.bagCount, approvalId)
                .onSuccess { outcome -> handleScanOutcome(order, outcome) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Scan failed") }
        }
    }

    fun checkAndAllocateHopper(orderNo: String, hopperCode: String) {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.checkHopper(orderNo, hopperCode)
                .onSuccess {
                    _hopperCode.value = hopperCode
                    _navigationEvent.send(MixingNavDestination.PREMIX_COMPLETE)
                }
                .onFailure { e ->
                    _uiState.value = MixingUiState.HopperUnavailable(hopperCode, e.message ?: "Unavailable")
                }
        }
    }

    fun startListeningForHopperBarcode(orderNo: String) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.Barcode>().collect { event ->
                if (_hopperCode.value.isBlank()) {
                    checkAndAllocateHopper(orderNo, event.value)
                }
            }
        }
    }

    fun completePremix(orderNo: String) {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.completePremix(orderNo, _hopperCode.value, _scannedIngredients.value)
                .onSuccess { _navigationEvent.send(MixingNavDestination.PREMIX_COMPLETE) }
                .onFailure { e ->
                    if (e.message?.startsWith("Queued") == true) {
                        _isQueuedOffline.value = true
                        _navigationEvent.send(MixingNavDestination.PREMIX_COMPLETE)
                    } else {
                        _uiState.value = MixingUiState.Error(e.message ?: "Failed to complete pre-mix")
                    }
                }
        }
    }

    fun clearError() {
        if (_uiState.value is MixingUiState.Error) _uiState.value = MixingUiState.Idle
    }

    fun operatorCanCancelDirectly(): Boolean =
        sessionHolder.session.value?.allowedActions?.contains("cancel_premix_direct") == true

    // Waits for premix_cancel_result before touching any local state — a rejected
    // cancel (e.g. the pre-mix already has scanned ingredients, or the manager
    // approval was denied) must leave the job exactly as it was, per the backend's
    // "only an untouched JC load can be closed" rule.
    fun cancelJob(managerUsername: String = "", managerPassword: String = "") {
        val jobCardNumber = currentOrderNo
        val preMixId = cachedOrder?.preMixId ?: ""
        if (jobCardNumber.isBlank()) return
        scanJob?.cancel()
        val orderBeforeCancel = cachedOrder
        viewModelScope.launch {
            _uiState.value = MixingUiState.Cancelling
            useCase.cancelJob(
                preMixId,
                jobCardNumber,
                "Operator cancelled — incorrect job card",
                managerUsername,
                managerPassword
            )
                .onSuccess {
                    currentOrderNo = ""
                    cachedOrder = null
                    _scannedIngredients.value = emptyList()
                    _hopperCode.value = ""
                    _isQueuedOffline.value = false
                    _uiState.value = MixingUiState.Idle
                    _cancelOutcome.send(CancelOutcome.Confirmed)
                }
                .onFailure { e ->
                    _uiState.value = orderBeforeCancel?.let { MixingUiState.OrderLoaded(it) } ?: MixingUiState.Idle
                    if (orderBeforeCancel != null) startListeningForScans(jobCardNumber)
                    _cancelOutcome.send(CancelOutcome.Failed(e.message ?: "Cancel failed"))
                }
        }
    }
}
