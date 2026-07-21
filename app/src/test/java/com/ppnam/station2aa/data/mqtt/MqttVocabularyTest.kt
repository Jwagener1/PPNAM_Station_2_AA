package com.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MqttVocabularyTest {

    @Test
    fun `error code constants carry their contract wire values`() {
        assertEquals("invalid_json", ErrorCode.INVALID_JSON.raw)
        assertEquals("session_required", ErrorCode.SESSION_REQUIRED.raw)
        assertEquals("message_expired", ErrorCode.MESSAGE_EXPIRED.raw)
        assertEquals("message_id_reused", ErrorCode.MESSAGE_ID_REUSED.raw)
        assertEquals("permission_denied", ErrorCode.PERMISSION_DENIED.raw)
        assertEquals("unsupported_schema", ErrorCode.UNSUPPORTED_SCHEMA.raw)
    }

    @Test
    fun `error codes compare by value`() {
        assertEquals(ErrorCode.SESSION_REQUIRED, ErrorCode("session_required"))
        assertNotEquals(ErrorCode.SESSION_REQUIRED, ErrorCode.NOT_FOUND)
    }

    @Test
    fun `an unknown error code is preserved rather than rejected`() {
        val unknown = ErrorCode("some_future_backend_code")
        assertEquals("some_future_backend_code", unknown.raw)
    }

    @Test
    fun `next action constants carry their contract wire values`() {
        assertEquals("", NextAction.NONE.raw)
        assertEquals("login", NextAction.LOGIN.raw)
        assertEquals("recover_holding", NextAction.RECOVER_HOLDING.raw)
        assertEquals("start_mixing", NextAction.START_MIXING.raw)
        assertEquals("retry_with_manager_approval", NextAction.RETRY_WITH_MANAGER_APPROVAL.raw)
        assertEquals("select_collection_mix_or_machine", NextAction.SELECT_COLLECTION_MIX_OR_MACHINE.raw)
        assertEquals("upgrade_reader_for_mixing", NextAction.UPGRADE_READER_FOR_MIXING.raw)
    }

    @Test
    fun `next actions compare by value`() {
        assertEquals(NextAction.LOGIN, NextAction("login"))
        assertNotEquals(NextAction.LOGIN, NextAction.NONE)
    }

    @Test
    fun `an unknown next action is preserved rather than rejected`() {
        assertEquals("do_a_new_thing", NextAction("do_a_new_thing").raw)
    }
}
