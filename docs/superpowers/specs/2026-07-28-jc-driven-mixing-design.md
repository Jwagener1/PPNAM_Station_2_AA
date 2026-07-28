# JC-Driven Mixing — Android handheld design

**Date:** 2026-07-28
**Status:** Approved for planning
**Supersedes:** `2026-07-21-mqtt-schema-4-mixing-ui-design.md` for everything Mixing-related, and the
strict two-phase destination-assignment work landed in `b7d32ff`.

## 1. Why

The customer replaced the Mixing model. Mixer plans, reservations and the separate
destination-assignment workflow are withdrawn. Mixing is now driven by a **completed collection**,
its **job card**, **equipment scans**, and **server-issued cycle/run IDs**. The backend developer is
implementing the new model in `C:\Dev\PPNAM-Station-2` now; this document covers the Android
handheld's half.

### Scope

**In scope:** the Android app under `app/src` — domain models, MQTT DTOs, use cases, ViewModels,
Compose screens — and its JVM unit tests.

**Out of scope, by explicit decision:** `tools/backend-sim/`, `tools/test-harness/`, `docs/TEST_PLAN.md`,
and the sibling repo's `RFID_MQTT_CONTRACT.md`. The requirements document's instructions about
`AGENTS.md`, `Plans.md`, `Designs.md`, `SAP_README.md` and the operational database reset describe the
backend developer's repository, not this one; `C:\Dev\PPNAM-Station-2` is read-only here apart from
`RFID_MQTT_CONTRACT.md`, which is also out of scope.

### Consequences of that scope

The backend simulator stays on the old plan-based contract. **Nothing will run end-to-end** until
the backend developer ships. Verification for this change is unit tests and wire-shape tests only.
The on-device Gate 4 run recorded in project memory cannot be repeated until either the sim or the
real backend catches up. This is accepted, not overlooked.

### Cutover

Hard cutover. Plan and reservation code is deleted, not flagged off. There is one code path. The
retired messages return `client_upgrade_required` server-side, so a dual path would only preserve
code that can no longer succeed.

## 2. The new workflow, as the app must model it

One completed collection creates exactly one physical mix. The first mixer start atomically claims
the collection and creates `MIX_######` + `CYC_######`. Every finish requires the exact machine code
plus the server-issued cycle ID.

| Area | Start source | Route input | Finish | Then |
|---|---|---|---|---|
| DOLCI | collection | — | same mixer scan | Completed (mixer and fixed run finish together) |
| Main | collection | — | same mixer scan | `ReadyForAllocation` → destination scan starts run → same destination scan completes |
| JANDI | collection | `JAN-02` / `JAN-03` / `JAN-DRUM-01` **required** | same mixer scan | 2/3 direct-feed; Drum → `ReadyForTransfer` → drum scans start/finish decanting |
| JANDI 4 | current drum **+** one ready Main mix | Main mix by `mixBatchId` or by source mixer code | same machine scan | composite run, JCs may differ |
| Mackie | collection, **or** one ready Main mix | — | same mixer scan | fixed direct feed |
| Rajoo | one collection **per layer** | 1–5 positive `{materialCode, dosingQuantity}` **required** | second scan of that layer's mixer | shared run closes when every *started* layer finishes |

Rules the app must not violate:

- Main-to-Rajoo allocation is always rejected. The app never offers it.
- A destination start takes **exactly one** mix. Multi-mix selection is removed.
- The app never infers availability, scan permission, or valid destinations. Those come from
  `scanAllowed`, `validMixerCodes`, `validNextMachineCodes` only.
- No Mixing action posts to SAP. `sapIssuePrepared` is a local preview; `sapIssueQueued`,
  `sapPostingEnabled` and `sapProductionOrderChanged` are always false.

## 3. Wire layer — `data/mqtt/dto/MixingMessages.kt`

### 3.1 Deletions

`MixerPlanItemDto`, `MixDestinationDto`, `MixDestinationAssignmentPayload`,
`MixDestinationAssignmentResultResponse`, `AssignedDestinationDto`, the `PlanItemStatus` and
`MixPlanStatus` objects, and `EquipmentStatus.RESERVED`.

Field deletions:

