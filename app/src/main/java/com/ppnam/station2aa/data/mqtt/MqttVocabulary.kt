package com.ppnam.station2aa.data.mqtt

/**
 * Contract v3.0 `errorCode`. A value class rather than an enum: the contract defines these as
 * "shared across message families", so message-specific codes may also arrive. An unknown code
 * must pass through intact rather than fail the parse.
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
        val MACHINE_UNAVAILABLE = ErrorCode("machine_unavailable")
        val VALIDATION_FAILED = ErrorCode("validation_failed")
        val MESSAGE_ID_REUSED = ErrorCode("message_id_reused")
        val SERVICE_UNAVAILABLE = ErrorCode("service_unavailable")
    }
}

/**
 * Contract v3.0 `nextAction`. Guidance for the scanner UI, never authorization.
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
        val CHOOSE_DESTINATION = NextAction("choose_destination")
        val ASSIGN_OR_FINISH_HOPPER = NextAction("assign_or_finish_hopper")
        val SCAN_SAME_MACHINE_TO_FINISH = NextAction("scan_same_machine_to_finish")
        val ALLOCATE_PREMIX = NextAction("allocate_premix")
        val REVIEW_ALLOCATION = NextAction("review_allocation")
        val COMPLETE_STATION2_WORK = NextAction("complete_station2_work")
    }
}
