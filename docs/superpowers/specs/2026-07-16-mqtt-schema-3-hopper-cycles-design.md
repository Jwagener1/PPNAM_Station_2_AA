# MQTT Schema 3.0 — Hopper Board & Machine Cycles Design

**Date:** 2026-07-16
**Status:** Approved design. The hopper interaction flow is **the user's own**, confirmed 2026-07-16.
**Contract:** `C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` v3.0 (read-only reference)
**Scope:** Sub-project 4 of 5
**Depends on:** SP1 (transport, merged `0c3dd9e`), SP2 (session, merged `f970531`), SP3 (ingredients — not yet built)

## The workflow change this delivers

v2's model was **one hopper, one mix**. `HopperScanScreen` scanned a single hopper code and called
`check-hopper` — a message the backend never subscribed to, so it never worked. SP1 deleted the
screen rather than port a dead flow that couldn't express v3 anyway.

v3's model is **several hoppers, one shared pre-mix**:

> *"Several Hoppers may run concurrently on that same shared pre-mix. They do not create separate
> pre-mixes or duplicate ingredient collections."*
> *"The ingredients are collected once, into one collection, and mixed by however many Hoppers the
> operator brings to bear on that same shared pre-mix. Nothing is copied and no quantity is split."*

So this is a genuinely new workflow, not a port.

## The interaction: see what's free, scan what you want, submit once

**This flow is the user's design.** It is recorded here because it is correct for a reason worth
writing down.

1. Collection reaches `ReadyForRouting`; the final ingredient scan returns `nextAction: "choose_destination"`.
2. The operator opens the hopper screen and **sees the live board — which hoppers are free**.
3. The operator **scans each hopper barcode** they intend to use. Scans accumulate on screen.
4. The operator hits **Submit once**. One `machine_cycle_start_requested` carries all of them in
   `machineCodes[]`.

### Why batching is not merely nicer — it's the only safe option

The contract:

> *"All requested Hopper codes are claimed atomically. If one new code is invalid, inactive, or busy
> on another pre-mix, none of the new codes are assigned."*

Fire-one-request-per-scan and you get **partial assignment**: hopper 1 claimed, hopper 2 busy, and
the operator is now half-committed to a pre-mix with no clean way back — and Station 2 has created a
route and a linked pre-mix off the back of the first success. Batching makes it all-or-nothing.

The rest of the flow's correctness follows from the contract too:

- **The board is already in hand.** `hoppers[]` is **required** in `ingredient_scan_result` —
  including the ingredient-ready scan that returns `choose_destination`. The contract names that a
  "decision point" and mandates a current board *in the same message*, precisely so the operator
  needn't issue a separate lookup. No extra round-trip to show step 2.
- **A stale board self-corrects.** The operator walks the floor scanning; someone else may claim a
  hopper meanwhile. Submit then fails atomically and returns `conflicts[]` — naming the machine, a
  `conflictCode`, a reason, and who holds it — plus a refreshed board. So no polling is needed;
  the failure path *is* the refresh.

## Design decisions

### Show "in use", never block submit

The board tells us a hopper is `InUse`. We grey it and warn if scanned. We **do not** disable submit.

This is the same rule SP1 established for pallet `usable`/`recoverable`: **Station 2 decides, the
client displays.** Blocking client-side would re-derive a server decision from data that may be
stale in *either* direction — and the atomic rejection with `conflicts[]` already handles it
correctly and with a better error message than we could invent.

### Adding hoppers later is the same screen, unchanged

> *"Additional available Hoppers may join the same linked pre-mix while at least one Hopper cycle
> remains active."*
> *"Repeating a request that includes a Hopper already active on this same shared pre-mix returns
> that existing cycle with `alreadyActive: true`; it creates no duplicate assignment."*

So scan-then-submit works identically for the first batch and every later addition, and re-scanning
an already-running hopper is idempotent. **One screen, one interaction, no separate "add hopper"
mode.** A `Routed` collection with active hoppers returns `nextAction: "assign_or_finish_hopper"`,
which is what routes the operator back here.

### Finish names the cycle, not the machine

`machine_cycle_finish_requested` requires **both** `machineCode` and `cycleId`. The contract is
emphatic about why:

> *"A machine is reused but a cycle never is."* Without `cycleId`, a stale or repeated scan would
> finish *whoever is in that machine now* — someone else's mix.

**We read `cycleId` from the board, not only from our own start response.** That is what lets an
operator finish a hopper *another operator* started — which the shared-pre-mix model makes routine.
The board carries `cycleId` per hopper for exactly this.

Finishing an already-finished `cycleId` is an accepted no-op (`alreadyFinished: true`) — never an
error to surface as a failure.

### Partial vs final finish

- Any hopper but the last: `isComplete: false`, `nextAction: "assign_or_finish_hopper"`. Only that
  hopper is released; the others keep running. **The pre-mix is not done.**
- The last one: `isComplete: true`, `preMixStatus: "ReadyForAllocation"`, `nextAction: "allocate_premix"`.

The UI must not imply the mix is complete when one hopper of three finishes. This is the single
easiest thing to get wrong in the whole sub-project, because the v2 mental model says otherwise.

