# MQTT Pre-Mix & Hopper Workflow — Design Spec

**Date:** 2026-06-30  
**Approach:** Surgical extension of existing `MixingUseCase` and mixing screens (Option A)  
**Scope:** Richer ingredient validation, invalid-ingredient exception/supervisor flow, hopper allocation replacing mixer code, live hopper broadcast subscription

---

## Context

The existing mixing flow is tablet-driven via DataWedge (RFID + barcode). All scans arrive through `ScanEventBus`. MQTT transport is app → WPF only (`station2/request` publish / `station2/response/{deviceId}` subscribe). The WPF backend is the authority for SAP data, operator identity, and hopper state.

This design extends that flow with:
- A richer `validate-ingredient` response (valid/invalid with reason)
- A supervisor-gated exception path for invalid ingredients
- Hopper allocation replacing the `MixerCodeScreen` (barcode scan → WPF confirmation)
- A new broadcast subscription (`station2/hopper/status`) for live hopper state across all tablets
- SAP Issue to Production queued on completion (not on job card or ingredient scan)

---

## 1. MQTT Layer

### 1.1 Updated and new action strings

| Action | Direction | Request payload | Response payload |
|--------|-----------|-----------------|------------------|
| `validate-ingredient` *(updated)* | app → WPF | `{orderNo, tagId}` | `{valid, itemCode, itemName, requiredQty, reason?}` |
| `approve-ingredient-exception` *(new)* | app → WPF | `{orderNo, tagId, supervisorTagId}` | `{approved, supervisorName?, reason?}` |
| `check-hopper` *(new)* | app → WPF | `{orderNo, hopperCode}` | `{available, hopperCode, reason?}` |
| `complete-premix` *(updated)* | app → WPF | `{orderNo, hopperCode, ingredients[], exceptions[]}` | `{}` |

`exceptions[]` is the subset of `ingredients` where `isException = true`. The `mixerCode` field in the old `complete-premix` payload is replaced by `hopperCode`.

### 1.2 New broadcast subscription — `station2/hopper/status`

- Subscribed on MQTT connect alongside the existing `station2/response/{deviceId}`
- WPF publishes whenever any hopper changes state
- Payload: `{ hopperCode, status: "Available"|"InUse"|"Offline", assignedTo? }`
- App exposes `hopperStatusUpdates: SharedFlow<HopperStatus>` from `MqttRepositoryImpl`
- No offline queue, no retry — ephemeral push state; ViewModel re-requests on reconnect if needed

### 1.3 MqttRepository interface + MqttRepositoryImpl changes

`MqttRepository` interface gains one new property:
```kotlin
val hopperStatusUpdates: SharedFlow<HopperStatus>
```

`MqttRepositoryImpl` adds a second `subscribe()` call in the connect block for `station2/hopper/status`. Incoming messages on that topic are routed to a new `MutableSharedFlow<HopperStatus>` backed by the replay=1 buffer so late subscribers get the last known state immediately.

---

## 2. Domain Models

### 2.1 Updated `BomLine`

```kotlin
data class BomLine(
    val itemCode: String,
    val itemName: String,
    val requiredQty: Double,
    val scannedQty: Double = 0.0,
    val valid: Boolean = true,
    val reason: String? = null
)
```

### 2.2 New `IngredientValidationResult`

```kotlin
sealed class IngredientValidationResult {
    data class Valid(val bomLine: BomLine) : IngredientValidationResult()
    data class Invalid(val tagId: String, val reason: String) : IngredientValidationResult()
}
```

### 2.3 New `HopperStatus`

```kotlin
data class HopperStatus(
    val hopperCode: String,
    val status: HopperAvailability,
    val assignedTo: String?
)

enum class HopperAvailability { AVAILABLE, IN_USE, OFFLINE }
```

### 2.4 Updated `ScannedIngredient`

```kotlin
data class ScannedIngredient(
    val tagId: String,
    val itemCode: String,
    val qty: Double,
    val isException: Boolean = false,
    val approvedBy: String? = null  // supervisor name; null for normal scans
)
```

### 2.5 `PreMix` model

`mixerCode` field renamed to `hopperCode` throughout `PreMix` and all references.

---

## 3. Use Case — `MixingUseCase`

### 3.1 `validateIngredient` — updated return type

```kotlin
suspend fun validateIngredient(orderNo: String, tagId: String): Result<IngredientValidationResult>
```

- WPF returns `valid: false` → `Result.success(Invalid(tagId, reason))`
- WPF returns `valid: true` → `Result.success(Valid(bomLine))`
- MQTT error → `Result.failure`
- Offline (Queued) → `Result.success(Valid(BomLine(tagId, "Offline scan", 1.0)))` — optimistic, unchanged behaviour

### 3.2 New `approveIngredientException`

```kotlin
suspend fun approveIngredientException(
    orderNo: String,
    tagId: String,
    supervisorTagId: String
): Result<ScannedIngredient>
```

On `approved: true` → returns `ScannedIngredient(tagId, itemCode, qty=1.0, isException=true, approvedBy=supervisorName)`.  
On `approved: false` → `Result.failure(Exception(reason))` — ViewModel stays in `WaitingForSupervisor` and shows a snackbar error.

### 3.3 New `checkHopper`

```kotlin
suspend fun checkHopper(orderNo: String, hopperCode: String): Result<Unit>
```

`available: false` → `Result.failure(Exception(reason))`.  
`available: true` → `Result.success(Unit)`. WPF marks the hopper `InUse` and broadcasts the state change on `station2/hopper/status`.

### 3.4 `completePremix` — updated signature

