package com.ppnam.station2aa.domain.model

import org.junit.Assert.*
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `default stationName is Station 2`() {
        assertEquals("Station 2", AppSettings().stationName)
    }

    @Test
    fun `default scannerId is 1`() {
        assertEquals(1, AppSettings().scannerId)
    }

    @Test
    fun `default deviceId is handheld_1`() {
        assertEquals("handheld_1", AppSettings().deviceId)
    }

    @Test
    fun `default mqtt host is mqtt sysone co za`() {
        assertEquals("mqtt.sysone.co.za", AppSettings().mqttHost)
    }

    @Test
    fun `default mqtt port is 8884`() {
        assertEquals(8884, AppSettings().mqttPort)
    }

    @Test
    fun `default uses websocket and tls`() {
        val s = AppSettings()
        assertTrue(s.mqttUseWebSocket)
        assertTrue(s.mqttUseTls)
    }

    @Test
    fun `default credentials are admin admin`() {
        val s = AppSettings()
        assertEquals("admin", s.mqttUsername)
        assertEquals("admin", s.mqttPassword)
    }

    @Test
    fun `default requestTimeoutMs is 10000`() {
        assertEquals(10_000L, AppSettings().requestTimeoutMs)
    }
}
