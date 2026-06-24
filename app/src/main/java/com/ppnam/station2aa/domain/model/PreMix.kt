package com.ppnam.station2aa.domain.model

import java.time.Instant

data class PreMix(
    val id: String,
    val jobCardNo: String,
    val mixerCode: String,
    val ingredients: List<ScannedIngredient>,
    val status: PreMixStatus,
    val createdAt: Instant
)

data class ScannedIngredient(
    val tagId: String,
    val itemCode: String,
    val qty: Double
)

enum class PreMixStatus { IN_PROGRESS, COMPLETE, ALLOCATED }
