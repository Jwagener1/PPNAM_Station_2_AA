package com.ppnam.station2aa.data.mqtt

/**
 * Contract v3.0 topic structure:
 *
 *   PPNAM/{deviceId}/status
 *   PPNAM/{deviceId}/req/{requestType}
 *   PPNAM/{deviceId}/res/{responseType}
 *
 * A handheld subscribes to PPNAM/{ownDeviceId}/res/+ and PPNAM/station_2/status.
 */
object MqttTopics {

    /** Station 2's presence topic is a fixed literal in the contract, not a configured name. */
    const val STATION_STATUS = "PPNAM/station_2/status"

    fun request(deviceId: String, requestType: String): String {
        validateSegment(deviceId, "deviceId")
        validateSegment(requestType, "requestType")
        return "PPNAM/$deviceId/req/$requestType"
    }

    fun responseWildcard(deviceId: String): String {
        validateSegment(deviceId, "deviceId")
        return "PPNAM/$deviceId/res/+"
    }

    fun deviceStatus(deviceId: String): String {
        validateSegment(deviceId, "deviceId")
        return "PPNAM/$deviceId/status"
    }

    fun responseTypeOf(topic: String): String = topic.substringAfterLast('/')

    // The contract forbids '/', '+' and '#' in a topic segment. A segment carrying one of these
    // would silently reshape the topic (or subscribe to a wildcard), so fail loudly instead.
    private fun validateSegment(value: String, name: String) {
        require(value.isNotBlank()) { "$name must not be blank" }
        require(value.none { it == '/' || it == '+' || it == '#' }) {
            "$name must not contain '/', '+' or '#': was '$value'"
        }
    }
}
