package com.ppnam.station2aa.ui.mixing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppnam.station2aa.data.local.OfflineQueueRepository
import com.ppnam.station2aa.data.rfid.ScanEvent
import com.ppnam.station2aa.data.rfid.ScanEventBus
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
    data class OrderLoaded(val order: ProductionOrder) : MixingUiState()
    data class Error(val message: String) : MixingUiState()
}

object MixingNavDestination {
    const val MIXER_CODE = "mixer_code"
    const val PREMIX_COMPLETE = "premix_complete"
    const val HOME = "home"
}

@HiltViewModel
class MixingViewModel @Inject constructor(
    private val useCase: MixingUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val offlineQueueRepository: OfflineQueueRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<MixingUiState>(MixingUiState.Idle)
    val uiState: StateFlow<MixingUiState> = _uiState.asStateFlow()

    private val _scannedIngredients = MutableStateFlow<List<ScannedIngredient>>(emptyList())
    val scannedIngredients: StateFlow<List<ScannedIngredient>> = _scannedIngredients.asStateFlow()

    private val _mixerCode = MutableStateFlow("")
    val mixerCode: StateFlow<String> = _mixerCode.asStateFlow()

    private val _isQueuedOffline = MutableStateFlow(false)
    val isQueuedOffline: StateFlow<Boolean> = _isQueuedOffline.asStateFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val pendingCount: StateFlow<Int> = offlineQueueRepository.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _navigationEvent = Channel<String>(Channel.BUFFERED)
    val navigationEvent: Flow<String> = _navigationEvent.receiveAsFlow()

    private var scanJob: Job? = null

    fun lookupJob(orderNo: String) {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.lookupJob(orderNo)
                .onSuccess { order -> _uiState.value = MixingUiState.OrderLoaded(order) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Unknown error") }
        }
    }

    fun startListeningForScans(orderNo: String) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            launch {
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
                                is IngredientValidationResult.Invalid ->
                                    _uiState.value = MixingUiState.Error("Invalid ingredient: ${validation.reason}")
                            }
                        }
                        .onFailure {
                            _uiState.value = MixingUiState.Error("Unknown ingredient: ${event.tagId}")
                        }
                }
            }
            launch {
                scanEventBus.events.filterIsInstance<ScanEvent.Barcode>().collect { event ->
                    if (_mixerCode.value.isEmpty()) setMixerCode(event.value)
                }
            }
        }
    }

    fun startListeningForBarcode() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.Barcode>().collect { event ->
                if (_mixerCode.value.isEmpty()) setMixerCode(event.value)
            }
        }
    }

    fun setMixerCode(code: String) { _mixerCode.value = code }

    fun completePremix(orderNo: String) {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.completePremix(orderNo, _mixerCode.value, _scannedIngredients.value)
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
}
