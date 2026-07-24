# Station 2 Backend — Issues Found in Live Testing

**Date:** 2026-07-23
**Tested against:** the **live Station 2 backend** (`PPNAM/station_2/status` retained `online`)
**Broker:** `wss://mqtt.sysone.co.za:443/mqtt`, mosquitto 2.0.22
**Client:** PPNAM Station 2 Android app v1.0 (1) on a Chainway C72 (`handheld_1`)
**Method:** passive MQTT sniffer on `PPNAM/#` recording every frame with request/response
pairing and latency; the app was the only client acting. Evidence in
`docs/test-evidence-2026-07-23/`.

**Scope note:** multiple collections per job card (one per `job_card_load_requested`) is
**intended, client-requested behaviour** and is not reported as a defect. One consequence of
it is carried as B6 below.

**Headline:** reliability was excellent — **zero unanswered requests** across ~20 job-card
loads, 6 completed collections, 5 machine cycles and a forced network drop. The problems are
data correctness, speed, and contract hygiene.

---

## Severity summary

| ID | Severity | Issue |
|---|---|---|
| B1 | **Critical** | `bagSize` / `expectedBags` contradict the backend's own arithmetic |
| B2 | **High** | Force-closed cycles still yield a usable mix |
| B3 | **High** | Passwords accepted in cleartext; shared `admin/admin` broker credentials |
| B4 | **High** | Latency: 1.4–10.4 s, scaling with BOM size |
| B5 | **High** | `errorCode` sometimes carries a GUID instead of a symbolic code |
| B6 | **Medium** | `active_job_cards_list` will grow unboundedly — no pagination |
| B7 | **Medium** | Over-collection variance is recorded but never exposed |
| B8 | **Medium** | Response `timestampUtc` is earlier than the request it answers |
| B9 | **Medium** | Error contract inconsistent — text in `reason`, `errorMessage` absent |
| B10 | **Medium** | Misleading pallet-recovery rejection message |
| B11 | **Low** | `consumedApprovalId` returned when no approval was required |
| B12 | **Low** | Logout returns an empty `sessionState`; duplicate response published |
| B13 | **Low** | Timestamp serialization differs between request and response |

---

## B1. `bagSize` / `expectedBags` contradict the backend's own arithmetic — **Critical**

Job card 510019355, line 0, material `1600000044` "LF 2220 (BUBBLE WRAP)". The backend sends:

```
bagSize = "20.000 kg"    expectedBags = 10.9946    requiredQuantity = 219.892
```

The app faithfully displays *"Bag size: 20.000 kg"* and *"0.00 / 10.99 full bags"*.
Scanning **9** bags returned:

```
scannedBags = 9.0    capturedKilograms = 225.0    collected = true   satisfied = true
```

225.0 ÷ 9 = **25 kg per bag** — the pallet's real bag weight, not the 20 kg advertised. At
20 kg, 9 bags is 180 kg, 39.9 kg short, and could not have satisfied a 219.892 kg requirement.

**Confirmed by the converse.** 10 bags was *rejected* with `requiresManagerApproval: true`
and "Additional quantity requires manager approval" — 10 × 25 = 250 kg, i.e. 1.5 bags over,
exceeding `overCollectionToleranceBags: 1`. "Additional" only makes sense at 25 kg/bag; at
20 kg it would have been short.

**Corroborated from a second endpoint.** `pallet_lookup_result` for that same pallet returns
`remainingQuantity: 24775.0`, `remainingBags: 991` → **25.0 kg per bag**. Two backend
endpoints disagree about the bag size of one material.

### Impact
The operator is told to fetch 10.99 bags; 9 completes the line; they physically move 225 kg
while the screen implies 180 kg. Every quantity shown for the line is wrong, and the
over/under tolerance is judged against a number the operator cannot see.

### Fix
Publish the pallet's true bag size in `bagSize`/`expectedBags`, **or** do the arithmetic in
the advertised units — the two must agree. Audit across all materials: if the BOM's UoM
conversion and the pallet master disagree generally, this is a systemic stock-accuracy
problem rather than one bad row.

---

## B2. Force-closed cycles still yield a usable mix — **High**

The force-close dialog states the cycle is "released **without completing**". In practice,
force-closing `CYC_000010` on MXR-01 released the machine **and published `MIX_000006`** into
"Ready mixes", where it renders **identically** to `MIX_000009`, which came from a properly
finished cycle. Nothing marks it as abandoned or incomplete.

### Impact
An operator can feed a deliberately-abandoned mix into an extruder with no indication the
cycle was never completed.

