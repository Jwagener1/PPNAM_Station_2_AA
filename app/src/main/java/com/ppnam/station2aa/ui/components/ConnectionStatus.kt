package com.ppnam.station2aa.ui.components

import com.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlin.math.abs

/** Beyond this, the device clock is a plausible cause of blanket message_expired rejections. */
const val SKEW_THRESHOLD_MS = 30_000L

enum class ConnectionStatus { Offline, Reconnecting, StationOffline, ClockSkewed, Connected }

/**
 * Resolves what to tell the operator about connectivity, in precedence order.
 *
 * Broker connectivity alone is not "connected": the broker can be up while Station 2 is down, in
 * which case every request times out with no clue why. Station 2's retained presence topic is what
 * makes that visible.
 *
 * Clock skew ranks below station presence because a skew reading is only meaningful once we have
 * had a response to measure it from. It warns rather than blocks: a skewed clock fails every
 * request with message_expired, but the operator cannot fix the clock from here, and blocking would
 * strand them. A specific warning turns "everything is mysteriously broken" into something
 * actionable.
 */
fun resolveConnectionStatus(
    connectionState: MqttConnectionState,
    stationOnline: Boolean,
    clockSkewMillis: Long?,
    skewThresholdMs: Long = SKEW_THRESHOLD_MS,
): ConnectionStatus = when {
    connectionState == MqttConnectionState.DISCONNECTED -> ConnectionStatus.Offline
    connectionState == MqttConnectionState.RECONNECTING -> ConnectionStatus.Reconnecting
    !stationOnline -> ConnectionStatus.StationOffline
    clockSkewMillis != null && abs(clockSkewMillis) > skewThresholdMs -> ConnectionStatus.ClockSkewed
    else -> ConnectionStatus.Connected
}
