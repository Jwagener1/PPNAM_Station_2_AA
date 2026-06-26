# Settings Screen — Design Spec
**Date:** 2026-06-26  
**Status:** Approved

---

## Overview

A settings screen reachable from the home screen that lets a supervisor configure the app's MQTT connection, station identity, and advanced timeouts, while exposing diagnostic information (connection status, offline queue) to all operators without a PIN.

---

## Requirements

### Access & Entry
- A gear icon (`Icons.Filled.Settings`) is added to the `AppScaffold` `actions` slot; the icon is only rendered when an `onSettings: (() -> Unit)?` lambda is non-null (same pattern as `onBack`)
- The icon appears **only on the home screen** — no other screen passes a non-null `onSettings`
- Settings is a standard nav destination (back arrow returns to home)

### Two-tier access
- **Diagnostics zone** — visible to all operators, no PIN required
- **Configuration zone** — PIN-gated with fixed PIN `079545`; once unlocked the ViewModel resets back to Locked after a successful "Test & Apply" so config is automatically re-protected

### Apply behaviour
- "Test & Apply": builds a candidate `Mqtt5AsyncClient` from the edited settings, attempts a timed connection (15s timeout), reports success or failure inline on the screen
- On success: persists settings to DataStore, swaps the live MQTT client, resets PIN state to Locked
- On failure: leaves the existing client and persisted settings untouched, shows the error message

---

## Data Model

### `domain/model/AppSettings.kt`
Plain data class with defaults:

| Field | Type | Default |
|---|---|---|
| `stationName` | `String` | `"Station 2"` |
| `scannerId` | `Int` | `1` |
| `mqttHost` | `String` | `"mqtt.sysone.co.za"` |
| `mqttPort` | `Int` | `8884` |
| `mqttUseWebSocket` | `Boolean` | `true` |
| `mqttUseTls` | `Boolean` | `true` |
| `mqttUsername` | `String` | `"admin"` |
| `mqttPassword` | `String` | `"admin"` |
| `requestTimeoutMs` | `Long` | `10000` |
| `queueDrainIntervalMin` | `Int` | `15` |

`scannerId` is stored but not yet wired into any topic or behaviour — reserved for future use.

---

## Data Layer

### `data/settings/SettingsRepository.kt`
`@Singleton`, backed by `androidx.datastore:datastore-preferences`.

```
settingsFlow: Flow<AppSettings>          // reactive — emits on any change
suspend fun current(): AppSettings       // single read (first() from flow)
suspend fun save(settings: AppSettings)  // writes all fields atomically
```

DataStore file name: `app_settings.preferences_pb`

Default values are applied at read time via `?.let { } ?: default` — a fresh install with no stored data returns a full `AppSettings()` with all defaults.

### `data/mqtt/MqttClientFactory.kt`
`@Singleton`. Single public function:
```
fun build(settings: AppSettings): Mqtt5AsyncClient
```

Handles four connection modes derived from `mqttUseWebSocket` and `mqttUseTls`:

| `useWebSocket` | `useTls` | Protocol |
|---|---|---|
| false | false | TCP plain (MQTT 1883) |
| false | true | TCP + TLS (MQTTS 8883) |
| true | false | WebSocket (WS) |
| true | true | WebSocket + TLS (WSS) ← default |

TLS uses the Android system trust store — no custom keystore. Valid public cert on `mqtt.sysone.co.za` requires no additional configuration.

Username/password set via `.simpleAuth()` at builder level — included automatically in every CONNECT packet.

Automatic reconnect: initial delay 1s, max delay 30s (same as current behaviour).

---

## Modified: `domain/repository/MqttRepository.kt`

Add one new method to the interface so `SettingsViewModel` can call it without depending on the impl:

```kotlin
suspend fun reconnectWith(settings: AppSettings): Result<Unit>
```

---

## Modified: `data/mqtt/MqttRepositoryImpl.kt`

