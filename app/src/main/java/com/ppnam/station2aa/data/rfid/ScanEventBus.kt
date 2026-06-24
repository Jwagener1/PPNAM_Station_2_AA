package com.ppnam.station2aa.data.rfid

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed class ScanEvent {
    data class RfidTag(val tagId: String, val timestamp: Instant) : ScanEvent()
    data class Barcode(val value: String, val format: String, val timestamp: Instant) : ScanEvent()
}

@Singleton
class ScanEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ScanEvent> = _events.asSharedFlow()

    fun emit(event: ScanEvent) { _events.tryEmit(event) }
}
