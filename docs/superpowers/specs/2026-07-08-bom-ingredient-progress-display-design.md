# BOM Ingredient Progress Display — Design Spec

**Date:** 2026-07-08
**Scope:** Display-only fix on `IngredientScanScreen`'s per-ingredient progress bar/label, plus threading `uomCode` through the domain layer. No MQTT contract changes beyond the already-agreed `uomCode` value format, no changes to the ingredient-scanning/exception-approval flow (tracked separately for a future rework).

---

## Context

The `bom_loaded` response already carries decimal `plannedQuantity`, `issuedQuantity`, `remainingQuantity`, and `uomCode` per BOM line (see `RFID_MQTT_CONTRACT.md` § job card lookup). Two problems today:

1. `uomCode` is read off `BomLineResponse` but dropped entirely when `MixingUseCase.lookupJob` maps it to the domain `BomLine` — never reaches the UI.
2. `IngredientScanScreen`'s per-line progress bar and label are driven by a live RFID-scan-count (`scannedIngredients.count { itemCode == bomLine.itemCode }` vs `requiredQty.toInt()`), not by the decimal quantities already present on `BomLine` (`requiredQty` = plannedQuantity, `scannedQty` = issuedQuantity, `remainingQty` = remainingQuantity).

Backend is separately updating `uomCode` to send a human-readable unit (e.g. `"KG"`, `"TON"`) instead of an internal SAP code — `RFID_MQTT_CONTRACT.md` has been updated to reflect this (field name unchanged, value format changed).

## Out of Scope

The RFID ingredient-scanning flow (`MixingUseCase.validateIngredient`, `ScannedIngredient`, the scan-count-based "satisfied" checkmark/card-color/proceed-button gating) is **not** touched by this change — it's built against a stale placeholder action and will be migrated to the real `ingredient_scanned`/`ingredient_scan_result` contract (pallet scan + bag-size/count, live per-scan progress, manager-approval rework) as a separate, larger project.

## Design

### 1. `BomLine` gains a `uom` field

`app/src/main/java/com/ppnam/station2aa/domain/model/ProductionOrder.kt`:

```kotlin
data class BomLine(
    val itemCode: String,
    val itemName: String,
    val requiredQty: Double,
    val scannedQty: Double = 0.0,
    val remainingQty: Double = 0.0,
    val uom: String = "",
    val valid: Boolean = true,
    val reason: String? = null
) {
    val isFullyAllocated: Boolean get() = remainingQty <= 0.0
}
```

### 2. `MixingUseCase.lookupJob` maps `uomCode` through

```kotlin
BomLine(
    itemCode = line.materialCode,
    itemName = line.materialName,
    requiredQty = line.plannedQuantity,
    scannedQty = line.issuedQuantity,
    remainingQty = line.remainingQuantity,
    uom = line.uomCode
)
```

### 3. `IngredientScanScreen` per-line card

Current (count-based):
```kotlin
val fraction = (scannedCount.toFloat() / required.toFloat()).coerceIn(0f, 1f)
...
text = if (bomLine.isFullyAllocated) "Fully Allocated" else "$scannedCount / $required"
```

New (quantity-based, from the static BOM snapshot):
```kotlin
val fraction = if (bomLine.requiredQty > 0.0)
    (bomLine.scannedQty / bomLine.requiredQty).toFloat().coerceIn(0f, 1f)
else 0f
...
text = if (bomLine.isFullyAllocated) "Fully Allocated" else "%.2f %s".format(bomLine.remainingQty, bomLine.uom)
```

The existing `scannedCount`/`required`/`satisfied` computation (RFID-scan-count based) is left untouched — it still drives the checkmark, card border/background color, and the "Proceed to Hopper Scan" gating, none of which are in scope here.

## Formatting

Remaining quantity is shown fixed to 2 decimal places followed by the unit, e.g. `"15.31 KG"`. No thousands separators (BOM quantities in this domain are small, e.g. under ~1500).

## Testing

- Unit test on the mapping in `MixingUseCase.lookupJob` (or its existing test) asserting `uom` is carried from `BomLineResponse.uomCode` to `BomLine.uom`.
- No new ViewModel/screen tests planned — the changed logic is a pure display computation inline in a `@Composable`; existing `IngredientScanScreen` has no unit tests today (Compose UI, no test file), consistent with sibling screens in this codebase.
