package com.ppnam.station2aa.data.mqtt

/**
 * Contract v4.1 `errorCode`. A value class rather than an enum: codes are shared across message
 * families and message-specific codes may also arrive. An unknown code must pass through intact
 * rather than fail the parse.
 *
 * 4.1 promises these are "stable lowercase symbolic" values — backend issue B5 was a GUID arriving
 * here instead, which is why nothing downstream may assume a code it doesn't recognise is a bug.
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

        // v4.0 §10 — stable Mixing codes.
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

        // v4.1 authentication. The app can only provoke this by sending a `password` or
        // `managerPassword` property on a 4.1 message — i.e. it is a build defect, not an
        // operator error, and must never be shown as "wrong password".
        val PLAINTEXT_CREDENTIALS_FORBIDDEN = ErrorCode("plaintext_credentials_forbidden")

        // v4.1 keyset paging. Discard accumulated pages and re-request page one.
        val PAGE_CURSOR_STALE = ErrorCode("page_cursor_stale")

        // v4.1 cross-area mixer plans. The plan is saved in Station 2 (WPF), never on the
        // handheld, so every one of these is resolved by refreshing or by desk-side action.
        val MIXER_PLAN_REQUIRED = ErrorCode("mixer_plan_required")
        val MIXER_NOT_IN_PLAN = ErrorCode("mixer_not_in_plan")
        val MIXER_RESERVED = ErrorCode("mixer_reserved")
        val MIX_PLAN_LOCKED = ErrorCode("mix_plan_locked")
        val INVALID_PLANNED_LAYER_INPUTS = ErrorCode("invalid_planned_layer_inputs")
        val INVALID_PLANNED_DESTINATION = ErrorCode("invalid_planned_destination")
        val DESTINATION_BUSY = ErrorCode("destination_busy")
        val MIX_CYCLE_NOT_ACTIVE = ErrorCode("mix_cycle_not_active")
        val DESTINATION_ASSIGNMENT_LOCKED = ErrorCode("destination_assignment_locked")

        // v4.1 Station 3 master-batch capture.
        val STATION3_UNAVAILABLE = ErrorCode("station3_unavailable")
        val MASTER_BATCH_LABEL_INVALID = ErrorCode("master_batch_label_invalid")
        val MASTER_BATCH_MAPPING_MISSING = ErrorCode("master_batch_mapping_missing")
        val UNSUPPORTED_MASTER_BATCH_UOM = ErrorCode("unsupported_master_batch_uom")
        val MASTER_BATCH_ALREADY_COLLECTED = ErrorCode("master_batch_already_collected")
        val SOURCE_BARCODE_REQUIRED = ErrorCode("source_barcode_required")
        val CONFLICTING_SOURCE_BARCODES = ErrorCode("conflicting_source_barcodes")
    }
}

/**
 * Contract v4.1 `nextAction`. Guidance for the scanner UI, never authorization. An empty value
 * means "no forced navigation".
 *
 * One 4.1 action is parameterised: `scan_reserved_mixer:JAN-MIX-01,MXR-02` carries the remaining
 * reserved mixer codes after the colon. Use [ScanReservedMixer] to read it rather than comparing
 * the raw string, which will never match a constant.
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

        // v4.1 collection next actions (§ customer-confirmed extension).
        val CONTINUE_COLLECTING = NextAction("continue_collecting")
        val AWAIT_MANAGER_APPROVAL = NextAction("await_manager_approval")
        val RESCAN_APPROVED_MATERIAL = NextAction("rescan_approved_material")

        // v4.1 active-jobs invalidation push.
        val REFRESH_ACTIVE_JOBS = NextAction("refresh_active_jobs")

        // v4.1 mixer plans.
        val SAVE_MIXER_PLAN_IN_STATION_2 = NextAction("save_mixer_plan_in_station_2")
        val SCAN_SAME_MACHINE_TO_FINISH_OR_SCAN_NEXT_PLANNED_MIXER =
            NextAction("scan_same_machine_to_finish_or_scan_next_planned_mixer")

        /** Prefix of the parameterised reserved-mixer action. */
        const val SCAN_RESERVED_MIXER_PREFIX = "scan_reserved_mixer:"
    }

    /**
     * The remaining reserved mixer codes when this is a `scan_reserved_mixer:` action, else null.
     *
     * Null and an empty list mean different things: null is "this isn't that action at all",
     * whereas an empty list would be a malformed action that named no mixers.
     */
    val scanReservedMixerCodes: List<String>?
        get() = raw.takeIf { it.startsWith(SCAN_RESERVED_MIXER_PREFIX) }
            ?.removePrefix(SCAN_RESERVED_MIXER_PREFIX)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
}
