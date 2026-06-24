package com.ppnam.station2aa.domain.model

import java.time.Instant

data class AllocationRecord(
    val preMixId: String,
    val machineCode: String,
    val allocatedAt: Instant
)
