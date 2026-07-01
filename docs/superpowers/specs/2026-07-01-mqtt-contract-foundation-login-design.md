# MQTT Contract Foundation & Operator Login — Design Spec

**Date:** 2026-07-01
**Source contract:** `C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md`
**Approach:** Full replacement of the current MQTT transport (topics, envelope, action naming) with the RFID MQTT Contract, plus the operator login/session feature the contract requires. First of several phases; later phases (job card/BOM, ingredient scan + manager approval, hopper start/end, Rajoo allocation split, holding recovery) are separate specs that build on this one.
**Scope:** New topic scheme, typed per-message envelopes, device identity setting, mandatory login-at-startup with username/password and badge-scan, in-memory operator session, logout. Does **not** touch any existing mixing/rajoo/rfid/dashboard business logic — those are migrated to the new transport in later phases.

---

## Context

The app (running on a Zebra handheld with DataWedge RFID/barcode scanning) is the **handheld** in the contract's terminology; the WPF backend on the factory LAN is **Station 2**, the responder. Today the app speaks a bespoke protocol: one shared `{station}/request` publish topic and `{station}/response/{deviceId}` subscribe topic, a generic envelope (`correlationId`, `deviceId`, `action`, `data: String`), and ad-hoc kebab-case action strings (`lookup-job`, `validate-ingredient`, ...). None of the contract's envelope fields (`messageId`, `schemaVersion`, `operatorSessionId`, `timestampUtc`, `correlationKey`) or its per-message-type topic routing exist in code, and there is no operator login/session concept anywhere in the app.

The contract requires an active operator session (`operatorSessionId`) on every production message. Nothing else in the contract can be exercised end-to-end without login working first, so this phase builds:

1. The new topic/envelope foundation all later phases will reuse.
2. The login/logout flow itself (`reader_login_requested`, `login_tag_scanned`, `reader_logout_requested` → `operator_context`).

Decisions carried in from prior sessions: login failures show an inline error and allow immediate retry with **no lockout** (chosen over temporary lockout or differentiated network-vs-credentials handling). Login is **mandatory at startup** — no screen is reachable before an operator is authenticated — and the session is **not** persisted across process restarts; every cold start requires a fresh login.

---

## 1. MQTT Layer

### 1.1 Topics — `MqttTopics` rewritten

```kotlin
object MqttTopics {
    fun request(deviceId: String, requestType: String): String =
        "PPNAM/$deviceId/$requestType"

    fun response(deviceId: String, responseType: String): String =
        "PPNAM/$deviceId/$responseType"

    fun responseWildcard(deviceId: String): String =
        "PPNAM/$deviceId/+"

    fun status(deviceId: String): String =
        "PPNAM/$deviceId/status"

    fun stationStatus(stationName: String): String =
        "PPNAM/${stationName.trim().lowercase().replace(" ", "_")}/status"
}
```

- The app subscribes once, on connect, to `responseWildcard(deviceId)` to receive every response type on a single subscription — replacing today's single fixed response-topic subscribe.
- `stationStatus()` remains keyed off `AppSettings.stationName` (default `"Station 2"` → `"station_2"`), matching the contract's literal `PPNAM/station_2/status` example. This is the only topic still derived from `stationName`.
- The existing `hopperStatus(stationName)` broadcast topic (`{station}/hopper/status`, added 2026-06-30) is out of scope for this phase; it is not part of the RFID MQTT Contract and is left as-is until a later phase reconciles it (contract has no equivalent broadcast topic documented — flagged as an open question, see §8).

### 1.2 Device identity — new `AppSettings.deviceId`

```kotlin
data class AppSettings(
    val stationName: String = "Station 2",
    val deviceId: String = "handheld_1",   // NEW
    val scannerId: Int = 1,
    val mqttHost: String = "mqtt.sysone.co.za",
    val mqttPort: Int = 8884,
    val mqttUseWebSocket: Boolean = true,
    val mqttUseTls: Boolean = true,
    val mqttUsername: String = "admin",
    val mqttPassword: String = "admin",
    val requestTimeoutMs: Long = 10_000L,
    val queueDrainIntervalMin: Int = 15
)
```

