# MQTT Schema 3.0 — Protocol Foundation Design

**Date:** 2026-07-16
**Status:** Approved design, ready for implementation planning
**Contract:** `C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` v3.0 (read-only reference)
**Scope:** Sub-project 1 of 5 in the schema 3.0 migration

## Context

The Station 2 RFID MQTT contract moved to version 3.0. The contract states plainly that
"Version 3 is intentionally not wire-compatible with version 2." Every message the Android
handheld sends today is wrong under v3 — wrong topic, wrong schema version, wrong name, or
wrong shape. There is no incremental path in which some messages keep working.

This document specifies the first of five sub-projects: the protocol foundation.

### Decisions taken before this design

| Decision | Choice | Consequence |
| --- | --- | --- |
| Cutover strategy | Big-bang; no live users yet | No v2 code retained, no adapter, no feature flags |
| Backend readiness | Station 2 v3 implementation in progress | Build to contract, verify with fakes; ambiguities go to the Station 2 developer |
| First sub-project | Protocol foundation | Nothing else can compile against v3 without it |
| Offline queue | Delete entirely | See "Why the offline queue cannot survive v3" |
| Retry on timeout | Bounded retry reusing `messageId` | Contract's replay design anticipates this |
| Hopper board model | Lives in the foundation | Mandatory in 7 responses across sub-projects 2–5 |
| Broken screens | Foundation implements pallet lookup + recovery | Proves the foundation on a real vertical slice |
| `AppSettings.stationName` | Remove | No wire meaning in v3 |

### The five sub-projects

Because the cutover is big-bang, these are **work-sequencing units, not shippable
increments**. The app must reach v3 in full before it ships to a v3 Station 2.

1. **Protocol foundation** (this document) — envelope, topics, correlation, retry, presence,
   error/nextAction vocabulary, Hopper board model, legacy deletion, pallet lookup + recovery.
2. **Auth & session** — `login_requested` collapse, `sessionState` / `sessionExpiresAtUtc`,
   presence-driven suspend/resume, removal of role-based gating.
3. **Collection & ingredients** — `job_card_load_requested` / `collection_resume_requested`
   split, `bom_loaded`, `lineNumber`, inline manager approval, over-collection tolerance.
4. **Hopper board & machine cycles** — multi-hopper shared pre-mix, cycle start/finish/force-close.
5. **Allocation & completion** — Extruder/Rajoo, direct pallet/bag allocation, return/transfer,
   local Station 2 work completion.

## Current state (verified 2026-07-16)

- **The envelope is not a type.** Six fields are copy-pasted across 16 DTOs, with `"2.0"`
  hard-coded sixteen times.
- **Response correlation is by topic only.** `sendTyped` awaits
  `_incomingTyped.filter { it.first == responseType }.first()` — first message on the matching
  response topic wins. `messageId` and `correlationKey` are written to the wire and never read
  back. This is a live concurrency bug, not merely a missing field.
- **`correlationKey` is populated four different ways** by different callers: `messageId`
  (auth), `jobCardNumber` (lookupJob), `collectionId` (scanIngredient), a fresh UUID
  (fetchActiveJobCards).
- **Typed publishes are QoS 0.** `sendTyped` never calls `.qos()`. The contract requires QoS 1
  for workflow messages.
- **The legacy `{station}/request` protocol has never worked.** Confirmed by the in-code
  comment at `MqttRepositoryImpl.kt`: the backend has never subscribed to that topic, so
  "every call here has therefore always silently timed out."
- **Consequently, three of seven routed screens are non-functional today**: `RFID_RECOVERY`,
  `HOPPER_SCAN`, and `PREMIX_COMPLETE`. Dashboard and Rajoo have no routes at all — dead code.
  The app works from login through ingredient collection; everything past "choose destination"
  is a facade.
- **Topic normalisation is inconsistent.** `request`/`response`/`hopperStatus` produce
  `station2`; `stationStatus` produces `station_2`.

## Why the offline queue cannot survive v3

The queue exists but is dormant (no caller passes `allowOfflineQueue = true`) and drains via
the dead legacy path. It has already caused one production incident (the `premix_cancelled`
blank-payload spam).

It is tempting to assume v3's replay identity finally makes queuing *safe* — that is what
idempotency is for. It does not, because of a second rule. The envelope requires `timestampUtc`
to be "inside the configured acceptance window." A scan queued while offline and delivered ten
minutes later carries a stale timestamp and is rejected with `message_expired`. The timestamp
cannot be refreshed, because that changes the request body:

- keep the `messageId` → rejected as `message_id_reused`;
- mint a new `messageId` → a new operation, losing the idempotency that made queuing safe.

