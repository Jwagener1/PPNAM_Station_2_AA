package com.ppnam.station2aa.data.mqtt.dto

/** `mixing_overview_requested` — both filters optional; Gson omits nulls per the contract. */
data class MixingOverviewPayload(
    val mixingArea: String? = null,
    val productionOrderDocumentNumber: String? = null,
)

/** Equipment `status`. 4.1 adds `Reserved` — held by a saved mixer plan for one collection. */
object EquipmentStatus {
    const val AVAILABLE = "Available"
    const val RESERVED = "Reserved"
    const val IN_USE = "InUse"
    const val DISABLED = "Disabled"
}

/** Status of one item within a collection's mixer plan. */
object PlanItemStatus {
    const val RESERVED = "Reserved"
    const val STARTED = "Started"
    const val READY_FOR_DESTINATION = "ReadyForDestination"
    const val COMPLETED = "Completed"
}

/** Status of the plan as a whole. */
object MixPlanStatus {
    const val SAVED = "Saved"
    const val IN_PROGRESS = "InProgress"
    const val COMPLETED = "Completed"
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

    // ---- 4.1 cross-area mixer plans ---------------------------------------------------------

    /** The plan holding this machine, when it is reserved. */
    val mixPlanId: String? = null,
    val planItemStatus: String? = null,
    val reservationCollectionId: String? = null,
    val reservationJobCardNumber: String? = null,
    /**
     * Whether THIS handheld may scan this machine right now.
     *
     * Deliberately separate from [isAvailable]: a mixer reserved by the collection in hand is not
     * "available" (nobody else may take it) yet is exactly the machine this operator should scan.
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
    val plannedDestinationMachineCode: String? = null,
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
 * One mixer reserved within a collection's plan.
 *
 * 4.1 replaced "a completed collection starts one mixer" with "a completed collection has a saved
 * plan of several unique mixers, possibly in different areas, scannable in any order". Each
 * accepted planned scan creates its own `MIX_######` and cycle and starts only that item.
 */
data class MixerPlanItemDto(
    val planItemId: String = "",
    val mixingArea: String = "",
    val machineCode: String = "",
    val mixerDisplayName: String = "",
    /** Preselected for Mackie/Rajoo; null where the destination is chosen later (e.g. DOLCI). */
    val fixedDestinationMachineCode: String? = null,
    /** [PlanItemStatus]. */
    val status: String = "",
    /** Null until this item is started — never synthesize one (§7). */
    val mixBatchId: String? = null,
    val cycleId: String? = null,
)

/**
 * One collection in `readyCollections[]`: either unplanned and `ReadyForMixing`, or
 * `MixingPlanned` with reservations still outstanding.
 *
 * Before a plan is saved, [validMixerCodes] is scoped to enabled unreserved mixers in the
 * requested area and [nextAction] is `save_mixer_plan_in_station_2` — the plan is saved in Station
 * 2 (WPF), never on the handheld. After save, the remaining reserved codes are listed and the
 * collection stays here until no reservation remains.
 */
data class ReadyCollectionDto(
    val collectionId: String = "",
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val productCode: String = "",
    val productName: String = "",
    /** ReadyForMixing | MixingPlanned. */
    val status: String = "",
    val mixPlanId: String? = null,
    /** [MixPlanStatus]; null before a plan is saved. */
    val mixPlanStatus: String? = null,
    val plannedMixerCount: Int = 0,
    val startedMixerCount: Int = 0,
    val remainingMixerCount: Int = 0,
    val plannedMixerCodes: List<String> = emptyList(),
    val startedMixerCodes: List<String> = emptyList(),
    val remainingMixerCodes: List<String> = emptyList(),
    val mixerPlanItems: List<MixerPlanItemDto> = emptyList(),
    val validMixerCodes: List<String> = emptyList(),
    val nextAction: String = "",
) {
    /** True once Station 2 has a saved plan for this collection. */
    val hasSavedPlan: Boolean get() = !mixPlanId.isNullOrBlank()
}

/**
 * A durable link between a completed mix and a production machine.
 *
 * Includes active, consumed, returned and transferred links — completed entries are history and,
 * per the contract, "do not make readyMixes or activeRuns non-empty".
 */
data class MixDestinationDto(
    val mixBatchId: String = "",
    val machineCode: String = "",
    val productionRunId: String = "",
    /** Active | Consumed | Returned | Transferred. */
    val linkStatus: String = "",
    val runStatus: String = "",
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
    /** 4.1: collections that can start a mixer, with their plan state. */
    val readyCollections: List<ReadyCollectionDto> = emptyList(),
    val activeCycles: List<ActiveCycleDto> = emptyList(),
    /**
     * Only PHYSICAL mixes whose mixer cycle has finished. The contract's warning is worth keeping
     * in sight: "Never synthesize a mix ID for a collection that has not started a mixer."
     */
    val readyMixes: List<ReadyMixDto> = emptyList(),
    val activeRuns: List<ActiveRunDto> = emptyList(),
    /** 4.1: destination-link overview/history, including completed links. */
    val mixDestinations: List<MixDestinationDto> = emptyList(),
)

data class LayerInputDto(
    val materialCode: String,
    val dosingQuantity: Double,
)

/**
 * `machine_cycle_start_requested`. Exactly one of [collectionId] (mixer start) or
 * [mixBatchIds] (drum/production start) travels.
 *
 * [layerInputs] is Rajoo-only and, since 4.1, OPTIONAL: dosing is saved per layer mixer in the
 * Station 2 plan, and omitting it uses the stored 1–5 lines. Supplying a non-empty list that does
 * not exactly match the saved plan is rejected with `invalid_planned_layer_inputs` — so sending
 * nothing is both simpler and safer than echoing back what we think the plan says.
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

/**
 * `mix_destination_assignment_requested` — 4.1.
 *
 * One completed mix to one or more distinct compatible production machines. No quantity is
 * supplied per destination; the whole set is revalidated atomically, so a single invalid machine
 * rejects the request rather than partially assigning.
 */
data class MixDestinationAssignmentPayload(
    val mixBatchId: String,
    val machineCodes: List<String>,
)

/** One `{ machineCode, productionRunId }` pair from an accepted assignment. */
data class AssignedDestinationDto(
    val machineCode: String = "",
    val productionRunId: String = "",
)

/** `mix_destination_assignment_result`. */
data class MixDestinationAssignmentResultResponse(
    val mixBatchId: String = "",
    val assignedDestinations: List<AssignedDestinationDto> = emptyList(),
    val areaStatus: MixingOverviewResponse = MixingOverviewResponse(),
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

    // ---- 4.1 plan identity and progress -----------------------------------------------------

    val mixPlanId: String? = null,
    val planItemId: String? = null,
    /** [PlanItemStatus] of the item this scan touched — only that item changes. */
    val planItemStatus: String? = null,
    /** [MixPlanStatus] of the plan as a whole. */
    val mixPlanStatus: String? = null,
    val plannedMixerCount: Int = 0,
    val startedMixerCount: Int = 0,
    val remainingMixerCount: Int = 0,
    val plannedMixerCodes: List<String> = emptyList(),
    /** Exact codes still to be scanned — the operator's next targets. */
    val remainingMixerCodes: List<String> = emptyList(),
    val plannedDestinationMachineCodes: List<String> = emptyList(),
    val remainingDestinationMachineCodes: List<String> = emptyList(),
    val productionRunIds: List<String> = emptyList(),

    val areaStatus: MixingOverviewResponse = MixingOverviewResponse(),
)
