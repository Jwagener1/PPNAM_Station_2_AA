package com.ppnam.station2aa.ui.mixing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppnam.station2aa.data.rfid.ScanEvent
import com.ppnam.station2aa.data.rfid.ScanEventBus
import com.ppnam.station2aa.domain.model.ProductionOrder
import com.ppnam.station2aa.domain.model.ScannedIngredient
import com.ppnam.station2aa.domain.usecase.MixingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class MixingUiState {
    object Idle : MixingUiState()
    object Loading : MixingUiState()
    data class OrderLoaded(val order: ProductionOrder) : MixingUiState()
    data class Error(val message: String) : MixingUiState()
}

@HiltViewModel
class MixingViewModel @Inject constructor(
    val useCase: MixingUseCase,
    private val scanEventBus: ScanEventBus
) : ViewModel() {

    private val _uiState = MutableStateFlow<MixingUiState>(MixingUiState.Idle)
    val uiState: StateFlow<MixingUiState> = _uiState.asStateFlow()

    private val _scannedIngredients = MutableStateFlow<List<ScannedIngredient>>(emptyList())
    val scannedIngredients: StateFlow<List<ScannedIngredient>> = _scannedIngredients.asStateFlow()

    private val _mixerCode = MutableStateFlow("")
    val mixerCode: StateFlow<String> = _mixerCode.asStateFlow()

    fun lookupJob(orderNo: String) {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.lookupJob(orderNo)
                .onSuccess { order -> _uiState.value = MixingUiState.OrderLoaded(order) }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Unknown error") }
        }
    }

    fun startListeningForScans(orderNo: String) {
        viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                val valid = useCase.validateIngredientOffline(event.tagId, orderNo)
                if (valid) {
                    val ingredient = ScannedIngredient(event.tagId, event.tagId, 1.0)
                    _scannedIngredients.update { it + ingredient }
                } else {
                    _uiState.value = MixingUiState.Error("Unknown tag: ${event.tagId}")
                }
            }
        }
        viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.Barcode>().collect { event ->
                if (_mixerCode.value.isEmpty()) setMixerCode(event.value)
            }
        }
    }

    fun setMixerCode(code: String) {
        _mixerCode.value = code
    }

    fun completePremix(orderNo: String) {
        val ingredients = _scannedIngredients.value
        val mixer = _mixerCode.value
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.completePremix(orderNo, mixer, ingredients)
                .onSuccess { _uiState.value = MixingUiState.Idle }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Unknown error") }
        }
    }

    fun clearError() {
        if (_uiState.value is MixingUiState.Error) {
            _uiState.value = MixingUiState.Idle
        }
    }
}
