package com.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MqttTopicsTest {

    @Test
    fun `request topic uses the req segment`() {
        assertEquals(
            "PPNAM/handheld_1/req/login_requested",
            MqttTopics.request("handheld_1", "login_requested")
        )
    }

    @Test
    fun `responseWildcard subscribes to the res segment only`() {
        assertEquals("PPNAM/handheld_1/res/+", MqttTopics.responseWildcard("handheld_1"))
    }

    @Test
    fun `deviceStatus topic for a device`() {
        assertEquals("PPNAM/handheld_1/status", MqttTopics.deviceStatus("handheld_1"))
    }

    @Test
    fun `station status is the literal contract topic`() {
        assertEquals("PPNAM/station_2/status", MqttTopics.STATION_STATUS)
    }

    @Test
    fun `responseTypeOf extracts the last topic segment`() {
        assertEquals(
            "operator_context",
            MqttTopics.responseTypeOf("PPNAM/handheld_1/res/operator_context")
        )
    }

    @Test
    fun `deviceId containing a slash is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.request("hand/held", "login_requested")
        }
    }

    @Test
    fun `requestType containing a plus wildcard is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.request("handheld_1", "login+requested")
        }
    }

    @Test
    fun `requestType containing a hash wildcard is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.request("handheld_1", "login#requested")
        }
    }

    @Test
    fun `blank deviceId is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.request("", "login_requested")
        }
    }

    @Test
    fun `responseWildcard validates its device id`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.responseWildcard("hand#held")
        }
    }

    @Test
    fun `deviceStatus validates its device id`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.deviceStatus("hand+held")
        }
    }

    @Test
    fun `schema version is exactly 3 point 0`() {
        assertEquals("3.0", MqttSchema.VERSION)
    }
}