### Force-close is privileged, and follows SP2's rule

`machine_cycle_force_close_requested` releases a stuck cycle without its matching finish scan. It
carries `managerUsername`/`managerPassword`/`auditReason` inline; the approver must hold
`machine_force_close`.

Per SP2's hard-won lesson: **we never check that action id ourselves.** Station 2 checks it against
the *approver's* account. We collect credentials and send. The response names the approver
(`approverUserId`/`approverDisplayName`/`approverRole`) for the audit trail.

### Destination choice: Hopper now, Extruder/Rajoo disabled

`choose_destination` offers three families. SP4 builds Hopper end-to-end; Extruder and Rajoo are
disabled with honest copy pending SP5 — the same pattern SP1 used for the routing button rather than
shipping a control that silently does nothing.

Note `machine_cycle_start_requested` is **one unified message** for all three families — *"The
configured machine code determines the machine family. The handheld never sends a
destination-family selector."* So SP4 builds the message; SP5 reuses it under different rules
(exactly one machine code, sources across `collectionIds` + `preMixIds`).

## Messages

| Request | Response | Purpose |
| --- | --- | --- |
| `machine_cycle_start_requested` | `machine_cycle_start_result` | Batch-claim hoppers atomically |
| `machine_cycle_finish_requested` | `machine_cycle_finish_result` | Release one exact cycle |
| `machine_cycle_force_close_requested` | `machine_cycle_force_close_result` | Privileged release of a stuck cycle |
| `hopper_overview_requested` | `hopper_overview_result` | Manual board refresh |

Hopper start rules from the contract: `machineCodes` = one or more hopper codes; `collectionIds` =
**exactly one**; `preMixIds` = **empty**. All three arrays are always present — `[]`, never `null`.
The collection must be `ReadyForRouting`, or already `Routed` to an active shared hopper pre-mix. A
**completed** pre-mix can never receive another hopper.

## Scope

**In:**
1. Shared hopper-board UI component (the `HopperBoardEntry` model already exists from SP1).
2. Destination screen — Hopper enabled, Extruder/Rajoo disabled pending SP5.
3. Hopper scan screen: live board, accumulate scans, one atomic submit.
4. `conflicts[]` handling with a refreshed board.
5. Active-cycle view + finish by `machineCode` + `cycleId`, read from the board.
6. Partial vs final finish, correctly distinguished.
7. Force-close (privileged, manager credentials inline).
8. `hopper_overview_requested` for manual refresh.

**Out:**
- Extruder/Rajoo starts, direct pallet/bag allocation, return/transfer, work completion (SP5).
- Background board polling — the atomic rejection is the refresh (see above).
- Any client-side pre-validation of hopper availability (see "Show 'in use', never block submit").

## Testing

Fakes throughout; the Station 2 v3 backend is still in progress.

1. Two scanned hoppers produce **one** request with both codes in `machineCodes[]` — not two requests.
2. `collectionIds` carries exactly one id; `preMixIds` is `[]`, never null.
3. A rejected start surfaces `conflicts[]` per machine and refreshes the board; **no** partial local
   state is retained.
4. `alreadyActive: true` for a re-scanned hopper is not an error.
5. Finish sends both `machineCode` and `cycleId`; `cycleId` sourced from the board resolves correctly
   for a hopper this device did not start.
6. Partial finish keeps the pre-mix active and does **not** present as complete.
7. Final finish reports `ReadyForAllocation`.
8. `alreadyFinished: true` is an accepted no-op, not a failure.
9. Force-close always sends manager credentials — no path omits them (mirroring SP2's cancel rule).
10. Submit is disabled with zero hoppers scanned.

## Inherited from SP2's final review — must be handled here or in SP3

- **`clearError()` is dead code and `MixingUiState.Error` is a trap state.** SP2's scan guard nearly
  shipped a permanently-dead reader because of it. Whoever reshapes this flow gives `Error` a real
  dismiss/retry affordance.
- **The scan-guard allow-list needs a per-state decision as states are added.** Fail-closed is the
  right default, but it must be *decided* per state, not inherited — that is precisely how SP2's
  trap state happened.
- **`HomeScreen`/`HomeViewModel` are unreachable dead code.** Delete or route to them.

## Open questions for the Station 2 developer

1. **Acceptance window** — answered 2026-07-16: Station 2 does not implement one. This **contradicts
   the contract**, which mandates it and defines `message_expired`. Escalated in
   `docs/backend/2026-07-16-timestamp-acceptance-window.md`; awaiting implement-or-amend. Not
   blocking SP4.
2. **Is `station_2` the literal presence-topic device id?** Still unanswered, and now load-bearing —
   SP2's banner reads that topic. Also in the backend doc above.
3. Are message-specific `errorCode` values expected beyond the 14 shared codes?
4. **New, SP4-specific:** the contract lists `conflictCode` on the conflict object but never
   enumerates its values (`machine_in_use` is the only one shown, by example). Is it a closed
   vocabulary? We will treat it as **open** (a value class, per SP1's convention) and display
   `reason` to the operator rather than branching on the code — but a list would let us give better
   guidance per conflict type.
