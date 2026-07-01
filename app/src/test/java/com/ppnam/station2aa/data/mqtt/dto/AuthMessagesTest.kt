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
              "success": true,
              "errorMessage": null,
              "operatorId": "OP-1",
              "operatorName": "Jane Smith",
              "role": "Operator",
              "allowedActions": ["job_card_submitted", "ingredient_scanned"],
              "allowedTabs": ["Mixing", "Rajoo"]
            }
        """.trimIndent()
        val response = gson.fromJson(raw, OperatorContextResponse::class.java)
        assertTrue(response.success)
        assertEquals("sess-123", response.operatorSessionId)
        assertEquals("Jane Smith", response.operatorName)
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
              "success": false,
              "errorMessage": "Invalid credentials"
            }
        """.trimIndent()
        val response = gson.fromJson(raw, OperatorContextResponse::class.java)
        assertFalse(response.success)
        assertNull(response.operatorSessionId)
        assertEquals("Invalid credentials", response.errorMessage)
    }
}
