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
}