### Fix
Either discard/quarantine the mix on force-close, or attach a flag
(e.g. `originatedFromForceClose: true`) so the client can mark and gate it.

---

## B3. Passwords in cleartext + shared broker credentials — **High (security)**

`login_requested` carries the operator's password as a plain string:

```json
{"password": "<plaintext>", "username": "Jono", ...}
```

TLS protects transit, but the app ships **hardcoded broker credentials `admin/admin`**
(`AppSettings.kt:8-9`) which grant subscribe on `PPNAM/#`. During this test I used exactly
those credentials to read an operator's password off the broker. Anyone who can `strings` the
APK can harvest every operator password. The manager-approval and force-close dialogs send a
second password the same way.

### Fix, in order
1. Per-device broker credentials instead of one shared `admin/admin`.
2. Broker ACLs restricting each device to its own `PPNAM/<deviceId>/#` prefix.
3. Stop sending the password at all — challenge/response, or client-side hash against a
   server-issued salt.

Items 1 and 2 are broker configuration and can ship immediately; item 3 is a contract change.

---

## B4. Latency — **High**

Measured live, end to end, request published → response received:

| Action | n | min | mean | max |
|---|---|---|---|---|
| `active_job_cards_requested` | 24+ | 315 | 1 520 | **10 366** |
| `job_card_load_requested` | 23 | 1 382 | 4 013 | **9 771** |
| `ingredient_scan_requested` | 5 | 2 553 | 4 142 | **7 585** |
| `machine_cycle_force_close_requested` | 1 | 7 180 | 7 180 | 7 180 |
| `machine_cycle_start_requested` | 5 | 1 817 | ~4 000 | 5 685 |
| `machine_cycle_finish_requested` | 1 | 4 559 | 4 559 | 4 559 |
| `login_requested` | 3 | 2 686 | 2 765 | 2 861 |
| `pallet_lookup_requested` | 1 | 1 351 | 1 351 | 1 351 |
| `collection_resume_requested` | 1 | 553 | 553 | 553 |

`job_card_load` tracks BOM line count (2 lines ≈ 2.0 s, 5 lines ≈ 7.5–9.8 s), which points at
per-line work — most likely one SAP/stock call per ingredient — rather than one batched query.

For scale: the app's request timeout is 20 s (`AppSettings.requestTimeoutMs`). A 10.4 s worst
case already spends half the budget.

### Fix
Batch the per-ingredient SAP/stock lookups into a single call per job card. This is the
highest-value backend change available and would cut the operator's per-BOM wait from ~30 s.

---

## B5. `errorCode` sometimes carries a GUID — **High**

Two confirmed cases:

```
errorCode = "dac675694be14b7685280f006640ed6e"   (== exceptionId, approval-required rejection)
errorCode = "936666e9990c4c0cbd33c6ec9ca4e157"   (scan rejection)
```

Everywhere else `errorCode` is correctly symbolic — `validation_failed` for a bad password,
`service_unavailable` for an unknown production order. The scan/exception paths substitute an
opaque id, so any client branching on `errorCode` breaks on exactly the paths that matter.

### Fix
Keep `errorCode` symbolic (e.g. `manager_approval_required`, `pallet_not_in_holding`) and
leave the GUID in `exceptionId`, which already exists.

---

## B6. `active_job_cards_list` has no pagination — **Medium**

The worst latency of the whole test (**10 366 ms**) was this endpoint returning **29 open
collections plus the full hopper list** in one payload.

Because multiple concurrent collections per job card are by design, this list grows in normal
production use — it is not a test artefact. Every open collection ever created for every job
card accumulates in a single unbounded response, and the app requests it after nearly every
mutation.

### Fix
Paginate or filter — e.g. only collections for this device/operator, only non-terminal states,
or a `since` cursor. Also consider splitting the hopper/machine roster out of this response;
it is largely static and is being resent on every call.

---

## B7. Over-collection variance is recorded but never exposed — **Medium**

Scanning 90 bags against an 89.03-bag requirement recorded:

```
collectedQuantity  = 2225.657   (the planned figure)
capturedKilograms  = 2250.0     (what was physically scanned)
availableQuantity  dropped by the full 2250 kg
```

The backend tracks both numbers correctly, but nothing reports the **24.34 kg** variance. This
recurs on essentially every line, because BOM quantities are fractional in bags and the only
workable operator rule is to round up.

### Fix
Expose the variance explicitly per line and per collection so it can be reported on, and
consider requiring acknowledgement past a threshold.

---

## B8. Response `timestampUtc` is earlier than the request — **Medium**

