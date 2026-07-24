package com.ppnam.station2aa.domain.model

import org.junit.Assert.*
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `default deviceId is handheld_1`() {
        assertEquals("handheld_1", AppSettings().deviceId)
    }

    @Test
    fun `default mqtt host is mqtt sysone co za`() {
        assertEquals("mqtt.sysone.co.za", AppSettings().mqttHost)
    }

    @Test
    fun `default mqtt port is 443`() {
        assertEquals(443, AppSettings().mqttPort)
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
    fun `default requestTimeoutMs is 20000`() {
        // Raised from 10s: factory-floor WiFi is unreliable enough that 3 x 10s (30s total)
        // regularly wasn't enough headroom, causing genuinely-accepted requests to be timed out
        // client-side while Station 2 processed them — see MixingBoardViewModel's Failed re-sync.
        assertEquals(20_000L, AppSettings().requestTimeoutMs)
    }
}
