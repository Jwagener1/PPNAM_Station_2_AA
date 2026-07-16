package com.ppnam.station2aa.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppnam.station2aa.data.settings.SettingsRepository
import com.ppnam.station2aa.domain.model.AppSettings
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
import com.ppnam.station2aa.ui.components.ConnectionStatus
import com.ppnam.station2aa.ui.components.resolveConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PinState {
    object Locked : PinState
    object Unlocked : PinState
}

sealed interface ApplyState {
    object Idle : ApplyState
    object Testing : ApplyState
    data class Success(val message: String) : ApplyState
    data class Failure(val message: String) : ApplyState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val mqttRepository: MqttRepository
) : ViewModel() {

    private val correctPin = "079545"

    var pinInput = mutableStateOf("")
        private set
    var pinState = mutableStateOf<PinState>(PinState.Locked)
        private set
    var pinError = mutableStateOf(false)
        private set
    var applyState = mutableStateOf<ApplyState>(ApplyState.Idle)
        private set
    var draftSettings = mutableStateOf(AppSettings())
        private set

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val connectionStatus: StateFlow<ConnectionStatus> = combine(
        mqttRepository.connectionState,
        mqttRepository.stationOnline,
        mqttRepository.clockSkewMillis,
    ) { state, stationOnline, skew ->
        resolveConnectionStatus(state, stationOnline, skew)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionStatus.Offline)

    init {
        viewModelScope.launch {
            draftSettings.value = settingsRepository.current()
        }
    }

    fun onPinChange(value: String) {
        if (value.length <= 6) {
            pinInput.value = value
            pinError.value = false
        }
    }

    fun submitPin() {
        if (pinInput.value == correctPin) {
            pinState.value = PinState.Unlocked
            pinError.value = false
        } else {
            pinInput.value = ""
            pinError.value = true
        }
    }

    fun updateDraft(settings: AppSettings) {
        draftSettings.value = settings
    }

    fun testAndApply() {
        applyState.value = ApplyState.Testing
        viewModelScope.launch {
            val result = mqttRepository.reconnectWith(draftSettings.value)
            if (result.isSuccess) {
                settingsRepository.save(draftSettings.value)
                applyState.value = ApplyState.Success("Connected — settings saved")
                delay(2_000)
                pinState.value = PinState.Locked
                pinInput.value = ""
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Connection failed"
                applyState.value = ApplyState.Failure(msg)
            }
        }
    }
}