```
req  login_requested   timestampUtc 2026-07-23T10:12:03.450922Z
res  operator_context  timestampUtc 2026-07-23T10:12:03.3372679+00:00   <- 113 ms earlier
                       reached the broker at 10:12:08.805               <- 5.5 s later
```

Observed on **29 of 29** matched request/response pairs — systematic, not occasional.

### Fix
Stamp responses at send time. As it stands no client can use response timestamps for
staleness, ordering, or replay-window checks.

---

## B9. Error contract is inconsistent — **Medium**

Rejections put the human-readable text in `reason` and **omit `errorMessage` entirely**
rather than sending it null — 4 of 4 rejections captured did this. Clients must special-case
`reason` per response type.

### Fix
Settle on one field and always emit it, null when empty.

---

## B10. Misleading pallet-recovery rejection — **Medium**

Recovering `DUMMY-ST2-1600000050` (status `InUse`, location `Extrusion`) for a collection
whose line 0 material **is** `1600000050` was rejected with:

> "Recovery product is not valid for the active ingredient collection."

The product *is* valid; the obstacle is the pallet's location/state. The message sends the
operator hunting the wrong problem.

### Fix
Say what actually blocked it — e.g. "Pallet is at Extrusion and cannot be recovered to
Holding."

---

## B11. `consumedApprovalId` returned when no approval was required — **Low**

An accepted scan returned `requiresManagerApproval:false`, `hasApprovedException:false`,
`approvalState:""` — yet `consumedApprovalId:"fb326e2e12e94aaf8fa9df4a21d23176"`.

Either the field should be empty here, or approval records are being silently spent. Worth an
audit of the approval-consumption path.

---

## B12. Logout returns an empty `sessionState` — **Low**

`reader_logout_requested` → `operator_context` with `accepted:true` but `sessionState: ""`,
where the failed-login response correctly used `"Closed"`. Two `operator_context` responses
were also published for the single logout request.

---

## B13. Timestamp serialization differs between the two sides — **Low**

Requests emit `...Z` with 6 fractional digits; responses emit `...+00:00` with 7. Same
contract, two serializers.

---

## What the backend does well

Worth stating, because it constrains where to spend effort:

- **Zero unanswered requests** across the entire session, including a forced Wi-Fi drop and
  reconnect. Every request got a response.
- **Concurrency is correct.** Four cycles across two areas (MXR-01/02/03 + JAN-MIX-01) ran
  simultaneously with independent machine state; force-closing one left the others untouched;
  area counters stayed accurate.
- **Routing rules are enforced.** A finished mixer cycle produced a mix whose
  `validNextMachineCodes` correctly listed `JAN-DRUM-01, JAN-02, JAN-03` and **excluded
  JAN-04**, which is transfer-drum gated.
- **Permission modelling is precise** — Operator 17 actions, Admin 24, with a sensible
  admin-only set (`ingredient_approve_override`, `machine_force_close`,
  `allocation_return`, …). The app simply does not use it yet.
- **Bad input is handled cleanly** — an unknown production order returned
  `service_unavailable` with a clear reason and no crash.

---

## Recommended order

| # | Item | Why |
|---|---|---|
| 1 | **B1** bag size mismatch | Operators are moving quantities the system misreports |
| 2 | **B3** (1 & 2) broker credentials + ACLs | Config-only, closes credential harvesting today |
| 3 | **B2** force-close mix flag | Abandoned material can reach production |
| 4 | **B4** batch per-line SAP calls | Removes the ~30 s per-BOM operator wait |
| 5 | **B5** symbolic `errorCode` | Client branching is broken on the paths that matter |
| 6 | **B6** paginate `active_job_cards_list` | Grows unboundedly by design |
| 7 | **B7** expose collection variance | Stock reconciliation |
| 8 | **B8, B9, B10, B12, B13** | Cheap hygiene; prevents future client breakage |
| 9 | **B3** (3) stop sending passwords | Contract change, plan properly |

---

## Evidence

| Artefact | Contents |
|---|---|
| `test-evidence-2026-07-23/capture/wire.jsonl` | phase 1 MQTT frames, latency-paired |
| `test-evidence-2026-07-23/capture2/wire.jsonl` | phase 2 (cycles, concurrency) |
| `test-evidence-2026-07-23/analyze.py` | regenerates the latency and hygiene tables |
| `test-evidence-2026-07-23/FINDINGS.md` | raw running log, F-001 … F-048 |
| `test-evidence-2026-07-23/shots/` | screenshots |

Passwords are redacted in `wire.jsonl` (flagged `<redacted:present>`) but were present in
plaintext on the wire — see B3.