Replay lookup does run before timestamp rejection, but that only rescues a **duplicate of an
already-accepted** message — not a first delivery that never landed.

**Conclusion:** offline queuing of new operations does not survive v3's own rules. Delete it.
Offline becomes an explicit "not connected to Station 2" state shown to the operator.

Retry-on-timeout is the opposite case and is safe: seconds rather than minutes, same
`messageId`, same body, comfortably inside the window.

## Architecture

### 1. The transport owns the envelope

The single decision the rest of the design follows from. Use cases must not know about
envelopes; only the transport knows the device ID, the operator session, and the clock.

Callers supply message-specific fields only:

```kotlin
mqtt.request(
    requestType    = "login_requested",
    responseType   = "operator_context",
    payload        = LoginPayload(username = u, password = p),
    correlationKey = null,                    // optional trace key, omitted when null
    responseClass  = OperatorContextResponse::class.java,
)
```

Serialisation merges the envelope into the payload at publish time:

```kotlin
val obj = gson.toJsonTree(payload).asJsonObject
obj.addProperty("messageId", messageId)
obj.addProperty("schemaVersion", Schema.VERSION)   // "3.0" — defined once
obj.addProperty("deviceId", deviceId)
obj.addProperty("operatorSessionId", session?.operatorSessionId ?: "")
obj.addProperty("timestampUtc", nowIso8601Utc())
correlationKey?.let { obj.addProperty("correlationKey", it) }
```

This produces the flat JSON the contract specifies, with no custom Gson `TypeAdapter` and no
nested object. Gson omits nulls by default, satisfying the contract's rule that "an unused
optional request field must be omitted."

`operatorSessionId` uses `""` rather than omission, which the contract explicitly permits for
`login_requested`.

### 2. Correlation

A pending-request registry replaces topic matching:

```kotlin
private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
// inbound: pending.remove(envelope.inResponseToMessageId)?.complete(json)
```

`responseType` **cannot** discriminate, and this is provable within the auth flow alone:
`login_requested` and `reader_logout_requested` both answer on `operator_context`.
`inResponseToMessageId` is the only correct discriminator, as the contract requires.

An inbound response with no matching pending entry is logged and dropped, consistent with the
contract's "unknown topics receive no workflow side effect."

### 3. Retry

Bounded: 2 retries over approximately 10 seconds, subject to the acceptance window (see Open
Questions).

**The request bytes are built once and frozen.** A retry republishes the identical byte array —
same `messageId`, same `timestampUtc`. Regenerating the timestamp changes the body, which under
replay identity yields either `message_id_reused` or an unintended duplicate operation. This is
the single easiest thing to get wrong in this sub-project and must be covered by a test.

`messageId` is a UUID. The contract's examples (`login-0001`) are illustrative; a per-device
counter would need durable storage to avoid reuse-after-restart collisions, which would be
rejected as `message_id_reused`. Replay identity is `deviceId + requestType + messageId`, so
UUID uniqueness is more than sufficient.

### 4. Result type

```kotlin
sealed interface MqttOutcome<out T> {
    data class Accepted<T>(val body: T, val nextAction: NextAction) : MqttOutcome<T>
    data class Rejected<T>(
        val body: T,
        val errorCode: ErrorCode?,
        val reason: String?,
        val nextAction: NextAction,
    ) : MqttOutcome<T>
    data class NoResponse(val kind: FailureKind) : MqttOutcome<Nothing>
}

enum class FailureKind { Timeout, NotConnected, MalformedResponse }
```

`Rejected` carries the typed body deliberately. The contract guarantees "every response topic
has one stable payload shape": a rejected `ingredient_scan_result` still returns the full
refreshed `ingredients[]` and `hoppers[]`, and a rejected `machine_cycle_start_result` still
returns `conflicts[]`. Discarding it would force a redundant refresh.

`NoResponse` is genuinely distinct from `Rejected`: it means we never heard back, which an
operator must treat differently from a decision Station 2 actually made.

Note that `accepted: true` means Station 2 answered, not that the answer was favourable — a
pallet lookup returning `found: false` is `Accepted`.

### 5. Error and nextAction vocabulary

```kotlin
@JvmInline value class ErrorCode(val raw: String) {
    companion object {
        val INVALID_JSON = ErrorCode("invalid_json")
        val SESSION_REQUIRED = ErrorCode("session_required")
        val MESSAGE_EXPIRED = ErrorCode("message_expired")
        val MESSAGE_ID_REUSED = ErrorCode("message_id_reused")
        // ... all 14 contract codes
    }
}

@JvmInline value class NextAction(val raw: String) {
    companion object {
        val NONE = NextAction("")
        val LOGIN = NextAction("login")
        val RECOVER_HOLDING = NextAction("recover_holding")
        // ... all 13 contract values
    }
}
```

