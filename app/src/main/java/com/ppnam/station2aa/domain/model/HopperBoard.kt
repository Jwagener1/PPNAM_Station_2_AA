package com.ppnam.station2aa.domain.model

/**
 * Contract v3.0 Hopper status. Enum constant names match the wire values exactly so Gson maps them
 * without a custom adapter — do not rename them to Kotlin casing conventions.
 */
enum class HopperState {
    /** Configured, active, and free for assignment. */
    Available,

    /** Has an active Hopper cycle. */
    InUse,

    /** Configured but disabled or unavailable for operational use. */
    Inactive,
}

/**
 * One Hopper on the contract's common status board.
 *
 * The board is mandatory in seven responses — job-card load, active job cards, every ingredient
 * scan, hopper overview, and Hopper cycle start/finish/force-close — so the operator can see live
 * availability at every decision point without a separate lookup. It always lists every configured
 * Hopper, including inactive equipment.
 *
 * The assignment fields are nullable because the contract's own examples show abbreviated boards in
 * some responses and full boards in others.
 */
data class HopperBoardEntry(
    val displayName: String = "",
    val machineCode: String = "",
    val status: HopperState = HopperState.Inactive,
    val isAvailable: Boolean = false,
    val cycleId: String? = null,
    val collectionId: String? = null,
    val preMixId: String? = null,
    val jobCardNumber: String? = null,
    val assignedAtUtc: String? = null,
    val assignedByOperatorId: String? = null,
    val assignedByDisplayName: String? = null,
    val assignedFromDevice: String? = null,
    val inactiveReason: String? = null,
)
