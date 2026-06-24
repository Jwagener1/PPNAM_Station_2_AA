package com.ppnam.station2aa.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppnam.station2aa.domain.usecase.DashboardUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val palletLocation: String = "",
    val palletTagInput: String = "",
    val preMixList: String = "",
    val exceptions: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val dashboardUseCase: DashboardUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    fun setPalletTagInput(tag: String) = _state.update { it.copy(palletTagInput = tag) }

    fun lookupPallet() {
        val tag = _state.value.palletTagInput
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            dashboardUseCase.fetchPalletLocation(tag)
                .onSuccess { json -> _state.update { it.copy(isLoading = false, palletLocation = json) } }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    fun loadPreMixList() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            dashboardUseCase.fetchPreMixList()
                .onSuccess { json -> _state.update { it.copy(isLoading = false, preMixList = json) } }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    fun loadExceptions() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            dashboardUseCase.fetchExceptions()
                .onSuccess { json -> _state.update { it.copy(isLoading = false, exceptions = json) } }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
        }
    }
}