Value classes rather than enums: the contract notes these codes are "shared across message
families", implying message-specific codes may also exist. Unknown values from a still-evolving
backend pass through instead of crashing the parse, while `==` against the constants still works.

### 6. Topics

```kotlin
object MqttTopics {
    const val STATION_STATUS = "PPNAM/station_2/status"
    fun request(deviceId: String, requestType: String) = "PPNAM/$deviceId/req/$requestType"
    fun responseWildcard(deviceId: String) = "PPNAM/$deviceId/res/+"
    fun deviceStatus(deviceId: String) = "PPNAM/$deviceId/status"
    fun responseTypeOf(topic: String) = topic.substringAfterLast('/')
}
```

Segments are validated against `/`, `+`, and `#` at construction, per the contract. The
`station2` vs `station_2` normalisation bug evaporates, because v3 hardcodes `station_2` and the
legacy station topics are deleted.

Workflow publishes move to **QoS 1**, not retained. Presence publishes remain QoS 1, retained.

### 7. Presence

Existing behaviour is already contract-correct and is retained: retained `online` on connect,
retained `offline` LWT, retained `offline` published before clean disconnect.

**New:** subscribe to `PPNAM/station_2/status`. The app does not do this today. It lets the UI
distinguish three states that are currently collapsed into one:

| State | Meaning |
| --- | --- |
| Broker unreachable | Transport down |
| Broker up, `station_2` offline | Nothing is listening; requests will time out |
| Broker up, `station_2` online | Ready |

Today "connected" only means "connected to the broker", which can be true while nothing at all
is listening.

### 8. Clock skew

Every request carries a device-clock `timestampUtc` that must fall inside Station 2's acceptance
window. A handheld with a drifted clock fails **every** message with `message_expired` — a total
outage presenting as generic request failures.

Mitigation: compare each response's `timestampUtc` against local time; when the skew exceeds a
threshold, surface a specific "device clock out of sync" diagnostic. Detect and report only — no
auto-correction (YAGNI, and silently rewriting timestamps would obscure a real device fault).

### 9. Hopper board (shared model)

```kotlin
enum class HopperState { Available, InUse, Inactive }

data class HopperBoardEntry(
    val displayName: String,
    val machineCode: String,
    val status: HopperState,
    val isAvailable: Boolean,
    val cycleId: String? = null,
    val collectionId: String? = null,
    val preMixId: String? = null,
    val jobCardNumber: String? = null,
    val assignedAtUtc: String? = null,
    val assignedByOperatorId: String? = null,
    val assignedByDisplayName: String? = null,
    val assignedFromDevice: String? = null,
    val inactiveReason: String? = null,
)
```

Defined in the foundation because `hoppers[]` is mandatory in seven responses spanning
sub-projects 2–5; defining it later would mean each sub-project re-adding and drifting it.

The assignment fields are nullable because the contract's own examples show abbreviated boards in
some responses and full boards in others.

This replaces `HopperStatus` / `HopperAvailability`, whose values (`AVAILABLE`, `IN_USE`,
`OFFLINE`) do not match the contract's (`Available`, `InUse`, `Inactive`). It also removes the
`PPNAM/{station}/hopper/status` push subscription — v3 delivers the board inside responses.

## Vertical slice: pallet lookup and holding recovery

The foundation implements `pallet_lookup_requested` → `pallet_lookup_result` and
`holding_recovery_requested` → `holding_recovery_result`. These are the contract's simplest
request/response pair — no BOM, no Hopper board, no cycle state — which makes them the right
proving ground for the transport. They also make the `RFID_RECOVERY` screen work for the first
time, keeping the top-bar RFID Pallet Lookup button honest.

The key modelling rule, taken directly from the contract: **`usable` and `recoverable` are
computed by Station 2 and must not be re-derived by the client.**

| Field | Decided by |
| --- | --- |
| `usable` | `palletState` **and** `blocked` **and** `remainingQuantity` |
| `recoverable` | `palletState` alone |

Recoverability and `blocked` are independent: a blocked `AtStation1` pallet is still
`recoverable: true`, and recovering it does not unblock it — so it can come back
`usable: false` after a successful recovery. The screen shows Station 2's answer rather than
computing its own.

`palletState` is the axis every decision keys off: `Holding`, `Mixing`, `AtStation1`, `Unknown`,
`Consumed`. `blocked` is a separate overlay, not a state value.

The existing `Pallet(tagId, batchNo, itemCode, location)` model is replaced by one carrying the
full `pallet_lookup_result` shape.

## Blast radius

