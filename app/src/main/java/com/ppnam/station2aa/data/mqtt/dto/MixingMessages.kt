package com.ppnam.station2aa.data.mqtt.dto

/** `mixing_overview_requested` — both filters optional; Gson omits nulls per the contract. */
data class MixingOverviewPayload(
    val mixingArea: String? = null,
    val productionOrderDocumentNumber: String? = null,
)

data class EquipmentDto(
    val mixingArea: String = "",
    val equipmentRole: String = "",
    val machineCode: String = "",
    val displayName: String = "",
    val isEnabled: Boolean = false,
    val isAvailable: Boolean = false,
    /** Available | InUse | Disabled — rendered verbatim, never inferred locally (§13.7). */
    val status: String = "",
    val productLayer: Int? = null,
    val currentCycleId: String? = null,
    val currentProductionOrderDocumentNumber: String? = null,
    val currentMixBatchIds: List<String> = emptyList(),
    val validDestinationMachineCodes: List<String> = emptyList(),
    val routeDescription: String = "",
)

data class ReadyMixDto(
    val mixBatchId: String = "",
    val collectionId: String = "",
    val mixingArea: String = "",
    val productionOrderDocumentNumber: String = "",
    val mixerCode: String = "",
    val mixerDisplayName: String = "",
    val productLayer: Int? = null,
    val status: String = "",
    val plannedDestinationMachineCode: String? = null,
    /** The ONLY legitimate source of destination choices (§13.8). */
    val validNextMachineCodes: List<String> = emptyList(),
    val nextStepDescription: String = "",
)

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

/** `mixing_overview_result`, and the `areaStatus` embedded in every machine result (§8). */
data class MixingOverviewResponse(
    val mixingArea: String? = null,
    val productionOrderDocumentNumber: String? = null,
    val equipment: List<EquipmentDto> = emptyList(),
    val activeCycles: List<ActiveCycleDto> = emptyList(),
    val readyMixes: List<ReadyMixDto> = emptyList(),
    val activeRuns: List<ActiveRunDto> = emptyList(),
)

data class LayerInputDto(
    val materialCode: String,
    val dosingQuantity: Double,
)

/**
 * `machine_cycle_start_requested`. Exactly one of [collectionId] (mixer start) or
 * [mixBatchIds] (drum/production start) travels; [layerInputs] only on a Rajoo mixer.
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

/** `machine_cycle_force_close_requested` — same identity plus inline Manager/Admin approval. */
data class MachineCycleForceClosePayload(
    val machineCode: String,
    val cycleId: String,
    val managerUsername: String,
    val managerPassword: String,
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
    val approverUserId: String? = null,
    val approverDisplayName: String? = null,
    val approverRole: String? = null,
    val sapIssueQueued: Boolean = false,
    val sapProductionOrderChanged: Boolean = false,
    val areaStatus: MixingOverviewResponse = MixingOverviewResponse(),
)