- `EquipmentDto`: `mixPlanId`, `planItemStatus`, `reservationCollectionId`, `reservationJobCardNumber`
- `ReadyCollectionDto`: `mixPlanId`, `mixPlanStatus`, `plannedMixerCount`, `startedMixerCount`,
  `remainingMixerCount`, `plannedMixerCodes`, `startedMixerCodes`, `remainingMixerCodes`,
  `mixerPlanItems`, and the `hasSavedPlan` helper
- `ReadyMixDto`: `plannedDestinationMachineCode`
- `MachineCycleResultResponse`: `mixPlanId`, `planItemId`, `planItemStatus`, `mixPlanStatus`, the
  three counts, `plannedMixerCodes`, `remainingMixerCodes`, `plannedDestinationMachineCodes`,
  `remainingDestinationMachineCodes`, `productionRunIds`

### 3.2 The JC / production-order split

The app currently sends `productionOrderDocumentNumber` *carrying a job-card number*. The new
contract makes these two different things: `jobCardNumber` is the primary reference at top level,
and `productionOrderDocumentNumber` survives only inside `inputs[]`, where it is the SAP order behind
that particular input.

Every top-level `productionOrderDocumentNumber` in Mixing messages becomes `jobCardNumber`.
`BomLoadedResponse` and `ActiveJobCardSummary` already carry both fields correctly and are unchanged
in this respect.

### 3.3 `MixingOverviewPayload`

```kotlin
data class MixingOverviewPayload(
    val mixingArea: String? = null,
    val jobCardNumber: String? = null,
    val collectionId: String? = null,
)
```

All three optional. Gson omits nulls, per the contract's "omit rather than send null".

### 3.4 `MachineCycleStartPayload`

One payload with optional discriminating fields. Illegal combinations are prevented by construction:
call sites never build this directly, only through the six named use-case functions in §5.2.

```kotlin
data class MachineCycleStartPayload(
    val machineCode: String,
    val collectionId: String? = null,
    val destinationMachineCode: String? = null,
    val mixBatchId: String? = null,
    val mainSourceMixBatchId: String? = null,
    val mainSourceMixerCode: String? = null,
    val layerInputs: List<LayerInputDto>? = null,
)
```

| Variant | Fields on the wire |
|---|---|
| DOLCI / Mackie / Main mixer | `machineCode`, `collectionId` |
| JANDI shared mixer | `machineCode`, `collectionId`, `destinationMachineCode` |
| Rajoo layer | `machineCode`, `collectionId`, `layerInputs[]` |
| JANDI drum transfer | `machineCode`, `mixBatchId` |
| Main production destination | `machineCode`, `mixBatchId` |
| JANDI 4 | `machineCode`, `mainSourceMixBatchId` **or** `mainSourceMixerCode` |

`layerInputs` is **required and non-empty** for a Rajoo start — the reverse of the outgoing plan
model, where it was optional because dosing lived in the saved plan.

`mixBatchIds: List<String>` is gone; a destination start takes one mix.

### 3.5 New DTOs

```kotlin
/** One source feeding a production run. JANDI 4 and Rajoo runs carry several. */
data class RunInputDto(
    val inputRole: String = "",
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val collectionId: String = "",
    val mixBatchId: String = "",
    val sourceMixerCode: String = "",
    val productLayer: Int? = null,
)

/** The single JANDI drum. Reserved from fill until JANDI 4 consumes it. */
data class JandiDrumDto(
    val status: String = "",
    val jobCardNumber: String = "",
    val collectionId: String = "",
    val mixBatchId: String = "",
    val activeTransferCycleId: String? = null,
    val filledAtUtc: String? = null,
    val scanGuidance: String = "",
)
```

### 3.6 Reshaped response DTOs

`EquipmentDto` — `currentProductionOrderDocumentNumber` → `currentJobCardNumber`; add
`currentCollectionId`, `currentProductionRunId`, `fixedDestinationMachineCode`. Keeps `productLayer`,
`scanAllowed`, `validDestinationMachineCodes`, `routeDescription`.

`ReadyCollectionDto` — reduced to `jobCardNumber` (first), `collectionId`, `productCode`,
`productName`, `status`, `validMixerCodes`, `nextAction`.

`ReadyMixDto` — `productionOrderDocumentNumber` → `jobCardNumber`; `mixerCode` → `sourceMixerCode`.
Keeps `status`, `validNextMachineCodes`, `nextStepDescription`, `completionMode`, and the computed
`isAssignable` quarantine guard.

