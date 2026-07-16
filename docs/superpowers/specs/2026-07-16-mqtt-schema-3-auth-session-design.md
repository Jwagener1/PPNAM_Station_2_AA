# MQTT Schema 3.0 — Auth & Session Design

**Date:** 2026-07-16
**Status:** Approved design (user delegated design decisions; every judgement call is flagged for review)
**Contract:** `C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` v3.0 (read-only reference)
**Scope:** Sub-project 2 of 5 in the schema 3.0 migration
**Depends on:** Sub-project 1 (protocol foundation), merged to master as `0c3dd9e`

## Context

Sub-project 1 built the v3 transport and did a mechanical port of `AuthUseCase` — the two login
topics collapsed into `login_requested`, and the request/response plumbing moved to `request()`.
It deliberately deferred all *session semantics*. This sub-project delivers them.

The app cannot ship until sub-projects 2-5 all land (the cutover is big-bang), so nothing here is
gated on a release.

## The headline problem: the app enforces permissions the contract says are advisory

`MixingViewModel.operatorCanCancelDirectly()` currently reads:

```kotlin
sessionHolder.session.value?.allowedActions?.contains("cancel_premix_direct") == true
```

This is wrong three times over:

1. **It enforces on `allowedActions`.** The contract is unambiguous: *"`allowedActions` is a
   **display hint for the scanner UI**... **Do not use this list to enforce anything.**"* and
   *"Station 2 does not check a session's `allowedActions` when handling an ordinary request."*
2. **`cancel_premix_direct` is not a v3 action id.** The contract defines exactly six, and the one
   for this operation is `ingredient_collection_cancel`.
3. **It is checked against the wrong account.** The contract evaluates action ids against *the
   approver named in the request*, "never against the session that sent it."

The consequence is live: `IngredientScanScreen:89` branches on this gate to offer a **direct-cancel
path that sends no manager credentials at all**. v3 requires manager credentials on *every*
privileged action — explicitly *"even when the sender is themselves a Manager."* A cancel from that
path would be rejected by a v3 Station 2, and worse, the UI implies an authority the contract does
not grant.

**Fix: delete the gate entirely.** Every cancel goes through the manager-credential dialog. There is
no "direct" cancel in v3.

`role` gets the same treatment. The contract: *"`role` is **informational only** — for display and
audit. No rule anywhere in this contract gates on it."* We keep displaying it; we never branch on it.

## Session lifecycle: what the client actually has to do

The contract's `Active`/`Suspended`/`Closed` state machine is **almost entirely server-side**, keyed
off the device presence topic. It is worth being precise about how little the client owes, because
the temptation is to mirror the state machine and that would be wasted work:

| Contract transition | Client obligation |
| --- | --- |
| Presence `offline` → `Suspended` | **None.** Sub-project 1 already publishes retained `offline` via LWT and on clean disconnect. |
| Presence `online` → `Active` | **None.** Sub-project 1 already publishes retained `online` on connect. |
| Valid request on `Suspended` → `Active` | **None.** Resumption is implicit in sending any request. |
| `sessionExpiresAtUtc` reached → `Closed` | React to the resulting rejection. |
| Request on `Closed` → `session_required` + `nextAction: "login"` | **Clear the local session and return the operator to login.** |

So the client's entire share of the session lifecycle is: **handle `session_required`**. A wifi blip
already costs the operator nothing, because the transport reconnects and the next request silently
resumes the session server-side.

### Design decision: intercept `session_required` in the transport

The transport already parses `errorCode` on every response and already holds `OperatorSessionHolder`
(it injects `operatorSessionId` into every envelope). Clearing the session there covers **every
request in the app, present and future**, in one place.

The alternative — each use case checking `errorCode == SESSION_REQUIRED` — is worse: it is repeated
in every use case, and sub-projects 3-5 add roughly a dozen more, each an opportunity to forget.

**Judgement call flagged for review:** this puts a side effect (clearing the session) in the
transport, which is otherwise a pure request/response mechanism. I consider that justified because
the session is transport-level state — the transport is what stamps `operatorSessionId` onto every
message — so it is the correct owner of "that session is no longer valid." The alternative spreads
security-relevant handling across a dozen call sites.

### Navigation on session loss

`AppNavGraph` observes `sessionHolder.session`. When it becomes `null` while the operator is on any
screen other than login, navigate to login, clearing the back stack.

**Judgement call flagged for review:** this makes session loss a *global* navigation event rather
than a per-screen concern. A scan in progress is abandoned. That is correct — a `Closed` session
means Station 2 will reject everything anyway, so continuing to show a working-looking screen would
be a lie. The operator sees the login screen with an explanation rather than a silent failure.

## What we add to the session model

`OperatorContextResponse` gains the two fields sub-project 1 deliberately omitted:

```kotlin
val sessionState: String? = null,        // Active | Suspended | Closed
val sessionExpiresAtUtc: String? = null,
```

`OperatorSession` carries them through:

```kotlin
data class OperatorSession(
    val operatorSessionId: String,
    val operatorId: String,
    val operatorName: String,
    val role: String,                    // display and audit only — never branch on this
    val sessionState: SessionState,
    val sessionExpiresAtUtc: Instant?,
    val allowedActions: List<String>,    // UI display hint only — never enforce with this
    val allowedTabs: List<String>,       // UI display hint only
)

enum class SessionState { Active, Suspended, Closed;
    companion object { fun fromWire(raw: String?): SessionState = ... }  // unknown -> Active
}
```