- `deviceId` becomes a configured, human-assigned identity (matching the contract's `"handheld_1"` style and its "device must be configured in Station 2" rule), editable on `SettingsScreen` alongside the other MQTT fields.
- The current `Settings.Secure.ANDROID_ID`-sourced device id (`MqttRepositoryImpl` constructor) is removed. `MqttRepositoryImpl` reads `deviceId` from `SettingsRepository`/`AppSettings` the same way it already reads `stationName`.
- Persisted via the existing DataStore-backed `SettingsRepository`, same mechanism as the other settings fields.

### 1.3 Envelope — typed per-message classes, no generic wrapper

The generic `MqttRequest` / `MqttResponseMessage` wrapper is retired in favor of one flat data class per message type, matching the contract's flat JSON exactly. A shared field set is documented but not implemented as inheritance (Gson favors flat data classes; a shared `interface` would add no serialization value):

Common fields on every request: `messageId: String`, `schemaVersion: String = "1.0"`, `deviceId: String`, `operatorSessionId: String = ""`, `timestampUtc: String`, `correlationKey: String`.

This phase's concrete classes (`data/mqtt/dto/` package, new):

```kotlin
data class ReaderLoginRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val username: String,
    val password: String
)

data class LoginTagScannedRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val badgeTag: String
)

data class ReaderLogoutRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String,
    val timestampUtc: String,
    val correlationKey: String
)

data class OperatorContextResponse(
    val messageId: String,
    val schemaVersion: String,
    val deviceId: String,
    val operatorSessionId: String?,      // null/blank = no active session (e.g. after logout)
    val timestampUtc: String,
    val correlationKey: String,
    val success: Boolean,
    val errorMessage: String?,
    val operatorId: String?,
    val operatorName: String?,
    val role: String?,
    val allowedActions: List<String> = emptyList(),
    val allowedTabs: List<String> = emptyList()
)
```

`success`/`errorMessage` field names on `OperatorContextResponse` are this phase's assumption — the contract prose doesn't show a full JSON example for `operator_context` (see §8, open question). They follow the pattern used for other Station-2-authored response envelopes elsewhere in the contract.

### 1.4 `MqttRepository` — new typed send path

`MqttRepository` gains a generic typed request/response method used by login/logout and reused by every later phase:

```kotlin
interface MqttRepository {
    // existing connect/disconnect/connectionState/reconnectWith/hopperStatusUpdates unchanged this phase

    suspend fun <TResp> sendTyped(
        requestType: String,
        responseType: String,
        requestJson: String,
        responseClass: Class<TResp>,
        allowOfflineQueue: Boolean
    ): MqttTypedResult<TResp>
}

sealed class MqttTypedResult<out T> {
    data class Success<T>(val response: T) : MqttTypedResult<T>()
    data class Error(val message: String) : MqttTypedResult<Nothing>()
    object Disconnected : MqttTypedResult<Nothing>()   // only reachable when allowOfflineQueue = false
    object Queued : MqttTypedResult<Nothing>()          // only reachable when allowOfflineQueue = true
}
```

- Publishes to `MqttTopics.request(deviceId, requestType)`.
- Correlates the response by matching `messageId` on the next message received on `MqttTopics.response(deviceId, responseType)` whose `correlationKey`/`messageId` matches what was sent (assumption, see §8) via a `Flow.filter/first` pattern, same shape as today's `sendWithTimeout`.
- When `allowOfflineQueue = false` (used by login/logout, §1.5) and the client isn't connected, returns `Disconnected` immediately rather than queuing.
- When `allowOfflineQueue = true` (used by all later-phase workflow actions), preserves today's behavior: queue via `OfflineQueueDao` on timeout/disconnect/exception.
- The existing generic `send(action: String, dataJson: String): MqttResult` (string-action path) stays in place, unchanged, for the still-kebab-case actions until each later phase migrates them off it. Both paths coexist on `MqttRepositoryImpl` during the migration; the old path is deleted once the last kebab-case action (Rajoo/holding-recovery phase) is migrated.

### 1.5 Login is never offline-queued

`AuthUseCase` (§3) always calls `sendTyped(..., allowOfflineQueue = false)`. If the client is disconnected, the operator sees a connection error and can retry — queuing a login nobody is waiting on would be meaningless. This applies to login and logout only; every other contract message added in later phases uses `allowOfflineQueue = true`.

---

## 2. Domain Models

### 2.1 New `OperatorSession`

```kotlin
data class OperatorSession(
    val operatorSessionId: String,
    val operatorId: String,
    val operatorName: String,
    val role: String,
    val allowedActions: List<String>,
    val allowedTabs: List<String>
)
```

### 2.2 New `OperatorSessionHolder` (Hilt `@Singleton`, `data/session/`)

```kotlin
@Singleton
class OperatorSessionHolder @Inject constructor() {
    private val _session = MutableStateFlow<OperatorSession?>(null)
    val session: StateFlow<OperatorSession?> = _session.asStateFlow()

    fun set(session: OperatorSession) { _session.value = session }
    fun clear() { _session.value = null }
    fun currentSessionIdOrEmpty(): String = _session.value?.operatorSessionId ?: ""
}
```

- In-memory only — no Room/DataStore persistence, per "always require fresh login."
- Every later-phase use case reads `currentSessionIdOrEmpty()` to populate its request's `operatorSessionId`.
- `AppNavGraph` observes `session` (via a thin `SessionViewModel` or directly in the graph's root composable) to decide whether the current destination is still valid; if a screen requiring a session sees it go `null` (forced logout, see §4.3), it navigates back to Login.

### 2.3 New `LoginMethod` (sealed, for the ViewModel)

```kotlin
sealed class LoginMethod {
    data class Credentials(val username: String, val password: String) : LoginMethod()
    data class Badge(val badgeTag: String) : LoginMethod()
}
```

---

## 3. Use Case — new `AuthUseCase`

```kotlin
class AuthUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val sessionHolder: OperatorSessionHolder,
    private val deviceIdProvider: () -> String   // reads current AppSettings.deviceId
) {
    suspend fun login(method: LoginMethod): Result<OperatorSession>
    suspend fun logout(): Result<Unit>
}
```

- `login()` builds `ReaderLoginRequest` or `LoginTagScannedRequest` depending on `method`, calls `sendTyped("reader_login_requested" | "login_tag_scanned", "operator_context", ..., allowOfflineQueue = false)`.
  - `Success` with `success = true` and non-blank `operatorSessionId` → builds `OperatorSession`, calls `sessionHolder.set(...)`, returns `Result.success`.
  - `Success` with `success = false` → `Result.failure(Exception(errorMessage ?: "Login failed"))`.
  - `Error` / `Disconnected` → `Result.failure` with a connection-appropriate message.
- `logout()` builds `ReaderLogoutRequest` using the current session id, calls `sendTyped("reader_logout_requested", "operator_context", ..., allowOfflineQueue = false)`, clears `sessionHolder` regardless of server response (local logout must always succeed from the operator's perspective — the contract's `reader_logout_requested` is about closing the *reader's* session server-side, but the device shouldn't get stuck showing a stale session if the network drops mid-logout).

---

## 4. ViewModel — new `LoginViewModel`

### 4.1 `LoginUiState`

```kotlin
sealed class LoginUiState {
    object Idle : LoginUiState()
    object LoggingIn : LoginUiState()
    data class Error(val message: String) : LoginUiState()
    object LoggedIn : LoginUiState()
}
```

### 4.2 Methods

```
submitCredentials(username, password)
  → state = LoggingIn → AuthUseCase.login(Credentials(...))
  → success: state = LoggedIn, nav event fires to Home
  → failure: state = Error(message); form stays populated for retry (no lockout, no attempt counter)

onBadgeScanned(badgeTag)   // collected from ScanEventBus filterIsInstance<ScanEvent.RfidTag>()
  → same success/failure handling as submitCredentials, via Badge(...)

retry()  → state = Idle   // clears the error card, form/scan listener re-armed
```

Badge scanning is listened for continuously while `LoginUiState` is `Idle` or `Error` (same `scanJob: Job?` cancel-and-restart pattern used elsewhere), so an operator can scan a badge at any point, including right after a failed password attempt, without extra navigation.

### 4.3 Session-loss handling (app-wide)

A lightweight `SessionViewModel` (or the same responsibility folded into `AppNavGraph`'s root) collects `OperatorSessionHolder.session`. If it transitions from non-null to `null` outside of an explicit user-initiated logout (i.e., some later-phase use case detects an invalid/expired session and calls `sessionHolder.clear()`), the graph pops the back stack to `Login` (inclusive) and shows a "Session ended, please log in again" message. This phase only wires the observer and the clear-and-redirect mechanism; no later-phase use case actually triggers it yet since the contract doesn't document server-initiated session invalidation for this phase's messages.

---

## 5. Screens and Navigation

### 5.1 `LoginScreen` (new)

Route: `login` — becomes `AppNavGraph`'s **start destination**, replacing `home`.

- Username + password fields with a "Log in" button (`reader_login_requested`).
- A persistent `ScanPromptCard`-style hint: "Or scan your badge" — no separate mode toggle, both input paths are always live.
- `LoggingIn` → spinner, inputs disabled, badge scanning still armed (an operator mid-typing might badge-scan instead; whichever completes first wins — the ViewModel simply processes whichever `LoginMethod` result arrives).
- `Error` → inline red error text above the form, inputs re-enabled immediately, no delay/lockout/backoff.
- `LoggedIn` → one-shot nav event (`Channel<String>` pattern, matching existing ViewModels) to `home`, clearing `login` from the back stack (`popUpTo(NavRoutes.LOGIN) { inclusive = true }`).

### 5.2 `AppScaffold` — operator identity + logout

- Displays `operatorName` / `role` (from `OperatorSessionHolder`) in the top bar, replacing no existing element (this is new real estate — confirm placement doesn't collide with the existing connection-state/pending-count indicators already shown there).
- Tapping the identity opens a small confirm dialog → **Log out** → `AuthUseCase.logout()` → on completion (regardless of network result, per §3) navigate to `login` with the back stack cleared (`popUpTo(0)`).

### 5.3 `AppNavGraph` changes

| Old | New |
|---|---|
| start destination `home` | start destination `login` |
| *(none)* | `login` → `LoginScreen` |

All other existing routes (`mixing/*`, `rajoo/*`, `rfid/recovery`, `dashboard`, `settings`) are unchanged this phase; they simply become unreachable until `login` completes, by virtue of `login` being the only entry point.

---

## 6. DI Wiring (`AppModule`)

- `OperatorSessionHolder` — new `@Singleton`-scoped provider (or `@Inject constructor` is enough given no interface).
- `AuthUseCase` — new, depends on `MqttRepository`, `OperatorSessionHolder`, `SettingsRepository` (for `deviceId`).
- No changes to `AppDatabase`, `WorkManager`/`OfflineQueueWorker`, or the HiveMQ client factory wiring — login intentionally never touches the offline queue.

---

## 7. Test Plan

- `MqttTopics` — new topic functions produce the exact contract paths (`PPNAM/handheld_1/reader_login_requested`, `PPNAM/handheld_1/operator_context`, `PPNAM/station_2/status`).
- Envelope serialization — each new DTO round-trips through Gson with the exact field names the contract specifies (case-sensitive), including default `schemaVersion = "1.0"`.
- `AuthUseCase.login` — credentials success, credentials failure (`success=false`), badge success, badge failure, disconnected (no queueing occurs — assert `OfflineQueueDao.insert` is never called).
- `AuthUseCase.logout` — session cleared locally even when the MQTT call fails/times out.
- `LoginViewModel` — `Idle → LoggingIn → LoggedIn` on success; `Idle → LoggingIn → Error → Idle` on `retry()`; badge scan accepted while `Error` is shown; concurrent credential-submit + badge-scan resolves to a single outcome (no double session).
- `OperatorSessionHolder` — `set`/`clear`/`currentSessionIdOrEmpty` behavior.
- Navigation — cold start lands on `LoginScreen`; successful login pops to `home` with `login` removed from back stack; logout pops to `login` with the entire stack cleared; back button on `home` after login does not return to `login`.

---

## 8. Open Questions / Assumptions to Verify Against the Real Backend

1. **Response correlation field:** the contract doesn't show a full `operator_context` JSON example, so this spec assumes the response echoes the request's `messageId`/`correlationKey` for correlation. If Station 2 instead correlates purely by "next message on this response topic," the `sendTyped` matching logic (§1.4) needs adjusting once the real backend is available to test against.
2. **`operator_context` field names:** `success`/`errorMessage`/`operatorId`/`operatorName`/`role`/`allowedActions`/`allowedTabs` are inferred from the contract's prose description, not a literal example. Verify exact field names/casing against the backend before this ships.
3. **`station2/hopper/status` broadcast:** not part of the RFID MQTT Contract; left untouched this phase. A later phase should confirm whether Station 2 still publishes it under the old topic scheme or whether it's been folded into the new contract under a different name.
4. **Server-initiated session invalidation:** no contract message for this is documented; §4.3's observer is wired but currently has no trigger. Later phases should confirm whether any response can carry a "session no longer valid" signal.