```kotlin
suspend fun completePremix(
    orderNo: String,
    hopperCode: String,
    ingredients: List<ScannedIngredient>
): Result<Unit>
```

The app sends `ingredients[]` (all scanned items) and `exceptions[]` (the subset where `isException = true`) as separate arrays — WPF receives both pre-separated. `mixerCode` parameter removed.

---

## 4. ViewModel — `MixingViewModel`

### 4.1 Updated `MixingUiState`

```kotlin
sealed class MixingUiState {
    object Idle : MixingUiState()
    object Loading : MixingUiState()
    data class OrderLoaded(val order: ProductionOrder) : MixingUiState()
    data class IngredientInvalid(val tagId: String, val reason: String) : MixingUiState()
    data class WaitingForSupervisor(val tagId: String, val reason: String) : MixingUiState()
    data class HopperUnavailable(val hopperCode: String, val reason: String) : MixingUiState()
    data class Error(val message: String) : MixingUiState()
}
```

### 4.2 State and flow changes

- `_mixerCode` → renamed to `_hopperCode: MutableStateFlow<String>`
- New: `hopperStatusUpdates: SharedFlow<HopperStatus>` collected from `mqttRepository.hopperStatusUpdates`

### 4.3 Updated `startListeningForScans`

On `IngredientValidationResult.Invalid` → cancel the active scan listener, set state to `IngredientInvalid(tagId, reason)`. Scan listener resumes only when the operator chooses Discard or after a successful supervisor approval.

On `WaitingForSupervisor` entry, a new single-shot scan listener is started that accepts only `ScanEvent.RfidTag` and calls `submitSupervisorTag`.

### 4.4 New ViewModel methods

```
discardInvalidIngredient()
  → resets state to OrderLoaded, resumes startListeningForScans

requestSupervisorOverride(tagId, reason)
  → sets state to WaitingForSupervisor, starts supervisor tag scan listener

submitSupervisorTag(supervisorTagId)
  → calls approveIngredientException
  → success: appends exception ScannedIngredient, resumes normal scan listener, state → OrderLoaded
  → failure: stays WaitingForSupervisor, emits error snackbar

checkAndAllocateHopper(orderNo, hopperCode)
  → state → Loading
  → calls checkHopper
  → success: sets _hopperCode, fires PREMIX_COMPLETE nav event
  → failure: state → HopperUnavailable(hopperCode, reason)
```

---

## 5. Screens and Navigation

### 5.1 Navigation changes

| Old route | New route |
|-----------|-----------|
| `mixing/mixer_code/{orderNo}` | `mixing/hopper_scan/{orderNo}` |

`NavRoutes` updated. `IngredientScanScreen` "Proceed" button navigates to `hopperScan(orderNo)`.

### 5.2 `IngredientScanScreen` — exception overlays

Two new UI states rendered over the existing scan UI (normal scan cards remain visible but inactive):

**`IngredientInvalid` overlay:**
- Amber warning card: ingredient tag ID + reason from WPF
- **Discard** button → `discardInvalidIngredient()`
- **Override** button → `requestSupervisorOverride(tagId, reason)`

**`WaitingForSupervisor` overlay:**
- Pulsing `ScanPromptCard` with message "Scan supervisor tag to approve"
- **Cancel** button → `discardInvalidIngredient()`
- Accepts only RFID scan events; barcode events ignored in this state

### 5.3 `HopperScanScreen` (replaces `MixerCodeScreen`)

Route: `mixing/hopper_scan/{orderNo}`

- Live hopper availability list from `hopperStatusUpdates` — chips showing Available (green), In Use (red), Offline (grey)
- `ScanPromptCard` prompting barcode scan of hopper
- `ScanEvent.Barcode` → `checkAndAllocateHopper(orderNo, event.value)`
- `Loading` state → spinner, scan disabled
- `HopperUnavailable` state → inline error card with reason, scan prompt resets automatically
- No manual text entry field (WPF is the authority; a barcode scan is required)

### 5.4 `PreMixCompleteScreen`

- `mixerCode` chip label → `hopperCode`
- No other changes

---

## 6. Offline Behaviour

| Action | Offline behaviour |
|--------|-------------------|
| `validate-ingredient` | Optimistic accept — returns `BomLine("Offline scan")`, unchanged from current |
| `approve-ingredient-exception` | Fails with error — supervisor approval requires WPF connection |
| `check-hopper` | Fails with error — hopper allocation requires WPF confirmation |
| `complete-premix` | Queued via `OfflineQueueDao`, `isQueuedOffline = true`, navigate to completion screen |

---

## 7. Test Plan

- Valid ingredient scan → appended to list, BOM progress updates
- Invalid ingredient scan → `IngredientInvalid` state shown, scan listener paused
- Discard → state resets to `OrderLoaded`, scanning resumes
- Override → `WaitingForSupervisor` state, only RFID accepted
- Valid supervisor tag → exception ingredient added with `isException=true`, `approvedBy` set, scanning resumes
- Invalid supervisor tag (not a supervisor) → stays `WaitingForSupervisor`, error snackbar shown
- Cancel during supervisor wait → discard ingredient, return to scanning
- Hopper scan → available hopper → allocated, navigate to `PreMixCompleteScreen`
- Hopper scan → unavailable hopper → `HopperUnavailable` state, reason shown, scan resets
- Offline `complete-premix` → queued, `isQueuedOffline=true`, confirmation screen shown
- Offline `check-hopper` → fails with clear error, not queued
- `station2/hopper/status` broadcast → `HopperScanScreen` live chip updates reflect new state
- Duplicate `complete-premix` retry → WPF idempotency key prevents duplicate SAP issue
