package com.ppnam.station2aa.domain.model

sealed class IngredientValidationResult {
    data class Valid(val bomLine: BomLine) : IngredientValidationResult()
    data class Invalid(val tagId: String, val reason: String) : IngredientValidationResult()
}
