package com.ppnam.station2aa.ui.rajoo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppnam.station2aa.data.local.OfflineQueueRepository
import com.ppnam.station2aa.data.rfid.ScanEvent
import com.ppnam.station2aa.data.rfid.ScanEventBus
import com.ppnam.station2aa.domain.model.AllocationRecord
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
import com.ppnam.station2aa.domain.usecase.RajooUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RajooUiState {
    object Idle : RajooUiState()
    object Loading : RajooUiState()
    data class MachinesLoaded(val machines: List<String>) : RajooUiState()
    data class AllocationSuccess(val record: AllocationRecord) : RajooUiState()
    data class Error(val message: String) : RajooUiState()
}

@HiltViewModel
class RajooViewModel @Inject constructor(
    private val useCase: RajooUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val offlineQueueRepository: OfflineQueueRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<RajooUiState>(RajooUiState.Idle)
    val uiState: StateFlow<RajooUiState> = _uiState.asStateFlow()

    private val _navigationEvent = Channel<String>(Channel.BUFFERED)
    val navigationEvent: Flow<String> = _navigationEvent.receiveAsFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val pendingCount: StateFlow<Int> = offlineQueueRepository.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var scanJob: Job? = null

    fun loadMachines() {
        viewModelScope.launch {
            _uiState.value = RajooUiState.Loading
            useCase.getMachines()
                .onSuccess { machines -> _uiState.value = RajooUiState.MachinesLoaded(machines) }
                .onFailure { e -> _uiState.value = RajooUiState.Error(e.message ?: "Unknown error") }
        }
    }

    fun startListeningForScans(machineCode: String) {
        scanJob?.cancel()
        _uiState.value = RajooUiState.Idle
        scanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                allocatePallet(machineCode, event.tagId)
            }
        }
    }

    fun allocatePallet(machineCode: String, tagId: String) {
        viewModelScope.launch {
            _uiState.value = RajooUiState.Loading
            useCase.allocatePallet(machineCode, tagId)
                .onSuccess { record -> _uiState.value = RajooUiState.AllocationSuccess(record) }
                .onFailure { e -> _uiState.value = RajooUiState.Error(e.message ?: "Unknown error") }
        }
    }

    fun navigateHome() {
        viewModelScope.launch { _navigationEvent.send("home") }
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }
}
