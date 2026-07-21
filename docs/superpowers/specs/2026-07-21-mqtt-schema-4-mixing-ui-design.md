# MQTT Schema 4.0 — Five-Area Mixing UI (SP4b) Design

**Date:** 2026-07-21
**Status:** Approved design.
**Contract:** `C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` v4.0 (read-only reference)
**Depends on:** SP4a (`0198f5a` merge, 2026-07-21) — schema 4.0 foundation, v4 simulator (109-check selftest), enriched scan outcome, `upgradeRequired` latch.
**Completes:** the v4 big-bang surface. Nothing has shipped since SP1; SP4b is the release gate's last code prerequisite (releasing itself remains a separate decision with the Station 2 developer).

## Scope

The five-area Mixing UI and cycle flows: area picker + per-area board from
`mixing_overview_requested`, source-first start of mixer/Rajoo/drum/production
cycles via `machine_cycle_start_requested`, finish and manager force-close via
the cycle sheet, run accumulation, JANDI drum gating, Rajoo dose entry, and
navigation from `nextAction: "start_mixing"`. Plus the SP4a final-review
carry-ins (listed under Cleanups).

## Decisions (user-adjudicated 2026-07-21 — do not re-litigate)

1. **Entry:** auto-navigate to the board when a response carries
   `nextAction: "start_mixing"`, plus a persistent "Mixing" button on
   `JobLookupScreen`. The now-enabled button on `IngredientScanScreen` is a
   third entry.
2. **Machine selection:** scan-first with tap fallback. A machine tag's scanned
   value is its `machineCode`.
3. **Board layout:** area picker (five fixed cards with live summaries) → one
   per-area board using the `mixingArea` overview filter.
