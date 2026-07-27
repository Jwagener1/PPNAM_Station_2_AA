package com.ppnam.station2aa.domain.model

import com.ppnam.station2aa.data.mqtt.ErrorCode

/** The five fixed v4.0 mixing areas (§6). Server-authoritative; never extended locally. */
enum class MixingArea(val wire: String, val display: String) {
    Dolci("DolciBulkMixing", "DOLCI"),
    Main("MainMixingRoom", "Main Mixing Room"),
    Jandi("JandiBulkMixing", "JANDI"),
    Mackie("MackieBulkMixing", "Mackie"),
    Rajoo("RajooMachineMixing", "Rajoo");

    companion object {
        fun fromWire(value: String?): MixingArea? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The server's three equipment roles (§6). Pass-through strings — unknown roles are tolerated —
 * but these three drive the two-phase dispatch: a [Mixer] starts from a collection, a [Transfer]
 * (the JANDI drum) starts a transfer cycle from a finished mix, and a [ProductionMachine] is a
 * destination that may be committed ONLY via `mix_destination_assignment_requested` (§8).
 */
object EquipmentRole {
    const val MIXER = "Mixer"
    const val TRANSFER = "Transfer"
    const val PRODUCTION_MACHINE = "ProductionMachine"
}

data class Equipment(
    val machineCode: String,
    val displayName: String,
    val area: MixingArea?,
    /** Mixer | Transfer | ProductionMachine — pass-through; unknown roles tolerated. */
    val role: String,
    val isEnabled: Boolean,
    val isAvailable: Boolean,
    /** Available | Reserved | InUse | Disabled. */
    val status: String,
    val productLayer: Int?,
    val currentCycleId: String?,
    val currentJobCardNumber: String?,
    val currentMixBatchIds: List<String>,
    val validDestinationMachineCodes: List<String>,
    val routeDescription: String,
    /** 4.1: the plan reserving this machine, when reserved. */
    val mixPlanId: String? = null,
    val planItemStatus: String? = null,
    val reservationCollectionId: String? = null,
    val reservationJobCardNumber: String? = null,
    /**
     * 4.1: whether this handheld may scan this machine now. Server-decided.
     *
     * Not the same as [isAvailable]: a mixer reserved for the collection in hand is unavailable to
     * everyone else yet is precisely what this operator should scan. The contract forbids inferring
     * either locally, so this is the only thing that may gate the scan affordance.
     */
    val scanAllowed: Boolean = false,
)

data class ReadyMix(
    val mixBatchId: String,
    val collectionId: String,
    val area: MixingArea?,
    val jobCardNumber: String,
    val mixerCode: String,
    val mixerDisplayName: String,
    val status: String,
    val validNextMachineCodes: List<String>,
    val nextStepDescription: String,
    /** 4.1: `ForceClosed` marks a quarantined mix. */
    val completionMode: String? = null,
    /**
     * 4.1: false for a force-closed/quarantined mix, which may not be sent to production until a
     * Manager/Admin Release or Discard at the desk. Backend issue B2.
     */
    val isAssignable: Boolean = true,
)

/** One mixer reserved in a collection's plan. */
data class MixerPlanItem(
    val planItemId: String,
    val area: MixingArea?,
    val machineCode: String,
    val mixerDisplayName: String,
    val fixedDestinationMachineCode: String?,
    /** Reserved | Started | ReadyForDestination | Completed. */
    val status: String,
    val mixBatchId: String?,
    val cycleId: String?,
)

/** A durable link between a completed mix and a production machine, including history. */
data class MixDestination(
    val mixBatchId: String,
    val machineCode: String,
    val productionRunId: String,
    val linkStatus: String,
    val runStatus: String,
)

data class ActiveCycle(
    val cycleId: String,
    val machineCode: String,
    val area: MixingArea?,
    val role: String,
    val jobCardNumber: String,
    val collectionId: String?,
    val mixBatchIds: List<String>,
    val productionRunId: String?,
    val startedAtUtc: String,
    val startedByOperatorId: String,
)

data class ActiveRun(
    val productionRunId: String,
    val machineCode: String,
    val jobCardNumber: String,
    val mixBatchIds: List<String>,
    val startedAtUtc: String,
)

data class AreaOverview(
    val equipment: List<Equipment>,
    val activeCycles: List<ActiveCycle>,
    val readyMixes: List<ReadyMix>,
    val activeRuns: List<ActiveRun>,
    /**
     * 4.1: collections that can start a mixer, with their plan state.
     *
     * In 4.0 the board derived its mixer sources from the active-job-cards list. 4.1 makes the
     * overview authoritative for this, because only it knows the plan and its reservations.
     */
    val readyCollections: List<ReadyCollection> = emptyList(),
    /** 4.1: destination-link overview and history. */
    val mixDestinations: List<MixDestination> = emptyList(),
) {
    companion object {
        val EMPTY = AreaOverview(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * A collection that can start a mixer, plus its cross-area mixer plan.
 *
 * 4.1 replaced "a completed collection starts one mixer" with "a completed collection has a saved
 * plan of several unique mixers, possibly in different areas, scannable in any order". The plan is
 * saved in Station 2 (WPF) — the handheld only reads it and scans against it.
 */
data class ReadyCollection(
    val collectionId: String,
    val jobCardNumber: String,
    val productName: String,
    val productCode: String = "",
    /** ReadyForMixing (unplanned) | MixingPlanned (plan saved, reservations outstanding). */
    val status: String = "",
    val mixPlanId: String? = null,
    /** Saved | InProgress | Completed; null before a plan exists. */
    val mixPlanStatus: String? = null,
    val plannedMixerCount: Int = 0,
    val startedMixerCount: Int = 0,
    val remainingMixerCount: Int = 0,
    val plannedMixerCodes: List<String> = emptyList(),
    val startedMixerCodes: List<String> = emptyList(),
    /** The exact codes still to scan — the operator's next targets. */
    val remainingMixerCodes: List<String> = emptyList(),
    val planItems: List<MixerPlanItem> = emptyList(),
    val validMixerCodes: List<String> = emptyList(),
    val nextAction: String = "",
) {
    /** True once Station 2 has a saved plan. Until then the operator must save one at the desk. */
    val hasSavedPlan: Boolean get() = !mixPlanId.isNullOrBlank()

    /**
     * True when this collection is waiting on a plan being saved in Station 2. The handheld cannot
     * create one, so the only honest thing to show is "save the plan at the desk first".
     */
    val needsPlan: Boolean get() = !hasSavedPlan
}

/** One `{ machineCode, productionRunId }` pair from an accepted destination assignment. */
data class AssignedDestination(
    val machineCode: String,
    val productionRunId: String,
)

/** One collected manual line of a collection — the Rajoo dose sheet's row source. */
data class CollectedMaterial(
    val materialCode: String,
    val materialName: String,
    val collectedQty: Double,
)

data class LayerInput(
    val materialCode: String,
    val dosingQuantity: Double,
)

/**
 * The outcome of one machine-cycle operation. [Accepted] always carries the embedded
 * areaStatus — a business rejection also embeds it (§8), since every area has equipment.
 * [Rejected.areaStatus] is null when the rejection was envelope/session-level (session_required,
 * message_expired, client_upgrade_required, message_id_reused…) and so carried no operational
 * area data — the board must keep its current picture rather than overwrite it with emptiness.
 * [Failed] means Station 2 never decided (timeout/transport) and carries nothing.
 */
sealed class MachineCycleOutcome {
    data class Accepted(
        val action: String?,
        val machineCode: String,
        val cycleId: String?,
        val mixBatchId: String?,
        val productionRunId: String?,
        val affectedMixBatchIds: List<String>,
        val alreadyFinished: Boolean,
        val forceClosed: Boolean,
        val approverDisplayName: String?,
        val areaStatus: AreaOverview,
        /** 4.1 plan progress — how much of this collection's mixer plan is left to scan. */
        val planProgress: MixPlanProgress = MixPlanProgress.NONE,
        /**
         * 4.1 Phase 2: the `{ machineCode, productionRunId }` pairs from a destination assignment.
         * Empty for a mixer/drum cycle op; populated only when this outcome came from
         * `mix_destination_assignment_requested`.
         */
        val assignedDestinations: List<AssignedDestination> = emptyList(),
    ) : MachineCycleOutcome()

    data class Rejected(
        val errorCode: ErrorCode?,
        val reason: String,
        val areaStatus: AreaOverview?,
    ) : MachineCycleOutcome()

    data class Failed(val message: String) : MachineCycleOutcome()
}

/**
 * How far through a collection's mixer plan this operation left us.
 *
 * The operator's actual question after a mixer start is "which machine do I go to next?", and in
 * 4.1 that can be another mixer for the same collection rather than "go back and finish this one".
 * [remainingMixerCodes] answers it exactly; the counts are for the progress line.
 */
data class MixPlanProgress(
    val mixPlanId: String?,
    val planItemId: String?,
    val planItemStatus: String?,
    val mixPlanStatus: String?,
    val plannedMixerCount: Int,
    val startedMixerCount: Int,
    val remainingMixerCount: Int,
    val plannedMixerCodes: List<String>,
    val remainingMixerCodes: List<String>,
    val plannedDestinationMachineCodes: List<String>,
    val remainingDestinationMachineCodes: List<String>,
    val productionRunIds: List<String>,
) {
    /** True when this collection still has planned mixers the operator has not started. */
    val hasRemainingMixers: Boolean get() = remainingMixerCodes.isNotEmpty()

    companion object {
        /** No plan data on the response — a drum/production start, or a pre-4.1 server. */
        val NONE = MixPlanProgress(
            mixPlanId = null,
            planItemId = null,
            planItemStatus = null,
            mixPlanStatus = null,
            plannedMixerCount = 0,
            startedMixerCount = 0,
            remainingMixerCount = 0,
            plannedMixerCodes = emptyList(),
            remainingMixerCodes = emptyList(),
            plannedDestinationMachineCodes = emptyList(),
            remainingDestinationMachineCodes = emptyList(),
            productionRunIds = emptyList(),
        )
    }
}
