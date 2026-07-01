package com.ppnam.station2aa.data.mqtt.dto

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AuthMessagesTest {

    private val gson = Gson()

    @Test
    fun `ReaderLoginRequest serializes contract field names`() {
        val request = ReaderLoginRequest(
            messageId = "login-0001",
            deviceId = "handheld_1",
            timestampUtc = "2026-06-30T10:30:00Z",
            correlationKey = "login-0001",
            username = "operator1",
            password = "1234"
        )
        val json = JsonParser.parseString(gson.toJson(request)).asJsonObject
        assertEquals("login-0001", json.get("messageId").asString)
        assertEquals("1.0", json.get("schemaVersion").asString)
        assertEquals("handheld_1", json.get("deviceId").asString)
        assertEquals("", json.get("operatorSessionId").asString)
        assertEquals("2026-06-30T10:30:00Z", json.get("timestampUtc").asString)
        assertEquals("login-0001", json.get("correlationKey").asString)
        assertEquals("operator1", json.get("username").asString)
        assertEquals("1234", json.get("password").asString)
    }

    @Test
    fun `LoginTagScannedRequest serializes badgeTag field`() {
        val request = LoginTagScannedRequest(
            messageId = "login-0002",
            deviceId = "handheld_1",
            timestampUtc = "2026-06-30T10:30:00Z",
            correlationKey = "login-0002",
            badgeTag = "TAG-JSMITH"
        )
        val json = JsonParser.parseString(gson.toJson(request)).asJsonObject
        assertEquals("TAG-JSMITH", json.get("badgeTag").asString)
    }

    @Test
    fun `ReaderLogoutRequest serializes operatorSessionId`() {
        val request = ReaderLogoutRequest(
            messageId = "logout-0001",
            deviceId = "handheld_1",
            operatorSessionId = "session-id",
            timestampUtc = "2026-06-30T10:30:30Z",
            correlationKey = "logout-0001"
        )
        val json = JsonParser.parseString(gson.toJson(request)).asJsonObject
        assertEquals("session-id", json.get("operatorSessionId").asString)
    }

    @Test
    fun `OperatorContextResponse deserializes a successful login response`() {
        val raw = """
            {
              "messageId": "login-0001",
              "schemaVersion": "1.0",
              "deviceId": "handheld_1",
              "operatorSessionId": "sess-123",
              "timestampUtc": "2026-06-30T10:30:01Z",
              "correlationKey": "login-0001",
              "accepted": true,
              "reason": null,
              "operatorId": "OP-1",
              "displayName": "Jane Smith",
              "role": "Operator",
              "allowedActions": ["job_card_submitted", "ingredient_scanned"],
              "allowedTabs": ["Mixing", "Rajoo"]
            }
        """.trimIndent()
        val response = gson.fromJson(raw, OperatorContextResponse::class.java)
        assertTrue(response.accepted)
        assertEquals("sess-123", response.operatorSessionId)
        assertEquals("Jane Smith", response.displayName)
        assertEquals(2, response.allowedActions.size)
    }

    @Test
    fun `OperatorContextResponse deserializes a failed login response`() {
        val raw = """
            {
              "messageId": "login-0001",
              "schemaVersion": "1.0",
              "deviceId": "handheld_1",
              "operatorSessionId": null,
              "timestampUtc": "2026-06-30T10:30:01Z",
              "correlationKey": "login-0001",
              "accepted": false,
              "reason": "Invalid credentials"
            }
        """.trimIndent()
        val response = gson.fromJson(raw, OperatorContextResponse::class.java)
        assertFalse(response.accepted)
        assertNull(response.operatorSessionId)
        assertEquals("Invalid credentials", response.reason)
    }

    @Test
    fun `OperatorContextResponse deserializes the real Station 2 backend login response`() {
        // Verbatim payload captured from a live Station 2 login exchange — this is the
        // ground truth for field names, since the design spec's assumed names (success,
        // errorMessage, operatorName) turned out not to match the real backend.
        val raw = """
            {"accepted":true,"reason":null,"operatorId":"0209e218fd2940b5b0d7c889801fec22","displayName":"Avi","role":"Administrator","roleLabel":"Admin","allowedActions":["submit_job_card","recover_holding","scan_ingredient","assign_hopper","complete_premix","allocate_premix","allocate_full_pallet"],"allowedTabs":["pre_mix","allocation"],"messageId":"response-21e9f35f-3474-417e-b9e4-1cd19fef27bc","schemaVersion":"1.0","deviceId":"handheld_1","operatorSessionId":"b4ce5e9e93544507af5504177f798591","timestampUtc":"2026-07-01T08:22:56.5535261+00:00","correlationKey":"21e9f35f-3474-417e-b9e4-1cd19fef27bc"}
        """.trimIndent()
        val response = gson.fromJson(raw, OperatorContextResponse::class.java)
        assertTrue(response.accepted)
        assertEquals("b4ce5e9e93544507af5504177f798591", response.operatorSessionId)
        assertEquals("Avi", response.displayName)
        assertEquals("Administrator", response.role)
        assertEquals(7, response.allowedActions.size)
        assertEquals(2, response.allowedTabs.size)
    }
}