**Constructor change:** receives `MqttClientFactory` + `SettingsRepository` instead of a `Mqtt5AsyncClient` directly.

**Client lifecycle:**
- `private var mqttClient: Mqtt5AsyncClient? = null`
- On first `connect()` call: reads `settingsRepository.current()`, builds initial client via factory, then proceeds with connect + subscribe
- On subsequent `connect()` calls (reconnect events): reuses existing client reference

**New function:**
```kotlin
suspend fun reconnectWith(settings: AppSettings): Result<Unit>
```
1. Builds candidate client via `MqttClientFactory.build(settings)`
2. Attempts `connectWith().cleanStart(false).keepAlive(30)` + subscribe within a 15s `withTimeout`
3. **Success path:** disconnects old client (fire-and-forget, swallows exceptions), replaces `mqttClient` reference, emits `CONNECTED`
4. **Failure path:** disconnects candidate (fire-and-forget), leaves old client intact, returns `Result.failure(throwable)`

**`MqttTopics` change:**  
`REQUEST` and `response()` become functions that take `stationName: String`:
```kotlin
fun request(stationName: String) = "${stationName.lowercase().replace(" ", "")}/request"
fun response(stationName: String, deviceId: String) = "${stationName.lowercase().replace(" ", "")}/response/$deviceId"
```
`MqttRepositoryImpl` passes `settingsRepository.current().stationName` when building topics at connect time.

---

## Modified: `di/AppModule.kt`

| Change | Detail |
|---|---|
| Remove `provideMqttClient()` | Factory now owns client creation |
| Add `provideSettingsRepository(ctx)` | `@Singleton`, constructs `DataStore<Preferences>` via `preferencesDataStore` delegate |
| Add `provideMqttClientFactory()` | `@Singleton` |
| `WorkManager` schedule | Reads `queueDrainIntervalMin` via `runBlocking { settingsRepository.current() }` at DI init time; schedules with `ExistingPeriodicWorkPolicy.UPDATE` so a changed interval takes effect on the next app start |

---

## Navigation

- `NavRoutes.SETTINGS = "settings"` added as a new constant
- One new composable destination in the nav graph: `SettingsScreen(onBack = { navController.popBackStack() })`
- `HomeScreen` gains `onNavigateSettings: () -> Unit` parameter
- `AppScaffold` gains optional `onSettings: (() -> Unit)? = null` parameter — renders `Icons.Filled.Settings` icon in `actions` when non-null, tint `TextPrimary`

---

## `ui/settings/SettingsViewModel.kt`

### State

```kotlin
sealed interface PinState { object Locked; object Unlocked }
sealed interface ApplyState { object Idle; object Testing; data class Success(val msg: String); data class Failure(val msg: String) }
```

- `pinState: StateFlow<PinState>` — starts Locked
- `draftSettings: MutableState<AppSettings>` — initialised from `settingsRepository.current()` in `init {}`
- `applyState: StateFlow<ApplyState>` — starts Idle
- `offlineQueueCount: StateFlow<Int>` — derived from `offlineQueueDao.countFlow()`
- `pinInput: MutableState<String>` — bound to the PIN field

### Functions

| Function | Behaviour |
|---|---|
| `submitPin()` | If `pinInput == "079545"` → `pinState = Unlocked`; else shake/clear |
| `updateDraft(settings)` | Replaces `draftSettings` |
| `testAndApply()` | Sets `applyState = Testing`; calls `mqttRepository.reconnectWith(draftSettings)`; on success saves + sets `Success`; on failure sets `Failure(throwable.message)`; on success resets `pinState = Locked` after 2s delay |
| `clearQueue()` | Calls `offlineQueueDao.deleteAll()` |

---

## `ui/settings/SettingsScreen.kt`

Single scrollable `Column` inside `AppScaffold("Settings", ...)`.

### Diagnostics zone (always visible)

