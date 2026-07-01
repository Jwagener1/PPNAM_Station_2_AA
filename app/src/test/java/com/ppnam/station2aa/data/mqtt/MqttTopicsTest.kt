package com.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

class MqttTopicsTest {

    @Test
    fun `request topic lowercases and removes spaces from station name`() {
        assertEquals("station2/request", MqttTopics.request("Station 2"))
    }

    @Test
    fun `request topic handles station 3`() {
        assertEquals("station3/request", MqttTopics.request("Station 3"))
    }

    @Test
    fun `response topic includes device id`() {
        assertEquals("station2/response/abc123", MqttTopics.response("Station 2", "abc123"))
    }

    @Test
    fun `topics trim leading and trailing spaces`() {
        assertEquals("station2/request", MqttTopics.request("  Station 2  "))
    }

    @Test
    fun `contractRequest combines device id and request type`() {
        assertEquals(
            "PPNAM/handheld_1/reader_login_requested",
            MqttTopics.contractRequest("handheld_1", "reader_login_requested")
        )
    }

    @Test
    fun `contractResponse combines device id and response type`() {
        assertEquals(
            "PPNAM/handheld_1/operator_context",
            MqttTopics.contractResponse("handheld_1", "operator_context")
        )
    }

    @Test
    fun `contractResponseWildcard subscribes to every response type for a device`() {
        assertEquals("PPNAM/handheld_1/+", MqttTopics.contractResponseWildcard("handheld_1"))
    }

    @Test
    fun `deviceStatus topic for a device`() {
        assertEquals("PPNAM/handheld_1/status", MqttTopics.deviceStatus("handheld_1"))
    }

    @Test
    fun `stationStatus normalizes station name to snake case`() {
        assertEquals("PPNAM/station_2/status", MqttTopics.stationStatus("Station 2"))
    }

    @Test
    fun `stationStatus trims and lowercases`() {
        assertEquals("PPNAM/station_2/status", MqttTopics.stationStatus("  Station 2  "))
    }

    @Test
    fun `responseTypeOf extracts the last topic segment`() {
        assertEquals("operator_context", MqttTopics.responseTypeOf("PPNAM/handheld_1/operator_context"))
    }
}
