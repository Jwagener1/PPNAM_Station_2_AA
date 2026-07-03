# Job Card Lifecycle Enhancements — Design Spec

**Date:** 2026-07-03
**Source contract:** `C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` (updated alongside this spec — see §6)
**Backend source referenced:** `PPNAM.Station2.Core` (`StationWorkflowService`, `StationQueryService`, `RfidWorkflowMessageProcessor`, `UserAuthenticationService`, `RfidWorkflowMessageModels`)
**Scope:** Three related changes to the job-card lookup/cancel flow, sharing `JobLookupScreen`, `IngredientScanScreen`, `MixingViewModel`, `MixingUseCase`, and `data/mqtt/dto/JobCardMessages.kt`. Ships together because they touch the same files and the same screen-level UX pass.
**Not in scope:** The MQTT reconnection fix (companion spec, `2026-07-03-mqtt-reconnection-fix-design.md`) — unrelated files/risk profile.

---

## Context

Today `JobLookupScreen` is a bare text field: the operator must already know the job card number. `IngredientScanScreen`'s "satisfied" state is computed purely from locally-scanned RFID tag counts, ignoring the allocation quantities the backend already returns. Cancelling a wrongly-loaded job (`MixingViewModel.cancelJob()`) fire-and-forgets a `premix_cancelled` message the backend has never implemented a handler for (`MixingUseCase.kt:98-112`), and closes the UI immediately regardless of outcome — with dialog copy ("ingredients will be discarded") that doesn't match the backend's actual `CancelAccidentalPreMix` rule (rejects if the pre-mix has *any* scanned/hopper/downstream activity).

This spec addresses three things:

- **B1** — Active job list on `JobLookupScreen`, tap-to-load.
- **B2** — Per-line allocation status surfaced from data the backend already sends but the app currently discards.
- **B3** — Cancel becomes a real request/response with role-gated approval, waiting for backend confirmation before closing.

---

## B1 — Active Job List

### Backend reality check

No such endpoint exists in the current contract. `StationQueryService` already computes `ActivePreMixCount = state.PreMixes.Count(p => p.Status is PreMixStatus.Open or PreMixStatus.WaitingMixer)` for its own summary — this spec's list is the same filter, projected to a row per job instead of a count. This means "active jobs" = jobs with an **open or resumable pre-mix already started at Station 2**, not a live feed of all SAP-released production orders — the SAP client only supports single-order-by-number lookup (`GetProductionOrderByDocumentNumberAsync`), no bulk "list open orders" call exists, so a SAP-sourced list would be a materially bigger backend lift. If a broader "any releasable SAP order" list is actually wanted later, that's a separate spec.

List is **unscoped** — all active jobs system-wide, not filtered to the requesting device (per prior decision).

### New MQTT contract

Request `active_job_cards_requested` → response `active_job_cards_list`.

```json
// PPNAM/handheld_1/active_job_cards_requested
{
  "messageId": "active-jobs-0001",
  "schemaVersion": "1.0",
  "deviceId": "handheld_1",
  "operatorSessionId": "session-id",
  "timestampUtc": "2026-07-03T10:30:00Z",
  "correlationKey": "active-jobs-0001"
}
```

```json
// PPNAM/handheld_1/active_job_cards_list
{
  "messageId": "server-generated",
  "schemaVersion": "1.0",
  "deviceId": "handheld_1",
  "operatorSessionId": "session-id",
  "timestampUtc": "2026-07-03T10:30:00Z",
  "correlationKey": "active-jobs-0001",
  "accepted": true,
  "reason": null,
  "jobs": [
    {
      "jobCardNumber": "510019068",
      "productionOrderDocumentNumber": "510019068",
      "preMixId": "premix-id",
      "productName": "Layer Mash",
      "status": "Open"
    }
  ]
}
```

`status` is the `PreMixStatus` enum value as a string (`Open` | `WaitingMixer` — the only two this query returns). Requires an active operator session (standard envelope rule), no other request fields.

### Backend

New case in `RfidWorkflowMessageProcessor`'s dispatch (`ProcessActiveJobCardsAsync`, alongside `ProcessJobCardAsync`): validate session, query `state.PreMixes.Where(p => p.Status is PreMixStatus.Open or PreMixStatus.WaitingMixer)`, project to the row shape above, return `active_job_cards_list`. No new backend service logic — pure query, reusing the exact filter `StationQueryService` already applies elsewhere.

### App

`data/mqtt/dto/JobCardMessages.kt` — add:

