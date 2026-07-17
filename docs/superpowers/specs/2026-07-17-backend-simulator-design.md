# Station 2 Backend Simulator — Design Spec

**Date:** 2026-07-17
**Status:** Approved (user, 2026-07-17)
**Goal:** A Python program that mimics the Station 2 WPF backend over MQTT so the Android app can be tested end-to-end before the backend developer is involved, with extensive logging as a second source of truth when diagnosing problems.

## Decisions (user-confirmed)

- **Broker:** the existing broker (`mqtt.sysone.co.za:1883` by default, overridable). The simulator is a *client*, exactly like the real Station 2.
- **Scope:** the **full v3 contract** (`C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md`, schema `3.0`) — all 19 request/response pairs, including SP4+ machine cycles and allocations not yet built in the app.
- **Behaviour:** auto-respond only; no interactive fault-injection console.
- **Seed data:** realistic, derived from `SAP_Sample_Data` production orders (copied into the tool; the sibling repo stays untouched).

## Architecture

Plain Python 3.10+ package at `tools/backend-sim/`, single external dependency `paho-mqtt` (v2 API, MQTT v5 to match the app's HiveMQ client). All requests are processed on **one worker thread** — the contract requires serialized mutations, so single-threaded handling is correct by construction.

```
tools/backend-sim/
  sim.py            entry point + MQTT client + worker loop + CLI args
  envelope.py       8-step contract validation order; response envelope builder
  state.py          in-memory world + ID counters + replay store + seed loading
  simlog.py         logging subsystem (wire.jsonl, sim.log, snapshots, console)
  handlers/         one module per message family:
    auth.py         login_requested, reader_logout_requested
    pallets.py      pallet_lookup_requested, holding_recovery_requested
    jobcards.py     active/open job card lists, job_card_load, collection_resume, cancel
    ingredients.py  ingredient_scan_requested (scans, tolerance, approvals, waivers)
    cycles.py       machine_cycle start / finish / force_close, hopper_overview
    allocations.py  full_pallet / bag allocation, allocation_action, allocation_overview
    completion.py   station2_work_complete_requested
  seed/
    seed.json       operators, machines, pallets, config defaults
    prod-*.json     SAP order dumps copied from SAP_Sample_Data
  selftest.py       fake handheld driving the contract's 12-step acceptance flow
  README.md         setup + how to point the app at it
  requirements.txt
```

### MQTT surface

- Publishes retained `online` on `PPNAM/station_2/status`; LWT and clean-exit publish retained `offline`.
- Subscribes `PPNAM/+/req/+` (QoS 1) and `PPNAM/+/status`.
- Responses go to `PPNAM/{deviceId}/res/{responseType}`, QoS 1, not retained.
- **Collision guard:** presence on `PPNAM/station_2/status` is monitored; if the real backend appears `online`, the simulator prints a loud warning (or exits, with `--yield-to-real`).

### Validation (envelope.py)

Implements the contract's validation order exactly, one log line per step:
JSON parse → envelope fields → replay identity/body-hash lookup → schema/topic-device/configured-device/timestamp → device-bound session → permission (privileged actions) → business rules (handler) → persist → respond + store replay record. All 14 common error codes are produced from the correct step. Replay lookup happens **before** timestamp rejection. Timestamp window defaults to ±300 s (`--window`); clock skew is measured and logged on every request even when accepted.

### State model (state.py)

- **Operators:** `operator1/pass` (ordinary), `manager1/secret` (all six privileged action ids), badge `BADGE001` → operator1. `allowedActions`/`allowedTabs` returned as display hints.
- **Sessions:** per-device; `Active`/`Suspended`/`Closed`; presence-driven suspend/resume; valid request resumes a Suspended session; 16 h expiry; login closes the prior session on that device.
- **Machines:** hoppers MXR-01, MXR-02, MXR-03 (03 seeded `Inactive`), extruders EXT-03, EXT-04, rajoo RAJ-01. Family comes from configuration, never from the request.
- **Pallets:** ~7 seeded to cover every `palletState` axis: Holding (usable), Holding+blocked, Mixing, AtStation1 (recoverable), Unknown (recoverable), Consumed, plus a low-remaining pallet. Products match the SAP BOM materials so scans validate.
- **Materials:** full-bag weight 25 kg default; at least one BOM material seeded as **bulk** (`bagSize: null`) to exercise bulk-line semantics (no tolerance, `*Bags` fields null).
- **Collections / pre-mixes / cycles / runs / allocations:** counters issue `COL_`/`PMX_`/`CYC_`/`ROUTE_`/`RUN_`/`ALLOC_` ids; lifecycle per contract (shared pre-mix across hoppers, `ReadyForAllocation` on final finish, `alreadyFinished`/`alreadyActive` no-ops, atomic multi-hopper claims with `conflicts[]`).
- **Replay store:** in-memory `(deviceId, requestType, messageId) → (bodyHash, storedResponse)`.

### Business rules of note

- Bag math in **full-bag equivalents** everywhere; `weight = bagCount × fraction × fullBagWeight`.
- Over-collection ≤ `overCollectionToleranceBags` (default 1, `--tolerance`) auto-accepted, crediting only the remaining requirement while recording full `weightReceived`; beyond that: reject with `requiresManagerApproval: true` / `retry_with_manager_approval`; the approved retry must be a **new** `messageId` (old one with credentials → `message_id_reused`).
- Short-bag waiver carries credentials on first submission, requires `requestedMaterialCode`, adjusts the requirement, never creates a scan line.
- Privileged actions authenticate the **approver's** account and check the action id against the approver's `allowedActions`; sender's session/role is never consulted; responses carry `approverUserId`/`approverDisplayName`/`approverRole` (null when non-privileged).
- The hopper board is included in every response the contract lists, and always shows all configured hoppers.
- `im_Backflush` lines stay in the snapshot but are excluded from the handheld `ingredients[]`. UoM 269→`kg`, 268→`each`.
- Finish is addressed by `machineCode` + `cycleId`; finishing a finished cycle is an accepted no-op, never touches the machine's newer cycle.

### Logging (simlog.py) — the second source of truth

Each run creates `tools/backend-sim/logs/<UTC timestamp>/`:

1. **`wire.jsonl`** — one JSON line per MQTT message, both directions: `ts`, `dir` (`in`/`out`), `topic`, `qos`, `payload` (full JSON), plus `inResponseToMessageId` linkage. Passwords redacted per contract but flagged as `"<redacted:present>"` so credential presence is still visible.
2. **`sim.log`** — human-readable narrative: per-request validation trace (each of the 8 steps, pass/fail and why), business decisions with arithmetic shown ("3×1/2 bags = 1.5 full-bag eq; remaining 2.1 → accepted"), and every state transition (session, collection, hopper, pre-mix, cycle, allocation).
3. **`state-snapshots/NNNN-<event>.json`** — the full world state after every accepted mutation, so app-vs-backend disagreements can be diffed step by step.
4. **Console** — colour-coded one-liners mirroring `sim.log`.

### Self-test (selftest.py)

Two transports: `--direct` runs the simulator **in-process** (no broker needed —
added because the shared broker is not always reachable from the dev PC), while
the default mode connects over MQTT to exercise the real transport. In both, a
fake handheld (`handheld_selftest`) drives: presence → login → open SAP job cards → job load → ingredient scans (including one over-tolerance rejection + approved retry and one short-bag waiver) → `choose_destination` → two-hopper start → partial finish → final finish (`ReadyForAllocation`) → extruder start consuming the pre-mix → finish → `station2_work_complete`. Plus negative probes: wrong schema, replayed messageId with changed body, stale timestamp, request on closed session. Exits non-zero on any contract deviation — run this before trusting the simulator to judge the app.

## Error handling

- Unknown request topics: logged, no workflow side effect (per contract), no response.
- Malformed JSON: `invalid_json` response is still sent when a response topic can be derived; always logged.
- Broker connection loss: paho auto-reconnect; presence re-published on reconnect; queue of in-flight work preserved (in-memory state survives reconnects, not restarts — restarts are a fresh world, which is desirable for repeatable tests).

## Out of scope

- Fault injection / interactive console (explicitly declined).
- TLS and broker auth (test broker is open; flags exist for host/port only).
- Persistence across restarts; SAP posting; fixed-door-reader TCP integration; v2 adapter.
