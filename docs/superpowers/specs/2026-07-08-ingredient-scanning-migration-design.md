# Ingredient Scanning Migration — Design Spec

**Date:** 2026-07-08
**Scope:** Replace the legacy, non-contract ingredient-scanning flow (`MixingUseCase.validateIngredient`/`approveIngredientException`, generic `mqttRepository.send()` actions) with the real MQTT contract: `ingredient_scanned`/`ingredient_scan_result`, `manager_approval_requested`/`manager_approval_result` (exceptions), and `holding_recovery_requested`/`holding_recovery_result` (misplaced-pallet detour).
**Out of scope:** Hopper scan (`checkHopper`), premix-complete (`completePremix`), and Rajoo allocation (`RajooUseCase`) are separately broken against the contract in the same way (legacy `send()` actions) but are not touched here — flagged as follow-up work. The existing `RfidRecoveryScreen`/`RfidUseCase` ("lookup-pallet") is an unrelated, also-legacy pallet-lookup feature, not the `holding_recovery_requested` flow — not touched here.

---

## Context

Audited against the current `RFID_MQTT_CONTRACT.md` (PPNAM-Station-2 commit `a04bff4`) and the backend's `RfidWorkflowMessageModels.cs`/`RfidWorkflowMessageProcessor.cs`. Today, `MixingUseCase.validateIngredient` sends a made-up `"validate-ingredient"` action through the generic legacy envelope (`mqttRepository.send()`), which doesn't correspond to any topic Station 2 actually subscribes to. Every scanned RFID tag is treated as `qty = 1.0` regardless of real material weight, and `IngredientScanScreen`'s "satisfied" gating counts scan events against `requiredQty.toInt()`.

The real contract is pallet- and bag-based: the operator scans a pallet's RFID tag, picks a bag size and count, and the backend validates it against the pre-mix's manually-collected BOM lines, computing quantity server-side from the pallet's known full-bag weight.

## Data verified from source (not assumed)

- `IngredientScannedMessage` (request): `PreMixId`, `PalletRfidTag`, `Quantity` (decimal, legacy path), `BagCount` (`decimal?`), `BagSizeOption` (`string?`: `1/4`/`1/2`/`3/4`/`full`), `RequestedMaterialCode`, `ApprovalId`.
- `IngredientScanResultMessage` (response): `Accepted`, `Reason`, `PreMixId`, `ScannedQuantity`, `IsRequirementSatisfied`, `HasApprovedException`, `RequiresManagerApproval`, `ExceptionId`, `ConsumedApprovalId`, `NextAction`, `IngredientProgress: List<BomProgressLineMessage>`.
- `BomProgressLineMessage`: `MaterialCode`, `MaterialName`, `PlannedQuantity`, `IssuedQuantity`, `RequiredQuantity`, `ScannedQuantity`, `RemainingQuantity`, `ExpectedBags`, `ScannedBags`, `ApprovedExtraBags`, `ApprovedShortBags`, `RemainingBags` (all bag fields `decimal`), `RequiresManagerApproval`, `UomCode`, `Unit` (both carry the same operator-facing unit string, e.g. `"kg"`).
- **Verified server-side** (`RfidWorkflowMessageProcessor.BuildBomProgress`): `IngredientProgress` is the **complete recomputed list** of every manual-issue BOM line on the pre-mix, not just the material just scanned — grouped by `ItemNo`, summed across snapshot lines, adjusted for approved exceptions. This is not an assumption; it's read directly from the backend implementation.
- `ManagerApprovalRequestedMessage`: `ManagerUsername`, `ManagerPassword`, `ApprovalTargetType`, `ApprovalTargetId`, `PreMixId`, `PalletRfidTag`, `RequestedMaterialCode`, `ApprovalType` (optional — inferred from the exception when omitted), `ActualMaterialCode`, `QuantityDelta`, `BagCountDelta`, `Reason`.
- `ManagerApprovalResultMessage`: `Accepted`, `Reason`, `ManagerUserId`, `ManagerDisplayName`, `Role`, `RoleLabel`, `ApprovalTargetType`, `ApprovalTargetId`, `ApprovalType`, `ApprovalId`, `ExpiresAtUtc`.
- `HoldingRecoveryRequestedMessage`: `PreMixId`, `PalletRfidTag`, `ProductCode`, `Quantity`.
- `HoldingRecoveryResultMessage`: `Accepted`, `Reason`, `PreMixId`, `PalletRfidTag`, `ProductCode`, `ExceptionId`, `NextAction` (`"scan_ingredient"` on success, `"retry_recovery"` on failure).

## Design

### 1. Scan interaction

Operator scans **any pallet RFID tag** — no pre-selection of a BOM line; the backend identifies the material from the pallet. A bottom sheet opens with:
- Bag size as four pill buttons (`1/4`, `1/2`, `3/4`, `Full`)
- Bag count as a stepper (`–` / count / `+`, whole-number taps; long-press for ±0.25 fine adjustment), since real counts are usually whole bags with occasional partial remainders

Confirm sends one `ingredient_scanned` with `palletRfidTag`, `bagSizeOption`, `bagCount` (decimal). **Requires a live connection** — no offline-optimistic path, matching `checkHopper`/`approveIngredientException`'s existing "requires a connection" behavior. If disconnected, the scan screen shows a blocking "reconnect to scan ingredients" state instead of accepting an unvalidated local guess.

