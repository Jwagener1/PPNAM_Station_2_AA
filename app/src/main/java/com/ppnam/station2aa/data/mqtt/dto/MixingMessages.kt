package com.ppnam.station2aa.data.mqtt.dto

/** `mixing_overview_requested` — both filters optional; Gson omits nulls per the contract. */
data class MixingOverviewPayload(
    val mixingArea: String? = null,
    val productionOrderDocumentNumber: String? = null,
)

/** Equipment `status`. */
object EquipmentStatus {
    const val AVAILABLE = "Available"
    const val IN_USE = "InUse"
    const val DISABLED = "Disabled"
}

data class EquipmentDto(
    val mixingArea: String = "",
    val equipmentRole: String = "",
    val machineCode: String = "",
    val displayName: String = "",
    val isEnabled: Boolean = false,
    val isAvailable: Boolean = false,
    /** [EquipmentStatus] — rendered verbatim, never inferred locally (§13.7). */
    val status: String = "",
    val productLayer: Int? = null,
    val currentCycleId: String? = null,
    val currentProductionOrderDocumentNumber: String? = null,

    /**
     * Whether THIS handheld may scan this machine right now.
     *
     * The contract's rule is absolute — "Android must not infer availability or scan permission
     * locally" — so this is the only thing that may gate the scan affordance.
     */
    val scanAllowed: Boolean = false,

    val currentMixBatchIds: List<String> = emptyList(),
    val validDestinationMachineCodes: List<String> = emptyList(),
    val routeDescription: String = "",
)

/** Mix `status`. 4.1 adds `Quarantined` for a force-closed mix. */
object MixStatus {
    const val READY_FOR_TRANSFER = "ReadyForTransfer"
    const val READY_FOR_PRODUCTION = "ReadyForProduction"
    const val QUARANTINED = "Quarantined"
    const val CANCELLED = "Cancelled"
}

/** `completionMode` on a mix. */
object CompletionMode {
    const val NORMAL = "Normal"
    const val FORCE_CLOSED = "ForceClosed"
}

data class ReadyMixDto(
    val mixBatchId: String = "",
    val collectionId: String = "",
    val mixingArea: String = "",
    val productionOrderDocumentNumber: String = "",
    val mixerCode: String = "",
    val mixerDisplayName: String = "",
    val productLayer: Int? = null,
    /** [MixStatus]. */
    val status: String = "",
    /** The ONLY legitimate source of destination choices (§13.8). */
    val validNextMachineCodes: List<String> = emptyList(),
    val nextStepDescription: String = "",

    // ---- 4.1 force-close quarantine ---------------------------------------------------------

    /** [CompletionMode]. */
    val completionMode: String? = null,
) {
    /**
     * Whether this mix may be sent to a production machine.
     *
     * Backend issue B2 was that a force-closed cycle still yielded a usable mix. 4.1 makes such a
     * mix `Quarantined` and "never assignable until an audited Manager/Admin Release or Discard",
     * so the app must not offer it as a source — the operator cannot clear it from the handheld.
     */
    val isAssignable: Boolean
        get() = status != MixStatus.QUARANTINED &&
            status != MixStatus.CANCELLED &&
            completionMode != CompletionMode.FORCE_CLOSED
}

data class ActiveCycleDto(
    val cycleId: String = "",
    val machineCode: String = "",
    val mixingArea: String = "",
    val equipmentRole: String = "",
    val productionOrderDocumentNumber: String = "",
    val collectionId: String? = null,
    val mixBatchIds: List<String> = emptyList(),
    val productionRunId: String? = null,
    val startedAtUtc: String = "",
    val startedByOperatorId: String = "",
)

data class ActiveRunDto(
    val productionRunId: String = "",
    val machineCode: String = "",
    val productionOrderDocumentNumber: String = "",
    val mixBatchIds: List<String> = emptyList(),
    val startedAtUtc: String = "",
)

/**
 * One collection in `readyCollections[]` that can start a mixer.
 *
 * One completed collection creates exactly one physical mix. The mixers it may legally start come
 * from [validMixerCodes] — server-decided, never inferred locally.
 */
data class ReadyCollectionDto(
    val collectionId: String = "",
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val productCode: String = "",
    val productName: String = "",
    val status: String = "",
    val validMixerCodes: List<String> = emptyList(),
    val nextAction: String = "",
)

/** `mixing_overview_result`, and the `areaStatus` embedded in every machine result (§8). */
data class MixingOverviewResponse(
    val mixingArea: String? = null,
    /**
     * Inferred only when everything in scope belongs to one production order; null when the scope
     * is empty or spans several. Echoes the request's value when one was supplied.
     */
    val productionOrderDocumentNumber: String? = null,
    val equipment: List<EquipmentDto> = emptyList(),
    /** Collections that can start a mixer. */
    val readyCollections: List<ReadyCollectionDto> = emptyList(),
    val activeCycles: List<ActiveCycleDto> = emptyList(),
    /**
     * Only PHYSICAL mixes whose mixer cycle has finished. The contract's warning is worth keeping
     * in sight: "Never synthesize a mix ID for a collection that has not started a mixer."
     */
    val readyMixes: List<ReadyMixDto> = emptyList(),
    val activeRuns: List<ActiveRunDto> = emptyList(),
)

data class LayerInputDto(
    val materialCode: String,
    val dosingQuantity: Double,
)

/**
 * `machine_cycle_start_requested`. Exactly one of [collectionId] (mixer start) or
 * [mixBatchIds] (drum/production start) travels.
 *
 * The retired v3 array fields never appear here by construction.
 */
data class MachineCycleStartPayload(
    val machineCode: String,
    val productionOrderDocumentNumber: String,
    val collectionId: String? = null,
    val mixBatchIds: List<String>? = null,
    val layerInputs: List<LayerInputDto>? = null,
)

/** `machine_cycle_finish_requested` — the exact scanned machine plus the server-issued cycle id (§9). */
data class MachineCycleFinishPayload(
    val machineCode: String,
    val cycleId: String,
)

/**
 * `machine_cycle_force_close_requested`.
 *
 * 4.1: the manager's password no longer travels here. They prove it in a SCRAM exchange scoped to
 * `Cycle:<cycleId>` / `machine_force_close`, and only the resulting single-use
 * [authorizationToken] is sent.
 *
 * The resulting mix is `Quarantined` and NOT assignable — see [ReadyMixDto.isAssignable].
 */
data class MachineCycleForceClosePayload(
    val machineCode: String,
    val cycleId: String,
    val authorizationToken: String,
    val auditReason: String,
)

/** `machine_cycle_result` — the unified §8 result for start, finish, and force-close. */
data class MachineCycleResultResponse(
    val action: String? = null,
    val mixingArea: String? = null,
    val equipmentRole: String? = null,
    val machineCode: String? = null,
    val cycleId: String? = null,
    val productionOrderDocumentNumber: String? = null,
    val collectionId: String? = null,
    val mixBatchId: String? = null,
    val productionRunId: String? = null,
    val affectedMixBatchIds: List<String> = emptyList(),
    val alreadyFinished: Boolean = false,
    val forceClosed: Boolean = false,
    /** [CompletionMode] — `ForceClosed` means the resulting mix is quarantined. */
    val completionMode: String? = null,
    val approverUserId: String? = null,
    val approverDisplayName: String? = null,
    val approverRole: String? = null,
    val sapIssueQueued: Boolean = false,
    val sapProductionOrderChanged: Boolean = false,

    val areaStatus: MixingOverviewResponse = MixingOverviewResponse(),
)