```kotlin
data class ActiveJobCardsRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String
)

data class ActiveJobCardSummary(
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val preMixId: String = "",
    val productName: String = "",
    val status: String = ""
)

data class ActiveJobCardsListResponse(
    val messageId: String = "",
    val schemaVersion: String = "1.0",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String = "",
    val accepted: Boolean = false,
    val reason: String? = null,
    val jobs: List<ActiveJobCardSummary> = emptyList()
)
```

`MixingUseCase.fetchActiveJobCards(): Result<List<ActiveJobCardSummary>>` — same `sendTyped(requestType = "active_job_cards_requested", responseType = "active_job_cards_list", ..., allowOfflineQueue = false)` pattern as `lookupJob`. No offline queueing — a stale cached list while disconnected is misleading; better to show a connection error and let the operator retry.

`MixingViewModel` gains `activeJobs: StateFlow<List<ActiveJobCardSummary>>` and `fun loadActiveJobs()`, called from `JobLookupScreen`'s `LaunchedEffect(Unit)` on entry, and again after a cancel returns to this screen (§B3).

`JobLookupScreen` — active jobs render as a list above/below the manual entry field (job number + product name per row, per prior decision). Tapping a row calls `viewModel.lookupJob(job.jobCardNumber)` directly — identical to typing the number and pressing "Look Up" (auto-submit, per prior decision). Loading/empty/error states for the list are independent of the manual-lookup `MixingUiState` (a failed list fetch shouldn't block manual entry).

---

## B2 — Per-Line Allocation Status

### Backend reality check

**No backend change needed.** `BomLineMessage` (`RfidWorkflowMessageModels.cs:155-174`) already includes `PlannedQuantity`, `IssuedQuantity`, `RemainingQuantity` on every line of `BomLoadedMessage.Ingredients`, camelCase-serialized to match the Android DTO's existing `BomLineResponse` field names exactly. The gap is entirely in the app: `MixingUseCase.lookupJob`'s mapping (`MixingUseCase.kt:75-84`) reads `issuedQuantity` into `scannedQty` but drops `remainingQuantity` on the floor, and only the backflush line gets special treatment.

### Design

`domain/model/ProductionOrder.kt` — extend `BomLine`:

```kotlin
data class BomLine(
    val itemCode: String,
    val itemName: String,
    val requiredQty: Double,
    val scannedQty: Double = 0.0,
    val remainingQty: Double = 0.0,       // NEW — from BomLineResponse.remainingQuantity
    val valid: Boolean = true,
    val reason: String? = null
) {
    val isFullyAllocated: Boolean get() = remainingQty <= 0.0
}
```

`MixingUseCase.lookupJob` mapping — carry `remainingQty = line.remainingQuantity` for every line (not just backflush).

`IngredientScanScreen` — a line where `bomLine.isFullyAllocated` is true renders a distinct "Fully Allocated" state (badge + `SuccessGreen` treatment, no scan-progress bar, no "N / required" counter) instead of the current scan-count-driven card. It counts as satisfied for both the header's `satisfiedCount` tally and the "Proceed to Hopper Scan" gate — a line the backend says is already fully issued should never block progress waiting for a scan that will never happen. Lines with partial allocation (`0 < remainingQty < requiredQty`) keep today's scan-progress UI unchanged; `remainingQty` isn't otherwise surfaced numerically to avoid cluttering the card — the fully-allocated boolean is the only new UI signal, matching what was actually asked for ("product 1 was fully allocated" as a fact the operator needs, not a running remaining-quantity readout).

---

## B3 — Cancel With Role-Gated Approval

### Backend reality check (revised mid-session — see note)

> **Note:** `PPNAM-Station-2` was updated by a concurrent effort while this spec was being written. The base cancel round trip described below is **already implemented and live**, not hypothetical — this section was rewritten against the actual current code (`RfidWorkflowMessageProcessor.ProcessPreMixCancelled`, `RfidWorkflowMessageModels.PreMixCancelledMessage`/`PreMixCancelResultMessage`) rather than the dormant, unhandled state this spec originally assumed. `RFID_MQTT_CONTRACT.md` has been updated to match (§6).

`premix_cancelled` → `premix_cancel_result` already works end-to-end today: `RfidWorkflowMessageProcessor.ProcessPreMixCancelled` validates the session and calls `StationWorkflowService.CancelAccidentalPreMix(preMixId, sourceDevice, timestampUtc, operatorSessionId, reason)` (`StationWorkflowService.cs:413-498`), which:

- Requires an active operator session.
- No-ops successfully if already `Cancelled`.
- Rejects if `Completed`/`Allocated`.
- **Rejects if the pre-mix has any scanned ingredients, hopper assignment, SAP issue, allocation, exception, or manager-approval activity** — "only an untouched JC load can be closed." This contradicts the current app dialog's "ingredients will be discarded" copy; the new flow surfaces the real rejection reason instead.

The app already has a dormant `PreMixCancelledRequest` DTO and fires it with the *correct* request type string (`"premix_cancelled"`, via `publishTyped` — `MixingUseCase.kt:111`) — it just never reads a response, so the operator gets no confirmation either way. **This means the "wait for backend confirmation" half of B3 is mostly a matter of switching the existing plumbing from fire-and-forget to request/response**, not building a new endpoint.

What's genuinely missing, confirmed by reading the current handler: **no role check exists at all**. Any logged-in session — Worker included — can cancel any untouched pre-mix today. `OperatorContextMessage`'s `AllowedActions` (`RfidWorkflowMessageProcessor.cs:774-776`) is also currently a **flat, role-agnostic list** identical for every accepted login (`submit_job_card`, `cancel_premix`, `recover_holding`, ...) — there is no per-role differentiation anywhere yet. The role gate this spec adds (Worker needs approval, Manager/Admin don't) is entirely new application-level policy, layered in front of an already-working cancel operation.

The proven precedent for "verify a second person's credentials mid-workflow" is `manager_approval_requested` → `manager_approval_result`, backed by `UserAuthenticationService.VerifyCredentialsForRole(name, password, minimumRole, ...)`. That method is **username/password only** — there is no badge-tag-to-role lookup anywhere in the backend today.

Badge-based approval is therefore **not part of this delivery**. The wire contract reserves an optional `managerBadgeTag` field so the shape doesn't need to change when badge support lands, but this pass implements the approval dialog as **credentials-only** — no badge-scan UI is built now, since a badge path with no backend validation behind it would be a dead end an operator could tap into and always get rejected. Badge support (dialog UI + backend badge-to-role lookup) is a tracked fast-follow, not a stub shipped in this pass.

### Role gate — capability flag, not role strings

No fixed set of role-string values is defined in the app or contract (`OperatorSession.role` is a free-form display string). `OperatorSession.allowedActions` already exists — and, per the reality check above, is already sent today, just not yet role-differentiated. This spec adds one new capability, present only for Manager/Administrator sessions:

```
"cancel_premix_direct"
```

(Named to sit alongside the existing `"cancel_premix"` action already in the static list — `cancel_premix` means "may attempt a cancel at all," `cancel_premix_direct` means "may do so without a second approval.")

App gate: if `sessionHolder.session?.allowedActions?.contains("cancel_premix_direct") == true`, the cancel dialog skips straight to confirm. Otherwise it requires a second admin/manager credential entry before the confirm button is enabled. Backend gate (defense in depth, §"Backend" below): the handler re-derives this from the *initiating* session's actual role server-side — the app's own `allowedActions` check is a UX shortcut, not the security boundary. A tampered or stale client can't skip approval; the backend independently verifies `RoleMeetsMinimum(session.Role, Manager)` before honoring an unapproved cancel.

### MQTT contract (extends the existing message, not a new topic pair)

Topic stays `premix_cancelled` → `premix_cancel_result` — no rename, since the topic already exists and the app already publishes to it. `MixingUseCase.notifyJobCardCancelled`'s fire-and-forget `publishTyped` call is replaced with a `sendTyped` request/response call (§App below) using the same DTOs, extended with new fields.

`PreMixCancelledMessage` (request) gains three new optional fields:

```json
// PPNAM/handheld_1/premix_cancelled
{
  "messageId": "67404f9b-8502-41d6-9a5c-697dad9a59f7",
  "schemaVersion": "1.0",
  "deviceId": "handheld_1",
  "operatorSessionId": "session-id",
  "timestampUtc": "2026-07-03T10:40:00Z",
  "correlationKey": "premix-id",
  "preMixId": "premix-id",
  "jobCardNumber": "510019068",
  "reason": "Operator cancelled — incorrect job card",
  "managerUsername": "",
  "managerPassword": "",
  "managerBadgeTag": ""
}
```

`managerUsername`/`managerPassword` are blank when the initiating operator's own session already carries `cancel_premix_direct`; populated from the approval dialog otherwise. Field names deliberately match `ManagerApprovalRequestedMessage`'s existing `ManagerUsername`/`ManagerPassword` naming for consistency with the proven pattern. `managerBadgeTag` is reserved for the badge-approval fast-follow — always blank in this delivery; the backend accepts but ignores it, so the field can go live later without another contract version bump.

`PreMixCancelResultMessage` (response) gains three new fields on top of its existing `Accepted`/`Reason`/`PreMixId`/`JobCardNumber`/`PreMixStatus`/`NextAction`:

```json
// PPNAM/handheld_1/premix_cancel_result
{
  "messageId": "server-generated",
  "schemaVersion": "1.0",
  "deviceId": "handheld_1",
  "operatorSessionId": "session-id",
  "timestampUtc": "2026-07-03T10:40:01Z",
  "correlationKey": "premix-id",
  "accepted": true,
  "reason": null,
  "preMixId": "premix-id",
  "jobCardNumber": "510019068",
  "preMixStatus": "Cancelled",
  "nextAction": "",
  "approverUserId": "",
  "approverDisplayName": "",
  "approverRole": ""
}
```

`approverUserId`/`approverDisplayName`/`approverRole` are blank when no second approval was needed (initiating operator already qualified); populated with the verified manager/admin's identity otherwise, mirroring `ManagerApprovalResultMessage`'s audit fields. `accepted = false` covers both "approval rejected" (bad manager credentials, or Worker with no credentials supplied) and "pre-mix has activity, cannot close" (the existing `CancelAccidentalPreMix` business rule) — `reason` is the human-readable string from whichever check failed, shown verbatim in the app's error state.

### Backend

Two changes to already-working code, not a new handler:

1. **`ProcessPreMixCancelled`** (`RfidWorkflowMessageProcessor.cs:276-315`) gains a role/approval check inserted between the existing session lookup (line 291) and the existing `CancelAccidentalPreMix` call (line 298):
   - Load the initiating session's role.
   - If role meets `Manager` minimum → proceed with `approver = null`.
   - Else: if `ManagerUsername`/`ManagerPassword` blank → reject with `reason = "Manager or admin approval is required."` Else call `UserAuthenticationService.VerifyCredentialsForRole(ManagerUsername, ManagerPassword, StationRole.Manager, sourceDevice, approvalTarget: preMixId, reason, timestampUtc)`; on failure, reject with its returned message; on success, `approver = ` the verified `StationUserSummary`.
   - Only on passing this check does execution reach the existing `CancelAccidentalPreMix` call.
   - `BuildPreMixCancelResponse` (currently building `Accepted`/`Reason`/`PreMixId`/`JobCardNumber`/`PreMixStatus`/`NextAction`) gains `ApproverUserId`/`ApproverDisplayName`/`ApproverRole`, populated from `approver` when non-null.
2. **`BuildOperatorContextResponse`** (`RfidWorkflowMessageProcessor.cs:760-779`) — the `AllowedActions` list becomes role-aware instead of the current flat static array: `"cancel_premix_direct"` is appended only when `session.Role` meets `Manager` minimum (reusing `RoleMeetsMinimum`/`RoleRank` already in `UserAuthenticationService`). All other existing actions in the list are unaffected — this is additive, not a restructure of that list.

### App

`data/mqtt/dto/JobCardMessages.kt` — extend the existing `PreMixCancelledRequest` with `managerUsername: String = ""`, `managerPassword: String = ""`, `managerBadgeTag: String = ""`; add a new `PreMixCancelResultResponse` DTO matching the response shape above (including `preMixStatus`, `nextAction`, `approverUserId`, `approverDisplayName`, `approverRole`).

`MixingUseCase.notifyJobCardCancelled` (fire-and-forget) → replaced by `cancelJob(preMixId, jobCardNumber, reason, managerUsername, managerPassword): Result<PreMixCancelResultResponse>` using `sendTyped(requestType = "premix_cancelled", responseType = "premix_cancel_result", ..., allowOfflineQueue = false)` — cancellation is meaningless queued for later, the operator is standing there waiting for an answer now.

`MixingViewModel.cancelJob()` becomes suspend, driven by a new UI state (`MixingUiState.Cancelling`) shown while awaiting the response:

- If the current session already has `cancel_premix_direct` → call `useCase.cancelJob(..., managerUsername = "", managerPassword = "")` directly from the existing confirm dialog.
- Otherwise, confirming "Cancel Job" opens a second dialog collecting a manager/admin username + password (credentials-only in this pass, per the role-gate section above), then calls `useCase.cancelJob(...)` with those credentials.
- On `accepted = true`: clear local state (existing behavior) and navigate back; `JobLookupScreen`'s `LaunchedEffect` re-fires `loadActiveJobs()` on return (§B1).
- On `accepted = false` or a timeout/disconnected error: **stay on the current screen**, show the `reason` (or a connection-appropriate message) inline in the dialog, let the operator retry entering credentials or back out without cancelling anything — nothing is cleared, nothing navigates (per prior decision).

`IngredientScanScreen`'s cancel confirmation copy updates to no longer claim ingredients are discarded — it should reflect that a job with any activity on it can't be closed this way (surfaced via the `reason` returned on rejection, not hardcoded, since the exact wording is backend-owned).

---

## Error Handling Summary (all three)

| Case | Behavior |
|---|---|
| Active job list fetch fails/disconnected | Inline error in the list area; manual entry field remains usable |
| Job lookup on a fully-allocated-everywhere order | Not a special case — `IngredientScanScreen` just shows all lines as fully allocated and the Proceed gate is immediately satisfied |
| Cancel: wrong manager credentials | `accepted=false`, reason shown, dialog stays open for retry |
| Cancel: pre-mix has activity | `accepted=false`, backend's real reason shown, job stays loaded |
| Cancel: disconnected/timeout | Error shown, job stays loaded, no local state cleared |

## Testing

- `MixingUseCase` — `fetchActiveJobCards` success/error/disconnected; `lookupJob` mapping carries `remainingQty` for all lines including backflush; `cancelJob` success (with and without manager credentials), rejection (bad credentials, has-activity), disconnected.
- `MixingViewModel` — `loadActiveJobs()` populates `activeJobs`; tapping a job triggers the same path as `lookupJob(text)`; `cancelJob` state machine (`Idle → Cancelling → {back to Idle on reject, navigate away on accept}`); gating on `cancel_premix_direct` skips the approval dialog.
- `IngredientScanScreen` line rendering — fully-allocated line shows the new badge state and counts toward the Proceed gate without any local scans.
- Envelope serialization — new DTOs round-trip through Gson with exact contract field names, matching the existing convention (schemaVersion default "1.0", etc.).
- Backend (`RfidWorkflowMessageProcessorTests`) — `ProcessPreMixCancelled` now needs cases for: Manager/Admin session cancelling with blank credentials (succeeds), Worker session with blank credentials (rejected, no `CancelAccidentalPreMix` call attempted), Worker session with valid manager credentials (succeeds, `approver*` fields populated), Worker session with invalid manager credentials (rejected, `CancelAccidentalPreMix` not called). Backend (`UserAuthenticationServiceTests`) unaffected — reuses `VerifyCredentialsForRole` as-is.

---

## §6 — Contract Doc Sync (already applied)

`RFID_MQTT_CONTRACT.md` in `PPNAM-Station-2` was updated in this same session, directly against the file's actual current state (see the B3 reality-check note above — the file had moved since this spec was started):

1. **Supported topics table** — added `active_job_cards_requested` → `active_job_cards_list`; updated the existing `premix_cancelled` → `premix_cancel_result` row's Purpose text to mention approval instead of adding a duplicate row (the topic name didn't change).
2. **New "Active job cards" section** with the request/response JSON shapes from §B1, inserted between "Job card and BOM" and "Cancel an incorrectly loaded job card."
3. **Extended the existing "Cancel an incorrectly loaded job card" section** (not a new section — it already existed) with the `managerUsername`/`managerPassword`/`managerBadgeTag` request fields, the approval rule prose, and the `approverUserId`/`approverDisplayName`/`approverRole` response fields from §B3.
4. **`bom_loaded`'s description bullet list** gained an explicit line noting each ingredient carries `plannedQuantity`/`issuedQuantity`/`remainingQuantity`, and that `remainingQuantity <= 0` means the line is fully allocated.
5. **Login section** gained a line documenting the new `cancel_premix_direct` allowed action and which roles carry it.
6. **Fixed a pre-existing doc bug while in the area**: the `bom_loaded` bullet list's final item ("sets `nextAction` to `scan_ingredient`") had been orphaned outside the list by an earlier edit that inserted the cancel section in the middle of it — restored to its correct place in the list, and the cancel section moved after "A completed pre-mix is never resumed," restoring correct document order.
