package com.ppnam.station2aa.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ppnam.station2aa.data.session.OperatorSession
import com.ppnam.station2aa.data.session.OperatorSessionHolder
import com.ppnam.station2aa.domain.repository.MqttConnectionState
import com.ppnam.station2aa.domain.repository.MqttRepository
import com.ppnam.station2aa.domain.usecase.AuthUseCase
import com.ppnam.station2aa.ui.components.ConnectionStatus
import com.ppnam.station2aa.ui.components.resolveConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val authUseCase: AuthUseCase,
    sessionHolder: OperatorSessionHolder
) : ViewModel() {

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val connectionStatus: StateFlow<ConnectionStatus> = combine(
        mqttRepository.connectionState,
        mqttRepository.stationOnline,
        mqttRepository.clockSkewMillis,
    ) { state, stationOnline, skew ->
        resolveConnectionStatus(state, stationOnline, skew)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionStatus.Offline)

    val session: StateFlow<OperatorSession?> = sessionHolder.session

    private val _logoutEvent = Channel<Unit>(Channel.BUFFERED)
    val logoutEvent: Flow<Unit> = _logoutEvent.receiveAsFlow()

    fun logout() {
        viewModelScope.launch {
            authUseCase.logout()
            _logoutEvent.send(Unit)
        }
    }
}