`ActiveCycleDto` — `jobCardNumber` first; add `mixBatchId`, `destinationMachineCode`, `productLayer`,
`status`.

`ActiveRunDto` — reduced to `productionRunId`, `machineCode`, `status`, `startedAtUtc`, plus
`inputs: List<RunInputDto>`.

`MixingOverviewResponse` — drops `mixDestinations`; adds `jandiDrum: JandiDrumDto?` and `nextAction`.

`MachineCycleResultResponse` — after the plan-field deletions, adds `destinationMachineCode`,
`productLayer`, `resultingStatus`, `inputs: List<RunInputDto>`, `sapIssuePrepared: Boolean`,
`sapPostingEnabled: Boolean`. `jobCardNumber` replaces `productionOrderDocumentNumber`.

### 3.7 Inferred field names

The requirements give prose, not JSON, for parts of the overview. These names are assumed and must
be confirmed against the backend developer's implementation. Each is a one-line DTO fix if wrong:

- `EquipmentDto.fixedDestinationMachineCode`, `.currentCollectionId`, `.currentProductionRunId`
- `ActiveCycleDto.status`, `.destinationMachineCode`
- `ActiveRunDto.status`
- every field of `JandiDrumDto`
- `MachineCycleResultResponse.resultingStatus`, `.sapIssuePrepared`, `.sapPostingEnabled`

Unknown JSON fields are ignored by Gson and absent fields fall back to defaults, so a wrong guess
degrades to a blank display rather than a crash — provided every DTO parameter keeps a default
value, which `ResponseEnvelope`'s existing note explains is also required for Gson to work at all.

## 4. Vocabulary — `data/mqtt/MqttVocabulary.kt`

### 4.1 `ErrorCode`

Remove: `MIXER_PLAN_REQUIRED`, `MIXER_NOT_IN_PLAN`, `MIXER_RESERVED`, `MIX_PLAN_LOCKED`,
`INVALID_PLANNED_LAYER_INPUTS`, `INVALID_PLANNED_DESTINATION`, `MIX_CYCLE_NOT_ACTIVE`,
`DESTINATION_ASSIGNMENT_LOCKED`, `DESTINATION_ASSIGNMENT_REQUIRED`, `DRUM_CYCLE_REQUIRED`,
`JOB_CARD_MISMATCH`, `LEGACY_REQUEST_SHAPE`.

Add: `COLLECTION_NOT_READY`, `COLLECTION_ALREADY_MIXED`, `ROUTE_REQUIRED`, `WRONG_SCAN_SEQUENCE`,
`INVALID_DESTINATION`, `RAJOO_DESTINATION_FORBIDDEN`, `JANDI_DRUM_REQUIRED`, `JANDI_DRUM_BUSY`,
`JANDI_MAIN_MIX_REQUIRED`, `AMBIGUOUS_MAIN_MIX`, `AUTHORIZATION_REQUIRED`, `AUTHORIZATION_EXPIRED`.

Retained: `SESSION_REQUIRED`, `CLIENT_UPGRADE_REQUIRED`, `INVALID_MIXING_AREA`,
`UNKNOWN_OR_DISABLED_EQUIPMENT`, `EQUIPMENT_IN_USE`, `DESTINATION_BUSY`, `CYCLE_MISMATCH`,
`SOURCE_NOT_FOUND`, `SOURCE_NOT_READY`, `SOURCE_ALREADY_ASSIGNED`, `INVALID_ROUTE`,
`INVALID_LAYER_INPUTS`, `PERMISSION_DENIED`, `MESSAGE_ID_REUSED`, `VALIDATION_FAILED`, and the
envelope/auth/Station-3 codes, which this change does not touch.

`ErrorCode` stays a value class: unknown codes must pass through intact.

### 4.2 `NextAction`

Remove `SAVE_MIXER_PLAN_IN_STATION_2`, `SCAN_SAME_MACHINE_TO_FINISH_OR_SCAN_NEXT_PLANNED_MIXER`, and
the whole parameterised `scan_reserved_mixer:` mechanism including the
`scanReservedMixerCodes` accessor — every value in the new catalogue is a plain constant.