Deleting `sendTyped` breaks every caller, so the foundation must mechanically port
`AuthUseCase` and `MixingUseCase` onto `request()` to keep the build green. That port applies v3
topic renames where they are pure renames, and leaves payload shape and workflow semantics to
sub-projects 2 and 3:

| Ported in foundation (mechanical) | Deferred |
| --- | --- |
| `reader_login_requested` + `login_tag_scanned` → `login_requested` | `sessionState`, `sessionExpiresAtUtc`, presence-driven session, role-gating removal (SP2) |
| `ingredient_scanned` → `ingredient_scan_requested` | `lineNumber`, `bagSize`, tolerance, inline manager approval (SP3) |
| `active_ingredient_collections_requested` → `active_job_cards_requested` | response shape changes (SP3) |
| `job_card_submitted` → `job_card_load_requested` | load/resume split, `bom_loaded` shape (SP3) |

This is not throwaway work — the renames are permanent. Only the payload shapes change later.

## Deletions

- **Legacy transport**: `MqttRequest`, `MqttResponseMessage`, `MqttResult`, `send`,
  `sendWithTimeout`, `handleIncoming`, `handleHopperStatus`, `publishTyped`, and the legacy
  topic builders (`request`, `response`, `hopperStatus`, `stationStatus`, `contractRequest`,
  `contractResponse`, `contractResponseWildcard`).
- **Offline queue**: `OfflineQueueEntity`, `OfflineQueueDao`, `OfflineQueueRepository`, the drain
  worker, the Room migration, and the Settings "clear queue" control.
- **Dashboard**: `DashboardUseCase`, `DashboardScreen`, `DashboardViewModel` (unrouted).
- **Rajoo**: `RajooUseCase`, `MachineSelectScreen`, `PalletAllocScreen`, `RajooViewModel`,
  `AllocationRecord` (unrouted). Rajoo returns in sub-project 5 under the unified machine-cycle
  model.
- **Mixing legacy holdouts**: `MixingUseCase.checkHopper`, `MixingUseCase.completePremix`.
- **Models**: `HopperStatus`, `HopperAvailability`, old `Pallet`.
- **Settings**: `AppSettings.stationName`.
- **Routes**: `HOPPER_SCAN` and `PREMIX_COMPLETE` and their screens are removed until
  sub-project 4 rebuilds them on the machine-cycle model. `RFID_RECOVERY` is retained and made
  functional.
- **Manager approval**: `ManagerApprovalRequest` / `ManagerApprovalResultResponse` and the
  `approvalId` / `consumedApprovalId` flow are deleted in sub-project 3, not here — the
  foundation keeps them compiling.

## Testing

Fakes throughout, since the Station 2 v3 backend is in progress. A fake transport substitutes
for the broker; conformance tests assert wire shape against golden JSON.

The tests that matter:

1. **Out-of-order correlation** — two concurrent requests whose responses arrive in reverse
   order each resolve to the correct caller. *This test fails against today's code and is the
   bug fix.*
2. **Ambiguous response type** — a `login_requested` and a `reader_logout_requested` in flight
   together, both answering on `operator_context`, resolve correctly.
3. **Retry byte-identity** — a retried request republishes a byte-identical payload; `messageId`
   and `timestampUtc` are unchanged.
4. **Envelope shape** — golden-JSON comparison for a representative request, asserting flat
   structure, `schemaVersion: "3.0"`, and omission of an absent `correlationKey`.
5. **QoS 1** — workflow publishes assert QoS 1; presence publishes assert QoS 1 + retained.
6. **Topic validation** — segments containing `/`, `+`, or `#` are rejected.
7. **Unknown vocabulary** — an unrecognised `errorCode` or `nextAction` parses rather than
   throwing.
8. **Pallet lookup** — `found: false` is an `Accepted` outcome; `usable` / `recoverable` are read
   from the response, never computed.

## Open questions for the Station 2 developer

1. **What is the configured timestamp acceptance window?** It bounds the retry budget and
   determines how severely device clock drift degrades the handheld. Needed before retry timings
   are finalised.
2. **Is `station_2` the literal, fixed device ID in the presence topic**, or is it derived from
   configuration on the Station 2 side? The contract hardcodes it; we plan to as well.
3. **Are message-specific `errorCode` values expected beyond the 14 shared codes?** Our value-class
   design tolerates them either way; this is to confirm the assumption.

## Out of scope

- Session lifecycle semantics (sub-project 2).
- BOM, ingredient, and manager-approval reshaping (sub-project 3).
- Machine cycles, multi-hopper shared pre-mix (sub-project 4).
- Allocation and work completion (sub-project 5).
- Any v2 compatibility shim — the cutover is big-bang.