### 2. Live progress replaces the static snapshot

`BomLine` (`domain/model/ProductionOrder.kt`) gains `expectedBags`, `scannedBags`, `remainingBags` (all `Double`, mirroring the existing `Double` quantity fields) alongside `requiredQty`/`scannedQty`/`remainingQty`/`uom` already there.

On every **accepted** `ingredient_scan_result`, `MixingUseCase` maps `IngredientProgress` into a fresh `List<BomLine>` and the ViewModel **wholesale-replaces** `ProductionOrder.lines` with it — the same "whole list, freshly computed" pattern `bom_loaded` already uses, just re-triggered per scan instead of once at load. The progress bar/label built in the prior session (`fraction = scannedQty/requiredQty`, label = `"{remainingQty} {uom}"`) need no changes — they automatically go live because the `BomLine` list they read now updates after every scan.

The old scan-count-based `scannedIngredients`/`ScannedIngredient` list, and the "satisfied" logic built on counting RFID taps against `requiredQty.toInt()`, are removed — satisfaction is now `bomLine.remainingQty <= 0 && bomLine.remainingBags <= 0` per line (mirrors the backend's own `IsBomSatisfied`), and `allIngredientsSatisfied` becomes `order.lines.all { it.remainingQty <= 0.0 && it.remainingBags <= 0.0 }`.

### 3. Exception → manager approval (one uniform flow)

Wrong-material, extra-bag, and short-bag attempts are all just *rejected scans*: `accepted=false`, `requiresManagerApproval=true`, `exceptionId` set, `nextAction="manager_approval"`. On that rejection, `IngredientScanScreen` shows the **existing username/password `AlertDialog`** (the same component already built for job-card cancellation in this same screen) with the rejection's `reason` as the dialog body text. On approve, `MixingUseCase` resends the **same** `ingredient_scanned` request (same `palletRfidTag`/`bagSizeOption`/`bagCount`) with `approvalId` set to the `ManagerApprovalResultMessage.ApprovalId` just returned. `approvalType` is omitted on the `manager_approval_requested` call — the contract infers it from the open exception, so the client never determines or sends it.

If the manager-approval call itself is rejected (bad credentials), the dialog stays open and shows the failure reason, same UX as the existing job-cancel approval dialog.

### 4. Pallet-recovery detour

If a scan response has `nextAction="recover_holding"` (pallet known but not currently in Holding/Mixing), show a simple Yes/No `AlertDialog`: "Pallet not in Holding — recover it?". **Yes** → send `holding_recovery_requested` with `preMixId`, `palletRfidTag`, `productCode` left blank (Station 2 already knows the pallet's material from its own local pallet record — that's how the original scan validated "pallet material against manually collected BOM components" in the first place; the client has no independent way to know it and doesn't need to), `quantity` = 0 (the recovery moves the pallet into Holding; it doesn't require a client-supplied quantity for this handheld-triggered path). On `holding_recovery_result.accepted`, automatically retry the original `ingredient_scanned` request. On rejection, show the failure reason and let the operator scan a different pallet. **No** → dismiss, no request sent.

### 5. New `MixingUiState` states

- `EnteringBagDetails(palletTag: String)` — the bag-size/count bottom sheet is open. No material info yet; the backend identifies the material from `palletTag` only once the scan is submitted.
- `IngredientExceptionApproval(exceptionId: String, reason: String)` — replaces `WaitingForSupervisor`, which was keyed on a scanned supervisor tag id; this is keyed on the exception returned by the rejected scan instead, since approval is now username/password, not a tag scan.
- `PalletRecoveryPrompt(palletTag: String)` — the Yes/No recovery dialog.

### 6. Removed

- `MixingUseCase.validateIngredient`, `approveIngredientException` (legacy actions).
- `ScannedIngredient` domain model and `MixingViewModel._scannedIngredients` (superseded by `BomLine`'s own progress fields, always sourced from the backend).
- The RFID-tag-scan-based supervisor-approval flow (`submitSupervisorTag`, `requestSupervisorOverride`'s tag-scanning path) — replaced by the reused username/password dialog per §3.

## Error Handling

- Disconnected at scan time: blocking "reconnect" state, no queuing (§1).
- Rejected scan without `requiresManagerApproval` (e.g. bad pallet, wrong warehouse): show `reason` inline, let operator retry or scan a different pallet — no dialog.
- Manager approval denied or credentials invalid: dialog stays open with the failure reason (§3).
- Recovery rejected: show reason, no automatic retry loop beyond the one recovery attempt (§4).

## Testing

- `MixingUseCase` unit tests: `ingredient_scanned` request shape (palletRfidTag/bagSizeOption/bagCount serialized correctly); accepted scan maps `IngredientProgress` into `ProductionOrder.lines` wholesale; rejected-with-approval scan surfaces `reason`/`exceptionId`; approval retry includes `approvalId`; `recover_holding` triggers `holding_recovery_requested` with the right fields; disconnected state returns a "not connected" failure (no queuing).
- `MixingViewModel` unit tests: bag-entry confirm calls the use case with the entered size/count; manager-approval dialog submit calls approval then retries the scan; recovery Yes/No calls or skips the recovery request appropriately.
- No new `IngredientScanScreen` Compose tests (consistent with sibling screens today — no test file exists for any of them).
