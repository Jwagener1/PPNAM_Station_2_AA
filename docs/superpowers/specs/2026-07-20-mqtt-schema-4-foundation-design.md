# MQTT Schema 4.0 — Foundation (SP4a) Design

**Date:** 2026-07-20
**Status:** Approved design.
**Contract:** `C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` v4.0, last updated 2026-07-20 (read-only reference)
**Supersedes:** `2026-07-16-mqtt-schema-3-hopper-cycles-design.md` (SP4, hopper cycles) — retired by contract v4.0
**Depends on:** SP1 (`0c3dd9e`), SP2 (`f970531`), SP3 (`d6b4a85`), backend simulator (`5d93f81`)

## Why the re-plan

Contract v4.0 retires every v3 production mutation: Hopper overview/routing, Pre-Mix
allocation/finalization, direct/full-pallet/bag allocation, Extruder/Rajoo run
assignment/finish, and v3 machine-cycle start/finish/force-close. The replacement is
unified area-aware Mixing across five fixed areas (DOLCI, Main, JANDI, Mackie, Rajoo):

- `mixing_overview_requested` → `mixing_overview_result` (the board: equipment,
  active cycles, ready mixes, active runs; server-authoritative availability).
- `machine_cycle_start_requested` → `machine_cycle_result`, one message dispatched by
  the scanned machine's family: mixer (exactly one `collectionId` → creates
  `MIX_######` + `CYC_######`), Rajoo mixer (adds 1–5 `layerInputs`), JANDI drum
  (exactly one completed JANDI `mixBatchId`), production machine (1+ same-JC
  `mixBatchIds`, route-checked, accumulating into a `RUN_######`).
- `machine_cycle_finish_requested` / `machine_cycle_force_close_requested` address
  the exact `machineCode` + `cycleId`; re-finish is an idempotent no-op
  (`alreadyFinished: true`); force-close carries inline Manager/Admin credentials.

The old SP4/SP5 split (hopper cycles, then allocation) no longer maps to the contract.
v4 absorbs both. The re-planned split, **decided 2026-07-20**:

- **SP4a (this spec):** v4 foundation — simulator rework, app-wide schema bump,
  the three SP3 carry-over fixes, rewritten selftest. No new mixing UI.
- **SP4b (next spec):** the five-area mixing UI and cycle flows.

The v3 big-bang principle carries forward: **nothing ships until SP4b lands mixing.**

## What survives, what dies

**Survives untouched:** SP1 transport, SP2 auth/session, SP3 capture flows. The v4
envelope is byte-identical to v3; capture messages change only `schemaVersion`.
The old SP4 spec's *principles* survive into SP4b: server decides availability,
finish names the cycle not the machine, force-close approval is inline and checked
server-side against the approver's account.

**Dies:** the scan-to-accumulate hopper board, batch `machineCodes[]` submit,
partial/final finish semantics, shared-pre-mix model, `conflicts[]`, `hoppers[]` in
scan results, and all allocation messages. `machine_cycle_start_requested` in v4
**rejects** the v3 array fields (`machineCodes`, `collectionIds`, `preMixId`,
`preMixIds`) with `legacy_request_shape`.

## Backend survey facts this design leans on (verified 2026-07-20)

- Backend enforces `SupportedSchemaVersion = "4.0"`; schema 3.0 is temporarily
  accepted **only** for login/logout, pallet lookup, holding recovery, job lists,
  load/resume, cancel, and ingredient scanning (§12). Every retired v3 production
  request gets `client_upgrade_required` on `res/workflow_upgrade_required`.
- **Backend replay gap:** on the 4.0 path the backend does *not* compare the request
  body hash — a reused `messageId` with changed content silently returns the stored
  prior response instead of `message_id_reused`. The app must never rely on the
  server to catch messageId reuse; correction = new `messageId`, always.
- `errorCode` (and on some legacy responses `inResponseToMessageId`) is **omitted**
  from JSON when null, despite contract examples showing `"errorCode": null`.
  Parsers treat both as optional.
- Only `login_requested` is contract-sanctioned for login; `reader_login_requested`
  and `login_tag_scanned` are legacy extras the app must not use.
- Successful login returns `nextAction: ""` — empty means "no forced navigation".

## Decisions (user-adjudicated 2026-07-20 — do not re-litigate)

