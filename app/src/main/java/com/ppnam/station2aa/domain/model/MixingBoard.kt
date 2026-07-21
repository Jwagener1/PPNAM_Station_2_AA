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

data class Equipment(
    val machineCode: String,
    val displayName: String,
    val area: MixingArea?,
    /** Mixer | Transfer | ProductionMachine — pass-through; unknown roles tolerated. */
    val role: String,
    val isEnabled: Boolean,
    val isAvailable: Boolean,
    val status: String,
    val productLayer: Int?,
    val currentCycleId: String?,
    val currentJobCardNumber: String?,
    val currentMixBatchIds: List<String>,
    val validDestinationMachineCodes: List<String>,
    val routeDescription: String,
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
) {
    companion object {
        val EMPTY = AreaOverview(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** A ReadyForMixing collection from the active-job-cards list — a mixer start's source. */
data class ReadyCollection(
    val collectionId: String,
    val jobCardNumber: String,
    val productName: String,
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
 * The outcome of one machine-cycle operation. Both decided outcomes carry the embedded
 * areaStatus — a rejected start still refreshes the board (§8); [Failed] means Station 2
 * never decided (timeout/transport) and carries nothing.
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
    ) : MachineCycleOutcome()

    data class Rejected(
        val errorCode: ErrorCode?,
        val reason: String,
        val areaStatus: AreaOverview,
    ) : MachineCycleOutcome()

    data class Failed(val message: String) : MachineCycleOutcome()
}
