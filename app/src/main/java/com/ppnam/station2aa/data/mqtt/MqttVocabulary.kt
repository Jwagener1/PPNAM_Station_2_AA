package com.ppnam.station2aa.data.mqtt

/**
 * Contract v4.0 `errorCode`. A value class rather than an enum: codes are shared across message
 * families and message-specific codes may also arrive. An unknown code must pass through intact
 * rather than fail the parse.
 */
@JvmInline
value class ErrorCode(val raw: String) {
    companion object {
        val INVALID_JSON = ErrorCode("invalid_json")
        val INVALID_ENVELOPE = ErrorCode("invalid_envelope")
        val UNSUPPORTED_SCHEMA = ErrorCode("unsupported_schema")
        val DEVICE_MISMATCH = ErrorCode("device_mismatch")
        val DEVICE_NOT_CONFIGURED = ErrorCode("device_not_configured")
        val MESSAGE_EXPIRED = ErrorCode("message_expired")
        val SESSION_REQUIRED = ErrorCode("session_required")
        val PERMISSION_DENIED = ErrorCode("permission_denied")
        val NOT_FOUND = ErrorCode("not_found")
        val STATE_CONFLICT = ErrorCode("state_conflict")
        val VALIDATION_FAILED = ErrorCode("validation_failed")
        val MESSAGE_ID_REUSED = ErrorCode("message_id_reused")
        val SERVICE_UNAVAILABLE = ErrorCode("service_unavailable")

        // v4.0 §10 — stable Mixing codes. SP4a consumes CLIENT_UPGRADE_REQUIRED;
        // SP4b's mixing UI branches on the rest.
        val CLIENT_UPGRADE_REQUIRED = ErrorCode("client_upgrade_required")
        val INVALID_MIXING_AREA = ErrorCode("invalid_mixing_area")
        val LEGACY_REQUEST_SHAPE = ErrorCode("legacy_request_shape")
        val UNKNOWN_OR_DISABLED_EQUIPMENT = ErrorCode("unknown_or_disabled_equipment")
        val EQUIPMENT_IN_USE = ErrorCode("equipment_in_use")
        val CYCLE_MISMATCH = ErrorCode("cycle_mismatch")
        val SOURCE_NOT_FOUND = ErrorCode("source_not_found")
        val SOURCE_NOT_READY = ErrorCode("source_not_ready")
        val SOURCE_ALREADY_ASSIGNED = ErrorCode("source_already_assigned")
        val JOB_CARD_MISMATCH = ErrorCode("job_card_mismatch")
        val INVALID_ROUTE = ErrorCode("invalid_route")
        val DRUM_CYCLE_REQUIRED = ErrorCode("drum_cycle_required")
        val INVALID_LAYER_INPUTS = ErrorCode("invalid_layer_inputs")
    }
}

/**
 * Contract v4.0 `nextAction`. Guidance for the scanner UI, never authorization. An empty value
 * means "no forced navigation".
 */
@JvmInline
value class NextAction(val raw: String) {
    companion object {
        val NONE = NextAction("")
        val LOGIN = NextAction("login")
        val SCAN_JOB_CARD = NextAction("scan_job_card")
        val ACTIVE_JOB_CARDS = NextAction("active_job_cards")
        val SCAN_INGREDIENT = NextAction("scan_ingredient")
        val RECOVER_HOLDING = NextAction("recover_holding")
        val RETRY_WITH_MANAGER_APPROVAL = NextAction("retry_with_manager_approval")
        val START_MIXING = NextAction("start_mixing")
        val SELECT_COLLECTION_MIX_OR_MACHINE = NextAction("select_collection_mix_or_machine")
        val SCAN_SAME_MACHINE_TO_FINISH = NextAction("scan_same_machine_to_finish")
        val UPGRADE_READER_FOR_MIXING = NextAction("upgrade_reader_for_mixing")
    }
}