1. **Packaging:** two sub-projects, SP4a then SP4b (over one-big-SP4 or three-way split).
2. **Schema bump:** everything to `"4.0"` in SP4a, including capture flows. Nothing
   has shipped; running on the §12 compatibility window would only defer the change.
3. **Simulator replay is contract-strict:** body-hash comparison → `message_id_reused`
   on **all** paths, including 4.0. Deliberately stricter than the real backend so
   accidental messageId reuse in the app surfaces as a clear error in testing rather
   than as confusing stale data in production.
4. **All three SP3 carry-over gaps land in SP4a** (they are capture-side; SP4b
   depends on gap 3; the simulator exercises gaps 1 and 3 directly).
5. **Simulator approach: replace, not add-alongside.** The real backend hard-retires
   v3 mutations; a simulator that still accepted them would accept traffic production
   rejects — the worst property a test double can have.

## Design

### 1. Architecture and scope

One branch (`mqtt-schema-4-foundation`), four deliverables:

1. Simulator v4 rework (`tools/backend-sim/`).
2. App-wide `schemaVersion` bump to `"4.0"` plus retired-token cleanup.
3. SP3 carry-over fixes (gaps 1–3, re-framed for v4 below).
4. Selftest rewritten for v4.

SP4a leaves the app contract-valid for capture and deliberately without mixing UI:
a collection reaching `ReadyForMixing` (`nextAction: "start_mixing"`) shows an
honest "mixing arrives in the next update" placeholder — the SP1 pattern of never
shipping a control that silently does nothing.

### 2. Simulator v4 rework

**State (`state.py`):** drop hopper/pre-mix entities. Add `Equipment` (code, area,
role `Mixer|Transfer|ProductionMachine`, enabled, status `Available|InUse|Disabled`,
current cycle/JC/mix links), `MixBatch` (`MIX_######`), `Cycle` (`CYC_######`),
`ProductionRun` (`RUN_######`). The five-area topology is declarative seed data
using the contract's example codes:

| Area | Mixers | Valid next equipment |
|---|---|---|
| DolciBulkMixing | 3 fixed mixers | Pair 1→DOLCI 1, Pair 2→DOLCI 2, Pair 3→DOLCI 11 |
| MainMixingRoom | `MXR-01..05` | any of `EXT-01..25` |
| JandiBulkMixing | `JAN-MIX-01` | `JAN-02`/`JAN-03` direct; `JAN-DRUM-01` gates `JAN-04` |
| MackieBulkMixing | 1 grey mixer | fixed Mackie extruder |
| RajooMachineMixing | `RAJ-GM-01..03` (layers 1–3) | one 3-layer Rajoo extruder |

**Handlers:** `handlers/cycles.py` and `handlers/allocations.py` are deleted outright
(their topics are either retired or reborn under v4 semantics); one new
`handlers/mixing.py` owns all of the following:
- `mixing_overview_requested`: optional `mixingArea` / `productionOrderDocumentNumber`
  filters; unknown area → `invalid_mixing_area`; returns `equipment`, `activeCycles`,
  `readyMixes` (with `validNextMachineCodes`), `activeRuns`.
- `machine_cycle_start_requested`: dispatch by scanned machine family. Mixer start
  claims the collection atomically (one claim per collection, ever) and creates one
  `MIX_` + one `CYC_`. Rajoo start additionally validates 1–5 positive doses against
  collected quantities (`invalid_layer_inputs`). Drum start takes exactly one
  completed JANDI mix. Production start validates every mix is same-JC
  (`job_card_mismatch`), routed (`invalid_route`), unassigned
  (`source_already_assigned`), ready (`source_not_ready`), and accumulates into an
  existing active run on the same machine+JC. Presence of any v3 array field →
  `legacy_request_shape`.
- `machine_cycle_finish_requested` / `..._force_close_requested`: require
  `machineCode` + `cycleId`; stale/foreign cycle → `cycle_mismatch`; re-finish →
  accepted `alreadyFinished: true`; force-close verifies the approver holds
  `machine_force_close` (Manager/Admin) else `permission_denied`, echoes approver
  identity, redacts credentials in logs.
- Every result embeds `areaStatus` (refreshed area overview) per §8.
- Both SAP flags remain `false` (§13 item 14).

**Registry:** delete v3 production routes; a retired-topic guard answers every
retired suffix with `accepted: false`, `errorCode: client_upgrade_required`,
`nextAction: upgrade_reader_for_mixing` on `res/workflow_upgrade_required` —
mirroring the backend and acting as a tripwire for any v3 call left in the app.

