# Request to Station 2: the timestamp acceptance window

**Date:** 2026-07-16
**From:** Android handheld team
**Re:** `RFID_MQTT_CONTRACT.md` v3.0 — envelope `timestampUtc`, error code `message_expired`
**Status:** Decision needed. Either implementation or a contract amendment — see "The ask".

## Summary

The contract requires every request's `timestampUtc` to fall inside a configured acceptance window,
and defines `message_expired` as one of its 14 shared error codes. Station 2 does not currently
implement this.

**The divergence is the problem, not the feature.** The handheld is built against the contract, and
the contract is the only document both teams build from. Whichever way this goes, contract and
implementation need to agree — otherwise it surfaces at production cutover, which is exactly the
moment we cannot afford surprises.

## What the contract says today

From the request envelope (contract line ~161):

> | `timestampUtc` | Yes | ISO 8601 UTC timestamp inside the configured acceptance window. |

From the shared error codes (~line 233):

> | `message_expired` | Timestamp is outside the configured window. |

From the validation order (~line 273):

> Replay lookup occurs before timestamp rejection so a delayed duplicate can still receive its
> stored response.

That last line matters and is easy to miss: **a duplicate of an already-accepted message is rescued
by replay lookup even if it arrives outside the window.** The window therefore only ever rejects a
*first* delivery.

## Our honest assessment: it may not be worth much

We are asking for a decision, not lobbying for the feature. The case against is real:

- **Replay identity already prevents duplicate execution.** The same `deviceId + requestType +
  messageId` with the same body returns the stored response without repeating the action. That is
  the protection that actually matters for a retrying scanner, and it works with or without a window.
- **The window only catches stale *first* deliveries** — a message that was never accepted, delivered
  much later. With QoS 1 against a live broker, and no offline queue on the handheld (we deleted
  ours; see below), messages do not realistically sit around for minutes.
- **Production MQTT is TLS with authenticated broker credentials**, per the contract's own security
  section. That is the primary defence against a captured-and-replayed message, and it is stronger
  than a timestamp check.
- **It has a real cost: clock synchronisation.** A handheld whose clock drifts past the window fails
  *every* request with `message_expired` — a total device outage presenting as generic failures. We
  built skew detection specifically because of this risk (see below).

The case for is defence-in-depth: a window bounds how long a captured message stays useful if TLS or
broker credentials are ever compromised. That is a genuine, if secondary, benefit.

**Our recommendation: implement it, with a generous window** — for one reason above all, which is
that the contract already says it exists. A client team that builds to a documented rule the server
does not enforce is accumulating silent divergence. If the rule is not wanted, delete it from the
contract so nobody builds against it again.

## The ask

Pick one, and tell us which:

### Option A — implement it (our recommendation)

Enforce `timestampUtc` against a configured window; reject with `message_expired` when outside it.
Keep replay lookup *before* the timestamp check, exactly as the contract already specifies.

**Recommended window: 120 seconds, configurable.** Rationale below — please do not set it below
60 seconds without talking to us first.

### Option B — remove it from the contract

Delete the window from the envelope table and remove `message_expired` from the shared error codes.
We will drop our clock-skew handling accordingly.

**If you choose B, tell us explicitly** — silence reads as "not implemented yet", and we will keep
building as though it is coming.

## If Option A: why 120 seconds

The window must comfortably exceed the sum of three things:

| Contributor | Budget | Notes |
| --- | --- | --- |
| Network + broker latency | < 1s | Negligible in practice. |
| **Handheld retry budget** | **30s** | 3 attempts × 10s timeout. See below. |
| **Device clock skew** | **the real variable** | See below. |

**On the retry budget.** The handheld retries a timed-out request by republishing a **byte-identical
payload** — same `messageId`, same `timestampUtc`, deliberately, because regenerating the timestamp
would change the body and break replay identity (it would then be rejected as `message_id_reused`).
So a request's timestamp is frozen at first attempt and ages across retries.

Note this only bites when the *first* attempt never landed. If attempt 1 was accepted, attempts 2-3
are duplicates and replay lookup rescues them regardless of the window — per the contract's own
ordering rule.

Our budget is currently 3 × 10s = 30s, and `requestTimeoutMs` is operator-configurable in Settings
with no upper bound. **Once you give us a window, we will clamp the retry budget to sit inside it**
and stop the setting from exceeding it. That clamp is blocked on your answer.

**On clock skew — this is the part that decides the number.** The handheld stamps `timestampUtc`
from its own clock. If that clock drifts, *every* request from that device fails.

- If handhelds are **NTP-synced**, skew is sub-second and a 60s window is ample.
- If they are **not** — and warehouse handhelds frequently are not — skew of minutes is ordinary, and
  any window will lock the device out entirely.

We already detect this: the app measures skew from each response's `timestampUtc` against local
time, exposes it, and warns the operator above 30 seconds ("Clock out of sync") rather than silently
failing. It **does not** auto-correct — silently rewriting timestamps would hide a real device fault
and defeat the window's purpose.

**So: 120 seconds gives 30s of retry budget plus ~90s of skew tolerance.** That is tight enough to
be meaningful and loose enough not to brick a device over modest drift.

**Please also confirm the handhelds are NTP-synced.** If they are not, that is worth fixing before
the window is switched on — otherwise Option A turns clock drift into a fleet outage.

## Context you may want: this already cost us a design decision

While designing the handheld's schema 3.0 migration, we removed the app's offline queue. The
headline argument was the acceptance window: a scan queued offline and delivered ten minutes later
would be rejected as `message_expired`, and the timestamp cannot be refreshed without breaking
replay identity (keep the `messageId` → `message_id_reused`; mint a new one → the idempotency that
made queuing safe is gone).

Learning there is no window today weakens that argument. We are **not** re-adding the queue — it was
dormant, drained through a dead code path, and had already caused a production incident — but it is a
concrete example of the contract's text driving a real client decision. That is why the divergence
matters more than the feature does.

## Also outstanding, and now load-bearing

**Is `station_2` the literal, fixed device id in the presence topic** (`PPNAM/station_2/status`), or
is it derived from Station 2's configuration?

The contract hardcodes it and we have done the same. The handheld now **subscribes** to that topic to
tell the operator whether Station 2 is up — because "connected" previously meant only that the broker
was reachable, which can be true while Station 2 is down and every request silently times out.

If that device id is ever anything other than `station_2`, every handheld will show a permanent,
false "Station 2 offline". A one-word answer closes this.

## What we need back

1. **Option A or Option B** on the acceptance window.
2. If A: **the window value** (we suggest 120s) so we can clamp the retry budget.
3. **Are the handhelds NTP-synced?**
4. **Is `station_2` the literal presence-topic device id?**
