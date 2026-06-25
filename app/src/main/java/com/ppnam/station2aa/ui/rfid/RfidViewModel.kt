package com.ppnam.station2aa.ui.rfid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppnam.station2aa.data.local.OfflineQueueRepository
import com.ppnam.station2aa.data.rfid.ScanEvent
import com.ppnam.station2aa.data.rfid.ScanEventBus
import com.ppnam.station2aa.domain.model.Pallet
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
import com.ppnam.station2aa.domain.usecase.RfidUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RfidUiState {
    object Idle : RfidUiState()
    object Loading : RfidUiState()
    data class PalletFound(val pallet: Pallet) : RfidUiState()
    data class Error(val message: String) : RfidUiState()
}

@HiltViewModel
class RfidViewModel @Inject constructor(
    private val useCase: RfidUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val offlineQueueRepository: OfflineQueueRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<RfidUiState>(RfidUiState.Idle)
    val uiState: StateFlow<RfidUiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val pendingCount: StateFlow<Int> = offlineQueueRepository.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var scanJob: Job? = null

    fun startListening() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                lookupPallet(event.tagId)
            }
        }
    }

    private fun lookupPallet(tagId: String) {
        viewModelScope.launch {
            _uiState.value = RfidUiState.Loading
            useCase.lookupPallet(tagId)
                .onSuccess { pallet -> _uiState.value = RfidUiState.PalletFound(pallet) }
                .onFailure { e -> _uiState.value = RfidUiState.Error(e.message ?: "Unknown error") }
        }
    }

    fun resetToIdle() {
        _uiState.value = RfidUiState.Idle
        startListening()
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }
}