**Envelope:** `"4.0"` accepted everywhere; `"3.0"` accepted only for the §12 capture
actions; replay contract-strict on all paths (Decision 3). Three-layer logging
(wire.jsonl, sim.log validation narrative including dose/route arithmetic,
state snapshots) extends to mixing.

### 3. App changes

- **Schema bump:** the SP1 schema-version constant flips to `"4.0"`. Dead-token
  sweep for retired v3 action strings, verified by the same grep used at SP3 close.
- **Gap 1 — quantity-only scan path:** a bulk BOM line arms for direct-weight entry
  (numeric field, not the bag picker) and `scanIngredient` sends `quantity` instead
  of `bagSizeOption`/`bagCount`. The two shapes are mutually exclusive on the wire.
  Arming a bulk line for a bag scan becomes impossible by construction.
- **Gap 2 — waiver dialog visibility:** the first-attempt short-bag waiver dialog
  moves from local Compose state into `MixingUiState`, so the ViewModel scan guard
  sees it and swallows stray RFID reads while it is open.
- **Gap 3 — enriched Accepted outcome:** `IngredientScanOutcome.Accepted` carries
  the refreshed `collectionSummary`, collection status, `overCollectionToleranceBags`,
  and `nextAction` through the use-case boundary. (`hoppers[]` is gone from the
  contract; that half of the old gap closes by deletion.)
- **Upgrade signal:** `errorCode: client_upgrade_required` is recognized generically
  and surfaces as a blocking "update required" state, not an anonymous failure.
  The existing `res/+` subscription already covers `res/workflow_upgrade_required`.

### 4. Error handling and testing

**Selftest (rewritten for v4):** keep passing envelope/capture checks; replace all
cycle/allocation checks with:

- strict replay: identical resend → stored response; same id + changed body →
  `message_id_reused` (on a 4.0 topic, deliberately beyond the real backend);
- mixer/drum/Rajoo/production start acceptance paths, `MIX_`/`CYC_`/`RUN_` id shapes,
  run accumulation, `cycleId == productionRunId` on production cycles;
- every §10 rejection: `invalid_mixing_area`, `legacy_request_shape`,
  `unknown_or_disabled_equipment`, `equipment_in_use`, `cycle_mismatch`,
  `source_not_found`, `source_not_ready`, `source_already_assigned`,
  `job_card_mismatch`, `invalid_route`, `drum_cycle_required`,
  `invalid_layer_inputs`, `permission_denied`, `validation_failed`;
- JANDI 4 blocked until its exact drum cycle finishes; unblocked after;
- second claim of a claimed collection rejected; re-finish idempotent;
- retired-topic guard: a v3 mutation topic gets `client_upgrade_required` on
  `res/workflow_upgrade_required`;
- 3.0 accepted for a capture action, rejected for a mixing action.

**App unit tests:** schema bump reflected in every outgoing message; quantity-only
scan path (mutual exclusivity of `quantity` vs bag fields); bulk line cannot arm a
bag scan; waiver-dialog scan guard; enriched Accepted outcome mapping; generic
`client_upgrade_required` handling; missing `errorCode` parsed as no-error.

**Acceptance for SP4a:** full selftest green (`selftest.py --direct`), app unit
tests green, debug build succeeds, and one manual capture run against the live
simulator reaching `ReadyForMixing` with the placeholder shown.

## Out of scope (SP4b)

Five-area mixing UI, the board screen, machine scan/start/finish/force-close flows,
JANDI drum UX, Rajoo dose entry, run accumulation UX, and navigation from
`start_mixing` into all of it.

## Open questions for the Station 2 developer

1. Real machine codes for DOLCI/Mackie/Rajoo-extruder (contract shows examples for
   Main/JANDI/Rajoo mixers only). Simulator uses placeholder codes until confirmed;
   the topology table is seed data, trivially re-coded.
2. Will the 4.0 replay path gain the body-hash check the contract mandates (§11)?
   The app assumes not and self-polices messageId hygiene either way.
3. Carried from SP3, still open: message-specific `errorCode` values beyond the
   stable set, and confirmation that `station_2` is the literal presence-topic id
   (SP2's banner depends on it; the backend survey found it hard-coded as
   `StationDeviceId = "station_2"`, which answers this unless renamed).