Add: `OPEN_MIXING`, `SELECT_COLLECTION`, `SELECT_JANDI_ROUTE`, `SCAN_JANDI_DRUM_TO_START`,
`SCAN_JANDI_DRUM_TO_FINISH`, `SELECT_MAIN_DESTINATION`, `SCAN_DESTINATION_TO_START`,
`SELECT_JANDI4_MAIN_SOURCE`, `SCAN_JANDI4_TO_START`,
`SCAN_ADDITIONAL_RAJOO_LAYER_OR_FINISH_ACTIVE_LAYER`, `REFRESH_MIXING_OVERVIEW`, `COMPLETED`.

`START_MIXING` and `SELECT_COLLECTION_MIX_OR_MACHINE` are superseded by `OPEN_MIXING` and
`SELECT_COLLECTION` and are removed.

**Cross-screen consequence:** `START_MIXING` is consumed outside the board, at
`ui/mixing/MixingViewModel.kt:736`, where it drives the "collection complete → open Mixing"
navigation. That call site must move to `OPEN_MIXING` in the same change. It is the only place a
Mixing vocabulary removal reaches the collection flow, and missing it would break navigation
silently rather than at compile time — the comparison is against a value class, so a stale constant
would simply never match.

The collection-side actions already present
(`CONTINUE_COLLECTING`, `AWAIT_MANAGER_APPROVAL`, `RESCAN_APPROVED_MATERIAL`, `REFRESH_ACTIVE_JOBS`,
`LOGIN`) are unchanged; the requirements list them verbatim.

`nextAction` remains navigation guidance and never authorization.

## 5. Domain and use case

### 5.1 `domain/model/MixingBoard.kt`

Delete `MixerPlanItem`, `MixPlanProgress`, `MixDestination`, `AssignedDestination`.

- `Equipment` — drop the four plan/reservation fields; add `fixedDestinationMachineCode`,
  `currentCollectionId`, `currentProductionRunId`
- `ReadyCollection` — drop all plan fields and both `hasSavedPlan` / `needsPlan` helpers
- `ReadyMix` — `mixerCode` → `sourceMixerCode`
- `ActiveCycle` / `ActiveRun` — mirror the DTO reshape; `ActiveRun` gains `inputs: List<RunInput>`
- new `RunInput` and `JandiDrum` domain types
- `AreaOverview` — drop `mixDestinations`; add `jandiDrum: JandiDrum?` and `nextAction`
- `MachineCycleOutcome.Accepted` — drop `planProgress` and `assignedDestinations`; add
  `destinationMachineCode`, `resultingStatus`, `productLayer`, `inputs`, `sapIssuePrepared`

`MachineCycleOutcome.Rejected` keeps its nullable `areaStatus` and the reasoning behind it: an
envelope-level rejection carries no area data, and the board must keep its current picture rather
than blank it. That behaviour was a fix (`3129`) and survives this change.

### 5.2 `domain/usecase/MixingBoardUseCase.kt`

`assignDestinations()` is deleted outright.

`fetchOverview(area, jobCardNumber, collectionId)` — all optional.

`fetchCollectedMaterials(collectionId)` — the resume request no longer sends `jobCardNumber`; Station
2 returns it.

The single `startMixer` / `startRajoo` / `startDownstream` trio is replaced by six functions, one per
legal start variant. This is the design's main safety property: the payload's optional fields can
only be populated in valid combinations, because nothing else constructs it.

```kotlin
suspend fun startMixerFromCollection(machineCode: String, collectionId: String): MachineCycleOutcome
suspend fun startJandiMixer(machineCode: String, collectionId: String, route: String): MachineCycleOutcome
suspend fun startRajooLayer(machineCode: String, collectionId: String, doses: List<LayerInput>): MachineCycleOutcome
suspend fun startDrumTransfer(machineCode: String, mixBatchId: String): MachineCycleOutcome
suspend fun startProductionDestination(machineCode: String, mixBatchId: String): MachineCycleOutcome
suspend fun startJandi4(machineCode: String, mainSourceMixBatchId: String?, mainSourceMixerCode: String?): MachineCycleOutcome
```

`startRajooLayer` rejects locally when `doses` is empty, has more than five entries, or contains a
non-positive quantity — the operator gets the message immediately instead of a round trip to
`invalid_layer_inputs`. `startJandi4` requires exactly one of its two source arguments.

`finish(machineCode, cycleId)` and `forceClose(...)` are unchanged, including the SCRAM-scoped
manager authorization, which the new requirements restate rather than alter.