`SessionState` is a closed vocabulary (three values, exhaustively defined) so it is an enum, per the
convention sub-project 1 established: closed vocabularies are enums, open ones (`errorCode`,
`nextAction`) are value classes. Unknown values degrade to `Active` rather than locking the operator
out of a working session over an unrecognised string.

**Judgement call flagged for review:** a login response arriving with `sessionState: "Closed"` is
treated as a failed login. Accepting a session Station 2 has already closed would strand the
operator in a UI that rejects every action.

### Session expiry is handled reactively, not proactively

We store `sessionExpiresAtUtc` and **display** it, but we do not run a client-side timer that logs
the operator out. Station 2 is authoritative; a client timer could drift out of agreement with it —
and this device's clock is exactly what sub-project 1's skew detection exists to distrust.

The default expiry is 16 hours ("one shift plus margin"), so an operator can genuinely hit it
mid-shift. Showing it on the Home screen costs nothing and turns a surprise logout into an expected
one.

**Explicitly not built:** a proactive "your session expires in N minutes" warning. YAGNI until the
floor reports it as a problem — and a device with a skewed clock would show a wrong countdown, which
is worse than no countdown.

## Connection status: surfacing what sub-project 1 exposed

Sub-project 1 plumbed `stationOnline` and `clockSkewMillis` to the repository interface but left them
without a consumer, on the grounds that this sub-project owns that UI. `AppScaffold` currently
collapses everything into three states derived from `connectionState` alone.

The problem with today's banner: **"Connected" only means "connected to the broker."** That can be
true while Station 2 is down, in which case every request times out and the operator has no idea why.

New status model, in precedence order:

| Condition | Banner | Colour |
| --- | --- | --- |
| `connectionState != CONNECTED` | "Offline" / "Reconnecting" | Red / Orange |
| Connected, `stationOnline == false` | **"Station 2 offline"** | Orange |
| Connected, station online, `abs(clockSkew) > 30s` | **"Clock out of sync"** | Orange |
| Otherwise | "Connected" | Green |

**Judgement call flagged for review:** clock skew is shown as a *warning* rather than blocking work.
A skewed clock means every request fails with `message_expired`, so it is arguably fatal — but the
operator cannot fix the clock from this screen, and blocking would strand them entirely. A visible,
specific warning turns "everything is mysteriously broken" into "the device clock is wrong, tell
maintenance." The requests will fail loudly on their own.

Clock skew ranks below Station 2 presence because a skew reading is only meaningful once we have
had a response to measure it from.

## Inherited defect: the MixingViewModel scan race

Sub-project 1's final review flagged this, and a *prior project's* ledger flagged the same bug class
before that:

> `MixingViewModel` has no scan guard across its bag-entry / approval / recovery sub-flows. A stray
> RFID read while a dialog is open can dismiss it or clobber in-flight state.

Sub-project 1 fixed exactly this class of bug on `RfidViewModel` by guarding the scan collector on
`uiState`. The pattern exists in the codebase; this sub-project ports it to `MixingViewModel`.

It belongs here rather than sub-project 3 because it is session/UI-plumbing work, and because it has
now been reported twice and deferred twice.

## Scope

**In:**
1. `sessionState` / `sessionExpiresAtUtc` through the DTO, model, and Home display.
2. Transport-level `session_required` interception → clear session.
3. Global navigation to login on session loss.
4. Delete `operatorCanCancelDirectly()` and the direct-cancel path; every cancel requires manager
   credentials.
5. Three-state-plus connection banner consuming `stationOnline` and `clockSkewMillis`.
6. `MixingViewModel` scan-race guard.

**Out:**
- BOM/ingredient reshaping, inline manager approval (sub-project 3).
- Machine cycles (sub-project 4). Allocation (sub-project 5).
- Proactive expiry warning (YAGNI, see above).
- Any client-side mirror of the `Active`/`Suspended` state machine (server-side; see the table).

## Testing

Fakes throughout — the Station 2 v3 backend is still in progress.

1. A response carrying `session_required` clears the session holder; one carrying any other
   `errorCode` does not.
2. A login response with `sessionState: "Closed"` fails the login and stores no session.
3. `SessionState.fromWire` maps all three values; an unknown value degrades to `Active`.
4. The banner resolves correctly for each precedence branch, including connected-but-station-offline
   and connected-but-skewed.
5. Cancel always sends manager credentials — there is no code path that omits them.
6. A stray scan during a `MixingViewModel` sub-flow does not clobber state (mirroring
   `RfidViewModelTest`'s guard tests, which must fail against unguarded code first).

## Open questions for the Station 2 developer

Carried forward from sub-project 1 and still unanswered:

1. **What is the configured timestamp acceptance window?** Still bounds the retry budget
   (currently 3 × 10s = 30s, unclamped, with `requestTimeoutMs` operator-editable). This is
   sub-project 1's one deferred Important finding.
2. **Is `station_2` the literal, fixed device id in the presence topic?** This sub-project now
   *depends* on it — the banner reads that topic to decide whether Station 2 is up.
3. Are message-specific `errorCode` values expected beyond the 14 shared codes?