4. **Start flow: source-first everywhere.** Select the collection or mix(es)
   on the board first; valid destination machines highlight; scan or tap fires
   the start. (Chosen over machine-first — the operator confirms material
   before walking to a machine, and it matches §13.8's "render destination
   choices from validNextMachineCodes".)
5. **Finish:** cycle sheet with confirm. Scanning/tapping an InUse machine
   opens its active-cycle sheet; `[Finish cycle]` and `[Force close…]`
   (manager credentials + audit reason) live there. A stray scan only opens
   the sheet.
6. **Architecture:** new vertical slice (`MixingBoardUseCase`,
   `MixingBoardViewModel`, `ui/mixing/board/` screens). The SP4a capture flow
   gains only the navigation hook and the enabled button.

## Design

### 1. Screens and navigation

New nested nav graph `MIXING_BOARD` with two destinations:

- **`MixingAreaPickerScreen`** — the five fixed areas as cards, each with a
  live summary (available machines / active cycles / ready mixes) from one
  unfiltered `mixing_overview_requested` on entry. Auto-navigation lands here:
  a collection is destination-neutral, so the app never guesses the area — a
  banner shows "COL_… ready to mix — pick an area" and the collection arrives
  pre-selected on whichever board the operator opens.
- **`MixingBoardScreen`** (per area) — from a `mixingArea`-filtered overview:
  - *Ready collections* — from `active_job_cards_requested` filtered to
    status `ReadyForMixing` (the overview only lists mixes, not collections).
  - *Ready mixes* — each card shows JC, mixer of origin, and its
    `validNextMachineCodes` chips.
  - *Machines* — status-colored grid (Available / InUse / Disabled) built
    strictly from `areaStatus.equipment` (`status`/`isAvailable`; §13.7 —
    never inferred locally).
  - *Active cycles & runs* — with cycle ids and started-by.

Entry points: `MixingViewModel` emits a navigation event when an accepted
scan/waiver outcome carries `NextAction.START_MIXING` (the enriched Accepted
outcome from SP4a); `JobLookupScreen` gets a permanent "Mixing" button;
`IngredientScanScreen`'s placeholder button becomes an enabled "Start Mixing"
when `collectionStatus == "ReadyForMixing"` and navigates with that
collection. Refresh on board entry, on MQTT reconnect, and after every machine
result (§13.11). No polling.

### 2. Source-first interaction

Tapping a ready collection or ready mix enters selection mode:

- One collection at a time. Selecting a collection highlights the area's
  enabled + Available **mixers**.
- Mixes are multi-selectable; once the first is selected, mixes of other JCs
  grey out (client-side mirror of `job_card_mismatch` — server stays
  authoritative). Highlighted destinations = the intersection of the selected
  mixes' `validNextMachineCodes`, plus any production machine InUse **on the
  same JC with an active run** (accumulation into its `RUN_`).
- JANDI gating comes free from server data: pre-drum a JANDI mix lists
  `JAN-DRUM-01`/`JAN-02`/`JAN-03`; after its exact drum cycle, `JAN-04`.

Firing the start: with a selection active, **scanning any machine selects it**
— scan is trusted intent, even for a non-highlighted machine (the server
answers a wrong choice with its precise §10 code after confirm). **Tapping**
works only on highlighted cards. Both paths open a confirm sheet
summarizing the request; on a Rajoo mixer (`RAJ-GM-*`) the sheet adds dose
entry — one row per collected material, up to five, positive doses
pre-validated against collected quantities (server re-validates with
`invalid_layer_inputs`). The confirmed sheet sends `machine_cycle_start_requested`
with exactly one of `collectionId` / `mixBatchIds` (+ `layerInputs` for Rajoo)
— never the retired v3 array fields.

### 3. Finish and force-close

Scanning or tapping an **InUse machine with no selection active** opens its
active-cycle sheet: cycle id, JC, affected mixes, started-by/at.

- `[Finish cycle]` → `machine_cycle_finish_requested` with the exact
  `machineCode` + stored `cycleId` (§13.6 — never invented). An
  `alreadyFinished: true` reply is success.
- `[Force close…]` → the established manager-credentials + audit-reason dialog
  (same pattern as cancel/waiver; blank fields fail closed client-side).
  The response's approver identity is surfaced in the result message.

### 4. Results, errors, refresh

Every `machine_cycle_result` — accepted or rejected — embeds a refreshed
`areaStatus`; the board updates from it directly, no extra overview
round-trip. Rejections show the server `reason` in a snackbar and apply the
embedded refresh. `session_required` and `client_upgrade_required` remain
globally handled by the transport (SP2/SP4a). The board ViewModel ignores
scan events while a sheet is open or a request is in flight (the capture
screen's scan-guard discipline). `mixing_overview` rejections
(`invalid_mixing_area` should be unreachable — areas are a fixed enum) fall
back to an error state with retry.

### 5. Architecture (new vertical slice)

- `data/mqtt/dto/MixingMessages.kt` — `MixingOverviewPayload`,
  `MixingOverviewResponse` (equipment/activeCycles/readyMixes/activeRuns
  DTOs), `MachineCycleStartPayload` (machineCode, productionOrderDocumentNumber,
  optional collectionId / mixBatchIds / layerInputs), finish and force-close
  payloads, `MachineCycleResultResponse` with `areaStatus:
  MixingOverviewResponse`. Omitted `errorCode`/nulls tolerated as everywhere.
- `domain/model/MixingBoard.kt` — `MixingArea` (five fixed values),
  `Equipment`, `ReadyMix`, `ActiveCycle`, `ActiveRun`, `MachineCycleOutcome`.
- `domain/usecase/MixingBoardUseCase.kt` — `fetchOverview(area?, jc?)`,
  `fetchReadyCollections()`, `startMixer`, `startRajoo`, `startDownstream`
  (drum and production share the mixBatchIds shape), `finish`, `forceClose`.
  Typed outcomes always carry the embedded areaStatus.
- `ui/mixing/board/` — `MixingBoardViewModel` (own sealed
  `MixingBoardUiState`: `AreaPicker`, `Board` with selection + sheet
  sub-states), the two screens, sheet composables. Dark-graphite/amber design
  system.
- Navigation: `MIXING_BOARD` routes; `MixingViewModel` gains only the
  `START_MIXING` navigation event; `onProceedToMixing` wired and enabled.

### 6. Cleanups folded in (SP4a final-review carry-ins)

- Hoist the `upgradeRequired` blocking dialog from `IngredientScanScreen` to
  an app-level scaffold so it blocks the board too.
- Simulator: strip the vestigial nested `accepted` from `area_overview()`
  (adjust the one selftest assertion).
- Add the two deferred tests: quantity-shaped manager-approval resubmit;
  `openShortBagWaiver` refusal branches.
- Delete dead `HomeScreen.kt`/`HomeViewModel` (+tests) — unreferenced since
  the Jul 16 navigation restructure.

### 7. Testing and acceptance

- TDD unit tests: use case (payload shapes incl. exactly-one-source rule,
  outcome mapping, areaStatus propagation) and ViewModel (selection rules,
  same-JC greying, accumulation highlighting, scan guard, sheet flows,
  navigation events).
- Integration harness: the SP4a simulator (`tools/backend-sim/`, 109-check
  selftest stays green; only the `area_overview()` cleanup touches it).
- Acceptance: the contract §14 end-to-end flow run manually against the live
  simulator — capture → ReadyForMixing → auto-navigate → mixer start/finish →
  downstream start with accumulation → JANDI drum gate → Rajoo dose start →
  force-close — plus SP4a's still-deferred manual capture checks (4.0-only
  wire traffic, `***` password redaction).

## Out of scope

Release/rollout (separate decision with the Station 2 developer), real machine
codes (simulator placeholders stand until confirmed), any SAP behavior (both
SAP flags stay false and are never displayed as SAP activity), offline
queueing of mixing actions.

## Open questions for the Station 2 developer (carried forward)

1. Real DOLCI/Mackie/Rajoo-extruder machine codes (sim topology is seed data,
   trivially re-coded).
2. Whether the 4.0 replay path gains the §11 body-hash check (app self-polices
   messageId hygiene regardless).
3. Confirm force-close semantics: the sim voids the mix and releases the
   collection claim back to ReadyForMixing — is that the backend's behavior?
