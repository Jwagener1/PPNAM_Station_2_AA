# MQTT Reconnection Reliability Fix — Design Spec

**Date:** 2026-07-03
**Scope:** `MqttRepositoryImpl` reconnection state machine only. No MQTT contract changes, no UI changes beyond `MqttConnectionState` transitions already surfaced today via `AppScaffold`/`LoginScreen`/etc.
**Not in scope:** Any of the job-card lifecycle work (see the companion `2026-07-03-job-card-lifecycle-design.md`) — that spec is unrelated in files touched and risk profile.

---

## Context

Reported symptom: the MQTT connection sometimes drops after login and never recovers — the app is left showing `DISCONNECTED` indefinitely with no further reconnect attempts, requiring an app restart to recover.

## Root Cause

Traced via code/data-flow inspection of `MqttRepositoryImpl.kt` (no live device reproduction was available in this session — see Verification below):

1. `LoginViewModel.init` calls `connect()` once at startup. `MqttClientFactory.build()` arms HiveMQ's `automaticReconnect()` (1s initial delay, 30s max delay), which only takes over *after* a first successful connect — it does not retry a failed initial connect.
2. On a later transport-level drop, HiveMQ's automatic reconnect silently re-establishes the TCP/TLS connection and fires `onConnected` again (`MqttRepositoryImpl.kt:66-82`).
3. That handler calls `subscribeAndAnnounce()` to re-subscribe (MQTT5 sessions here have no `sessionExpiryInterval`, so subscriptions don't survive a disconnect) and re-publish presence. If this step throws for any reason (subscribe timeout, broker hiccup), the catch block sets `_connectionState = DISCONNECTED` and calls `scheduleReconnectRetry()` (`MqttRepositoryImpl.kt:78-81`).
4. `scheduleReconnectRetry()` waits 5s, then calls `connect()` again (`MqttRepositoryImpl.kt:93-101`).
5. **Defect:** `connect()` only guards on `_connectionState.value == CONNECTED` (line 129). At this point state is `DISCONNECTED`, but the underlying HiveMQ client (`mqttClient`) is still transport-connected — HiveMQ's own automatic reconnect already succeeded in step 2. So `connect()` skips rebuilding the client and calls `client.connectWith().send().await()` on an **already-connected client** — a second CONNECT on an open MQTT session, which brokers may treat as a protocol violation.
6. Unlike `reconnectWith()` (wrapped in `withTimeout(15_000L)`, line 183), `connect()` has **no timeout** around this await. If it hangs — plausible if the client silently ignores/never resolves a redundant connect — the `retryJob` coroutine never completes. `scheduleReconnectRetry()`'s own guard (`retryJob?.isActive == true`) then permanently suppresses any further retry attempt: the app is stuck in `DISCONNECTED` forever, matching the reported symptom exactly.

The underlying design problem: **two independent reconnection mechanisms (HiveMQ's `automaticReconnect()` and the app's `scheduleReconnectRetry()`/`connect()`) share one client with no mutual exclusion**, and only one of the two paths (`reconnectWith()`) has a timeout guard.

## Approaches Considered

1. **Targeted fix to the reentrancy + timeout defect (recommended).** Track transport-connected state independently of the UI-facing `_connectionState`; make `connect()` a no-op against a client that's already transport-connected; give the subscribe-only failure path its own bounded retry (with timeout) instead of re-running a full `connectWith()`; add the missing `withTimeout` to `connect()`. Keeps HiveMQ's tested backoff/retry as the sole transport-reconnection driver. Smallest diff that fixes the actual defect chain.
2. **Hand-roll all reconnection app-side**, disabling HiveMQ's `automaticReconnect()` entirely. Full control over retry/backoff, but reimplements logic the library already provides correctly, for no benefit here — rejected as unnecessary scope.
3. **Minimal patch**: just add `withTimeout` to `connect()` and tighten `scheduleReconnectRetry()`'s guard, without introducing transport-state tracking. Stops the permanent hang, but leaves the dual-reconnect-mechanism design smell in place — the next failure mode in this area is likely to be a variant of the same root issue. Rejected in favor of #1, which is barely more work and actually closes the defect class.

## Design

### 1. New internal transport-state tracking

```kotlin
private val _isTransportConnected = AtomicBoolean(false)
```

Set to `true` in `buildClient()`'s `onConnected` callback (both the synchronous initial-connect path and the later automatic-reconnect path) and to `false` in `onDisconnected`. This is distinct from `_connectionState` (`CONNECTED`/`RECONNECTING`/`DISCONNECTED`), which remains the UI-facing signal and can legitimately show `RECONNECTING` while the transport is briefly down mid-automatic-reconnect.

### 2. `connect()` becomes idempotent against a live transport

```kotlin
override suspend fun connect() {
    if (_isTransportConnected.get()) return   // HiveMQ already owns this connection
    ...
}
```

Replaces the current `_connectionState.value == CONNECTED` guard, which doesn't reflect the true "is there already a live client" condition when state has degraded to `DISCONNECTED` while the transport is still (or again) connected.

### 3. `connect()` gets the same timeout `reconnectWith()` already has

```kotlin
withTimeout(15_000L) {
    client.connectWith()...send().await()
    subscribeAndAnnounce(...)
}
```

Bounds every connect attempt so a hung `.await()` can no longer permanently wedge `retryJob`.

### 4. Subscribe-only retry on the automatic-reconnect path

When `onConnected` fires for a *non-initial* connect (i.e., HiveMQ's automatic reconnect just succeeded) and `subscribeAndAnnounce()` throws, do **not** call `scheduleReconnectRetry()` → `connect()` (which would now just no-op per #2 anyway, uselessly). Instead retry `subscribeAndAnnounce()` itself with its own short bounded loop (e.g. 3 attempts, 2s apart, each under its own `withTimeout`). If all attempts fail, log and set `_connectionState = DISCONNECTED` — HiveMQ's automatic reconnect is still armed underneath and will fire `onConnected` again on the next transport cycle if the connection itself drops again, or the subscribe retry loop will eventually succeed once the broker recovers.

### 5. `onDisconnected` sets `RECONNECTING`, not `DISCONNECTED`

```kotlin
onDisconnected = {
    _isTransportConnected.set(false)
    if (mqttClient === client) {
        _connectionState.value = MqttConnectionState.RECONNECTING
    }
}
```

`DISCONNECTED` is reserved for: never successfully connected yet, or an explicit `disconnect()` call. Every other loss-of-transport case is `RECONNECTING`, matching what's actually happening (HiveMQ retrying with capped backoff up to 30s) rather than implying the app has given up.

### 6. `scheduleReconnectRetry()` scope narrows

Only used for the case `automaticReconnect()` doesn't cover: a failed *initial* connect (before any successful connect has ever happened on this client instance). Once a client has connected at least once, all further recovery is HiveMQ's automatic reconnect + the subscribe-retry loop in #4 — `scheduleReconnectRetry()` is not invoked from the post-connect subscribe-failure path anymore.

## Error Handling

- A connect attempt that times out (#3) surfaces as `DISCONNECTED` (initial connect) with `scheduleReconnectRetry()` re-arming, same as today.
- A subscribe-retry exhaustion (#4) surfaces as `DISCONNECTED` even though the transport is technically still up — this is intentionally conservative: the app can't confirm it's actually receiving messages, so it shouldn't claim `CONNECTED`. If the transport later drops for real, `onDisconnected`/`onConnected` cycle normally through HiveMQ's automatic reconnect and recovers.

## Testing

- `MqttRepositoryImplTest`: simulate `onConnected` firing a second time (post-initial) with `subscribeAndAnnounce` throwing — assert `connect()` is never called again (no re-`connectWith()`), assert the subscribe-retry loop runs up to its cap, assert final state is `DISCONNECTED` without a permanently-stuck `retryJob`.
- Assert `connect()` returns immediately (no-op) when `_isTransportConnected` is already `true`.
- Assert `connect()`'s `connectWith().await()` path now honors `withTimeout(15_000L)` — a hung future is cancelled and surfaces as a normal failure, not an indefinite hang.
- Assert `onDisconnected` sets `RECONNECTING` (not `DISCONNECTED`) when `mqttClient === client`.

## Verification Caveat

This root cause was established via static/data-flow tracing of the existing code, not a live reproduction on device (no device/logcat access in this session). Before merging, reproduce the original symptom (force a network drop after login, e.g. airplane-mode toggle or broker restart) on a real device/emulator against both the old and new code to confirm the fix actually resolves the observed behavior, not just the traced defect.