```
┌─────────────────────────────────────────────┐
│  DIAGNOSTICS                                │
│  ─────────────────────────────────────────  │
│  Connection       ● Connected               │  ← same pill chip as top bar
│  Offline queue    3 pending    [Clear]       │
│  Version          v1.0 (build 1)            │
└─────────────────────────────────────────────┘
```

"Clear" shows a confirmation `AlertDialog` before calling `viewModel.clearQueue()`.

### Configuration zone

**Locked state:**
```
┌─────────────────────────────────────────────┐
│  CONFIGURATION                              │
│  ─────────────────────────────────────────  │
│  [ PIN ••••••         ]  [ Unlock ]         │
│  Enter supervisor PIN to edit settings      │
└─────────────────────────────────────────────┘
```
PIN field: `KeyboardType.NumberPassword`, `ImeAction.Done` triggers `submitPin()`. Invalid PIN clears the field (no error text — silent, by design).

**Unlocked state — grouped fields:**

Section headers use uppercase + 0.8sp letter spacing (same as `LabelValueRow` label style).

```
STATION
  Station name     [OutlinedTextField            ]
  Scanner ID       [OutlinedTextField, numeric   ]

CONNECTION
  Host             [OutlinedTextField            ]
  Port             [OutlinedTextField, numeric   ]
  WebSocket        [Row: label + Switch          ]
  TLS              [Row: label + Switch          ]
  Username         [OutlinedTextField            ]
  Password         [OutlinedTextField, masked    ]

ADVANCED
  Request timeout (ms)   [OutlinedTextField, numeric]
  Queue drain (min)      [OutlinedTextField, numeric]

[ Test & Apply                                 ]  ← 56dp full-width Button
```

All `OutlinedTextField` instances use the amber focus style (`focusedBorderColor = AmberPrimary`). Switches use `MaterialTheme.colorScheme.primary` (amber) when checked.

### Apply state display (below the button)

| State | Display |
|---|---|
| `Idle` | Nothing |
| `Testing` | `CircularProgressIndicator` (amber, 20dp) + `"Testing connection…"` in `TextMuted` |
| `Success` | `Icons.Filled.CheckCircle` (SuccessGreen, 18dp) + `"Connected — settings saved"` in SuccessGreen |
| `Failure` | `Icons.Filled.Error` (DangerRed, 18dp) + error message in DangerRed |

Displayed in a `Row` with 8dp spacing, centered horizontally, 12dp above the button.

---

## Files Changed / Created

| File | Action |
|---|---|
| `domain/model/AppSettings.kt` | NEW |
| `data/settings/SettingsRepository.kt` | NEW |
| `data/mqtt/MqttClientFactory.kt` | NEW |
| `ui/settings/SettingsViewModel.kt` | NEW |
| `ui/settings/SettingsScreen.kt` | NEW |
| `data/mqtt/MqttRepositoryImpl.kt` | MODIFY — constructor, client lifecycle, `reconnectWith()` |
| `data/mqtt/MqttTopics.kt` | MODIFY — static constants → functions taking `stationName` |
| `di/AppModule.kt` | MODIFY — remove `provideMqttClient`, add factory + settings providers |
| `ui/components/AppScaffold.kt` | MODIFY — add optional `onSettings` parameter |
| `ui/home/HomeScreen.kt` | MODIFY — pass `onNavigateSettings`, gear icon |
| `navigation/NavRoutes.kt` | MODIFY — add `SETTINGS` constant |
| `navigation/AppNavGraph.kt` | MODIFY — add settings destination |
| `app/build.gradle.kts` | MODIFY — add `datastore-preferences` dependency |

---

## Out of Scope

- `scannerId` wired into MQTT topics (deferred)
- PIN change flow (fixed PIN `079545`)
- Encrypted storage of password (factory LAN, PIN-gated UI is the security boundary)
- Per-screen settings access (home screen only)
