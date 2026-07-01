package com.ppnam.station2aa.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class OperatorSession(
    val operatorSessionId: String,
    val operatorId: String,
    val operatorName: String,
    val role: String,
    val allowedActions: List<String> = emptyList(),
    val allowedTabs: List<String> = emptyList()
)

@Singleton
class OperatorSessionHolder @Inject constructor() {
    private val _session = MutableStateFlow<OperatorSession?>(null)
    val session: StateFlow<OperatorSession?> = _session.asStateFlow()

    fun set(session: OperatorSession) {
        _session.value = session
    }

    fun clear() {
        _session.value = null
    }

    fun currentSessionIdOrEmpty(): String = _session.value?.operatorSessionId ?: ""
}
