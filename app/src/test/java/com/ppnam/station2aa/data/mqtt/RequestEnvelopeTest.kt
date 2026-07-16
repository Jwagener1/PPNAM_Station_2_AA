package com.ppnam.station2aa.data.mqtt

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestEnvelopeTest {

    private val gson = Gson()

    private data class LoginPayload(val username: String, val password: String)

    private fun build(payload: Any, correlationKey: String? = null): String =
        RequestEnvelope.build(
            gson = gson,
            payload = payload,
            messageId = "login-0001",
            deviceId = "handheld_1",
            operatorSessionId = "",
            timestampUtc = "2026-07-16T08:00:00Z",
            correlationKey = correlationKey,
        )

    @Test
    fun `envelope and payload are merged into one flat object`() {
        val json = JsonParser.parseString(build(LoginPayload("operator1", "secret"))).asJsonObject

        assertEquals("login-0001", json.get("messageId").asString)
        assertEquals("3.0", json.get("schemaVersion").asString)
        assertEquals("handheld_1", json.get("deviceId").asString)
        assertEquals("", json.get("operatorSessionId").asString)
        assertEquals("2026-07-16T08:00:00Z", json.get("timestampUtc").asString)
        assertEquals("operator1", json.get("username").asString)
        assertEquals("secret", json.get("password").asString)
    }

    @Test
    fun `an absent correlationKey is omitted rather than sent as null`() {
        val json = JsonParser.parseString(build(LoginPayload("operator1", "secret"))).asJsonObject
        assertFalse(json.has("correlationKey"))
    }

    @Test
    fun `a supplied correlationKey is included`() {
        val json = JsonParser.parseString(
            build(LoginPayload("operator1", "secret"), correlationKey = "COL_000123")
        ).asJsonObject
        assertEquals("COL_000123", json.get("correlationKey").asString)
    }

    @Test
    fun `a blank correlationKey is omitted rather than sent as empty string`() {
        val json = JsonParser.parseString(
            build(LoginPayload("operator1", "secret"), correlationKey = "")
        ).asJsonObject
        assertFalse(json.has("correlationKey"))
    }

    @Test
    fun `a whitespace-only correlationKey is omitted rather than sent`() {
        val json = JsonParser.parseString(
            build(LoginPayload("operator1", "secret"), correlationKey = "   ")
        ).asJsonObject
        assertFalse(json.has("correlationKey"))
    }

    @Test
    fun `an envelope-only request serializes to just the envelope`() {
        val json = JsonParser.parseString(build(EmptyPayload)).asJsonObject

        assertEquals("login-0001", json.get("messageId").asString)
        assertEquals("3.0", json.get("schemaVersion").asString)
        assertEquals(5, json.entrySet().size)
    }

    @Test
    fun `schema version always comes from MqttSchema`() {
        val json = JsonParser.parseString(build(EmptyPayload)).asJsonObject
        assertEquals(MqttSchema.VERSION, json.get("schemaVersion").asString)
    }

    @Test
    fun `a payload field never overwrites an envelope field`() {
        // Guard: a payload accidentally carrying its own deviceId must not win. The transport is
        // authoritative for envelope fields.
        val rogue = mapOf("deviceId" to "attacker_device", "username" to "operator1")
        val json = JsonParser.parseString(build(rogue)).asJsonObject
        assertEquals("handheld_1", json.get("deviceId").asString)
        assertEquals("operator1", json.get("username").asString)
    }

    @Test
    fun `a null payload field is omitted`() {
        data class Optional(val bagSizeOption: String?, val bagCount: Double?)
        val json = JsonParser.parseString(build(Optional(null, null))).asJsonObject
        assertFalse(json.has("bagSizeOption"))
        assertFalse(json.has("bagCount"))
        assertTrue(json.has("messageId"))
    }
}
