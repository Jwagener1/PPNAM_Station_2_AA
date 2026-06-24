package com.ppnam.station2aa.ui.rfid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppnam.station2aa.data.rfid.ScanEvent
import com.ppnam.station2aa.data.rfid.ScanEventBus
import com.ppnam.station2aa.domain.model.Pallet
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
    private val scanEventBus: ScanEventBus
) : ViewModel() {

    private val _uiState = MutableStateFlow<RfidUiState>(RfidUiState.Idle)
    val uiState: StateFlow<RfidUiState> = _uiState.asStateFlow()

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