## 6. UI

Decision: keep the board's structure, restyle for JC-first. The existing source-first shape
(select a source → machine grid → confirm dialog) already matches the new lifecycle.

### 6.1 ViewModel — `ui/mixing/board/MixingBoardViewModel.kt`

- `BoardSelection.Mixes(List<String>)` → `BoardSelection.Mix(mixBatchId, jobCardNumber)`. The
  same-JC multi-select rule disappears with it.
- `computeHighlightedMachines` is rewritten. The plan-aware branch (`remainingMixerCodes`
  intersected with scannable equipment) is replaced by: a selected collection highlights its
  `validMixerCodes`; a selected mix highlights its `validNextMachineCodes`; both intersected with
  equipment where `scanAllowed` is true. It stays a pure function, unit-tested without the ViewModel.
- `BoardSheet.StartConfirm` gains `routeOptions` / `selectedRoute` (JANDI) and `mainSourceOptions` /
  `selectedMainSource` (JANDI 4). Confirm stays disabled until a required choice is made.
- New ViewModel-local `cachedMainMixerCode: String?`, set when the operator scans a Main mixer code
  while a JANDI 4 start is pending, cleared on successful start or on clearing selection. The
  requirements are explicit that this is client-side only: "There is no separate source-selection
  MQTT mutation."
- Dispatch in `machineChosen` reads the role and area: a `ProductionMachine` now goes to
  `startProductionDestination`, not to the deleted assignment call.

### 6.2 Screen — `ui/mixing/board/MixingBoardScreen.kt`

- The JANDI route picker and JANDI 4 source picker render inside the existing `StartConfirm` dialog
  alongside the Rajoo dose sheet, which becomes mandatory rather than optional.
- A drum status card renders from `overview.jandiDrum` when present, in the JANDI area only.
- Composite runs (JANDI 4, Rajoo) list their `inputs[]` so an operator can see two different JCs
  feeding one run — the requirement that mixed-JC runs are legal is only meaningful if it is visible.
- **JC-first pass.** On every card the job card is the largest value; `COL_ID`, `MIX_ID`, `CYC_ID`
  and `RUN_ID` move to a small muted secondary line. Today's cards lead with `collectionId` and
  `mixBatchId` (`"${collection.collectionId} · JC ${collection.jobCardNumber}"`), which is exactly
  backwards under the new requirements.

Removed from the UI: every plan control, plan count, plan label, the "save the plan at the desk"
empty state, and the destination-assignment confirmation step.

## 7. Testing

Unit tests only — see §1 on the verification ceiling.

- `MixingBoardUseCaseTest`: one wire-shape test per start variant asserting the exact set of fields
  serialized, **and** the absence of plan fields and of `mixBatchIds`; the local Rajoo dose
  validation; the JANDI 4 exactly-one-source rule; DTO→domain mapping for `inputs[]` and `jandiDrum`.
- `MixingBoardViewModelTest`: highlight computation from `validMixerCodes` / `validNextMachineCodes`
  / `scanAllowed`; single-mix selection; route required before a JANDI mixer start; the JANDI 4
  cached-mixer-code lifecycle; production-machine dispatch reaching `machine_cycle_start_requested`.
- A guard test asserting no code path emits `mix_destination_assignment_requested`.
- Error-code and `nextAction` mapping tests covering the added values, and one asserting an unknown
  code still passes through intact.

Three existing test files reference constants this change removes and must be updated with it:
`MqttVocabularyTest` (asserts the raw strings of `START_MIXING` and
`SELECT_COLLECTION_MIX_OR_MACHINE`), `MixingBoardUseCaseTest`, and `MixingUseCaseTest` (both use
`NextAction.START_MIXING` as a stub value).

Other non-Mixing tests (auth, pallets, ingredients, envelope, paging) must keep passing untouched;
this change should not reach them.

## 8. Risks

1. **Inferred field names** (§3.7). Mitigated by Gson's tolerance and by listing them for
   confirmation, but a mismatch means a blank field the operator needs.
2. **No end-to-end verification** until the backend or the sim catches up. The first real run will
   find things unit tests cannot.
3. **Contract drift.** The backend developer is implementing from the same requirements document but
   independently. `RFID_MQTT_CONTRACT.md` is still the old plan-based version, so there is currently
   no shared written source of truth. Worth resolving before integration.
