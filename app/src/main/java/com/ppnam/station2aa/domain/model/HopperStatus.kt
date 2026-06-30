package com.ppnam.station2aa.domain.model

data class HopperStatus(
    val hopperCode: String,
    val status: HopperAvailability,
    val assignedTo: String? = null
)

enum class HopperAvailability { AVAILABLE, IN_USE, OFFLINE }
