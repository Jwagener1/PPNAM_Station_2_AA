# Station 2 Backend Simulator

Mimics the Station 2 WPF backend over MQTT (RFID contract **v3**, schema `3.0`)
so the Android app can be tested end-to-end without the real backend. Every
message, validation step, business decision, and state change is logged, so
when the app misbehaves the logs are a second source of truth for diagnosis.

## Setup

```powershell
cd tools\backend-sim
pip install -r requirements.txt
python sim.py                      # uses mqtt.sysone.co.za:1883
python sim.py --host 10.1.50.1     # or the factory broker
```

Point the app at the same broker in its Settings screen. The simulator plays
`station_2`: retained `online` presence with an `offline` LWT, subscribes to
`PPNAM/+/req/+` and `PPNAM/+/status`, and answers on `PPNAM/{deviceId}/res/*`.

**Collision warning:** if the real Station 2 backend is online on the same
broker, both backends will answer every request. The simulator checks the
retained `PPNAM/station_2/status` at startup and warns loudly; pass
`--yield-to-real` to make it exit instead. Note that the simulator also writes
that retained status topic (`online` on start, `offline` on exit).

## Options

| Flag | Default | Meaning |
| --- | --- | --- |
| `--host` / `--port` | `mqtt.sysone.co.za` / `1883` | Broker |
| `--window` | 300 | Timestamp acceptance window (seconds) — raise it if the device clock drifts |
| `--tolerance` | 1.0 | Over-collection tolerance in full bags |
| `--yield-to-real` | off | Exit if the real Station 2 is online |
| `--no-color` | off | Plain console output |

## Logs (per run: `logs/<UTC-timestamp>/`)

- **`wire.jsonl`** — every MQTT message in/out with full payload. Passwords are
  redacted per contract but flagged (`<redacted:present>`) so you can still see
  whether the app sent credentials. `messageId`/`inResponseToMessageId` are
  lifted to the top level for easy grepping.
- **`sim.log`** — the narrative: each request's 8 validation steps, bag-math
  arithmetic, why something was accepted/rejected, and every state transition
  (sessions, collections, hoppers, pre-mixes, cycles, allocations). Clock skew
  is logged on every request even when accepted.
- **`state-snapshots/`** — full world state after every accepted mutation;
  diff consecutive snapshots to see exactly what the backend believed.
- **Console** — colour-coded mirror of `sim.log`.

## Seed world

State is in-memory only; restarting gives a fresh, repeatable world.

- **Operators:** `operator1`/`pass` (badge `BADGE001`) — ordinary;
  `manager1`/`secret` (badge `BADGE012`) — holds all six privileged action ids
  (use for approvals, waivers, cancels, force-close, return/transfer).
- **Job cards:** SAP orders `510019068` (Layer-mash-style BOM, 7 manual lines,
  one bulk line `1600000217`) and `510018531`, loaded from real SAP sample dumps.
- **Hoppers:** `MXR-01`, `MXR-02` available; `MXR-03` Inactive ("Under
  maintenance"). **Extruders:** `EXT-03`, `EXT-04`. **Rajoo:** `RAJ-01`.
- **Pallets** (tags abbreviated; see `seed/seed.json` for full 24-char tags):

| Tag suffix | Pallet | Product | State | Exercises |
| --- | --- | --- | --- | --- |
| …0001 | PAL-001 | 1600000301 HD WHITE, 625 kg | Holding | happy path, tolerance |
| …0002 | PAL-002 | 1600000217 LD MIX, 1800 kg | Holding, bulk | direct-weight scans |
| …0003 | PAL-003 | 1600000070 FILLER, 600 kg | Holding + **blocked** | blocked rejection |
| …000C | PAL-012 | 1600000070 FILLER, 700 kg | Holding | over-tolerance approval |
| …0004 | PAL-004 | 1600000233 WRAP, 300 kg | **AtStation1** | lookup → recovery flow |
| …0005 | PAL-005 | 1500000326 MB WHITE, 75 kg | **Unknown** | recovery flow |
| …0006 | PAL-006 | 1600000309 STRETCHHOOD, 0 kg | **Consumed** | unrecoverable |
| …0007 | PAL-007 | 1500000331 DESICCANT, 15 kg | Holding, low stock | short scans / waiver |
| …0008–000B | PAL-008…011 | various | Holding | normal collection stock |

- Device ids are **auto-registered** on first sight (the app's `ANDROID_ID`
  can't be known ahead of time); the real backend would reject unconfigured
  devices, and the simulator logs a warning each time it auto-registers one.

## Self-test

With the simulator running, verify it against the contract before trusting it
to judge the app:

```powershell
python selftest.py [--host …]
```

Drives the contract's minimum acceptance flow end-to-end — login → job load →
ingredient scans (tolerance, over-tolerance approval retry, short-bag waiver)
→ two-hopper shared pre-mix → partial/final finish → extruder allocation →
local completion — plus negative probes (bad schema, stale timestamp, replay,
`message_id_reused`, device mismatch, closed session). Exits non-zero on any
deviation.

## What it deliberately does not do

- No TLS / broker auth, no persistence across restarts, no SAP posting,
  no fixed-door-reader TCP, no v2 compatibility.
- No fault injection console (by design decision 2026-07-17).
