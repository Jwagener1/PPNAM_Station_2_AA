# Graph Report - PPNAM_Station_2_AA  (2026-07-21)

## Corpus Check
- 654 files · ~1,617,016 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1495 nodes · 2111 edges · 150 communities (80 shown, 70 thin omitted)
- Extraction: 89% EXTRACTED · 11% INFERRED · 0% AMBIGUOUS · INFERRED: 230 edges (avg confidence: 0.79)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `24da120a`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Room DAO Tests
- Offline Queue Repository & RFID Scan Bus
- Operator Session & App Entry
- Pre-Mix Hopper Domain Models
- MQTT Message Envelope & Repository Impl
- Mixing ViewModel State Machine
- Typed MQTT Result & Repository Contract
- Operator Login & Auth Use Case
- Job Resume & Lookup Flow
- Dashboard & RFID Recovery ViewModels
- Job Cancel & Exception Approval Tests
- MQTT Client Factory & Reconnection Tests
- Mixing Screen Flow & Navigation
- Rajoo Allocation ViewModel
- Job Card Lifecycle Planning Docs
- MQTT Topic Builder Tests
- Rajoo Use Case & Tests
- App Settings Defaults & Tests
- Final MQTT Bugfix Round
- Shared UI Scaffold & Screens
- MQTT Topic Construction
- Dashboard Use Case & Tests
- Shared Scan UI Components
- Login & Session Design Docs
- Production Order & BOM Line Model
- Settings ViewModel Tests
- Settings Feature Design Docs
- Mixing Use Case Core Actions
- Settings Screen UI
- Settings PIN State Machine
- Android App Architecture Design Docs
- Pre-Mix Hopper Design Docs
- MqttRepository
- MixingViewModel.kt
- BomLine
- MQTT Schema 3.0 — Auth & Session Design
- BOM Line Response & Lookup Tests
- Navigation Routes
- UI Modernisation Design Docs
- MQTT Reconnection Fix Docs
- Settings Persistence Repository
- App / Hilt Bootstrap
- Sequencing
- MQTT Client Factory Tests
- Gradle Wrapper Script
- Android Instrumented Test Boilerplate
- MQTT Repository Reconnect Contract
- Unit Test Boilerplate
- Repo Rules & Graphify Workflow
- Design
- Global Constraints
- BOM Ingredient Progress Display — Design Spec
- Global Constraints
- Global Constraints
- HomeTile
- SessionStateTest
- IngredientScanOutcome
- External Repo Read-Only Except RFID_MQTT_CONTRACT.md
- Graphify Query-First Workflow for Codebase Questions
- AppModule (Hilt DI)
- DataWedgeReceiver
- HomeViewModel
- IngredientScanScreen
- JobLookupScreen
- MixerCodeScreen
- MixingUseCase
- MixingViewModel
- MqttRepository interface
- MqttRepositoryImpl
- MqttTopics
- OfflineQueueRepository
- PreMix / ScannedIngredient domain model
- PreMixCompleteScreen
- ProductionOrder / BomLine domain model
- ScanEventBus
- AppScaffold shared composable
- LabelValueRow shared composable
- AppSettings data class
- MqttClientFactory
- SettingsRepository (DataStore-backed)
- SettingsScreen
- SettingsViewModel
- HopperScanScreen
- HopperStatus / HopperAvailability
- IngredientValidationResult (Valid/Invalid)
- AuthUseCase
- OperatorSession data class
- OperatorSessionHolder
- MqttRepository.sendTyped / MqttTypedResult
- ActiveJobCardSummary / ActiveJobCardsListResponse
- MixingUseCase.cancelJob / PreMixCancelResultResponse
- CancelOutcome (Confirmed/Failed)
- handleTransportDisconnected
- isTransportConnected AtomicBoolean flag
- retryBounded generic retry helper
- DataWedge RFID/Barcode Scan Integration via ScanEventBus
- Layered MVVM + Clean Architecture Pattern
- MQTT Request/Response Correlation-ID Pattern
- Two-Layer Offline Queue Retry Strategy (connectivity callback + WorkManager fallback)
- Dark Graphite + Amber Design System
- Test & Apply MQTT Reconnect Flow
- Hopper Allocation Workflow (replaces Mixer Code capture)
- Supervisor-Gated Ingredient Exception Override
- Login Mandatory at Startup (LoginScreen is nav-graph start destination)
- LoginScreen
- LoginViewModel
- In-Memory-Only Operator Session (never persisted, fresh login every cold start)
- Parallel Typed sendTyped Transport (coexists with legacy kebab-case transport during migration)
- Active Job List Tap-to-Load (spec section B1)
- Cancel With Role-Gated Approval (cancel_premix_direct capability, backend re-verifies server-side as defense-in-depth) (spec section B3)
- Per-Line Allocation Status Surfaced from bom_loaded (spec section B2)
- Transport-Connected State Guard + Bounded Subscribe Retry Fix
- Superseded Decision: Backend Already Auto-Resumes Pre-Mix by jobCardNumber+operator+handheld
- RFID MQTT Contract (sibling repo, RFID_MQTT_CONTRACT.md)
- Global Constraints
- SettingsViewModel.kt
- Design
- LoginViewModelTest
- MQTT Schema 3.0 — Hopper Board & Machine Cycles Design
- SettingsRepository
- MQTT Schema 3.0 — Collection & Ingredients Design
- BomLine
- main
- Sequencing
- Request to Station 2: the timestamp acceptance window
- build_response
- common.py
- SimLogger
- IngredientScanResultTest
- Rejection
- MixingViewModel.kt
- AuthUseCase
- Simulator
- HomeViewModel
- Architecture
- state.py
- LoginViewModel
- jobcards.py
- LoginViewModelTest
- MqttRepository
- Station 2 Backend Simulator
- LoginViewModel.kt
- .request
- ConnectionStatus
- __init__.py

## God Nodes (most connected - your core abstractions)
1. `MixingViewModelTest` - 63 edges
2. `MixingViewModel` - 61 edges
3. `MixingUseCaseTest` - 47 edges
4. `MqttRepositoryImpl` - 36 edges
5. `OperatorSessionHolder` - 23 edges
6. `World` - 23 edges
7. `MqttRequestCorrelationTest` - 22 edges
8. `PalletUseCaseTest` - 22 edges
9. `AppSettings` - 21 edges
10. `BomLine` - 19 edges

## Surprising Connections (you probably didn't know these)
- `logout()` --calls--> `build_response()`  [INFERRED]
  tools/backend-sim/handlers/auth.py → tools/backend-sim/envelope.py
- `AppNavGraph()` --calls--> `SessionWatcher()`  [INFERRED]
  app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt → app/src/main/java/com/ppnam/station2aa/ui/session/SessionWatcher.kt
- `Rejection` --uses--> `World`  [INFERRED]
  tools/backend-sim/envelope.py → tools/backend-sim/state.py
- `scan()` --calls--> `Rejection`  [INFERRED]
  tools/backend-sim/handlers/ingredients.py → tools/backend-sim/envelope.py
- `load()` --calls--> `Rejection`  [INFERRED]
  tools/backend-sim/handlers/jobcards.py → tools/backend-sim/envelope.py

## Import Cycles
- None detected.

## Communities (150 total, 70 thin omitted)

### Community 0 - "Room DAO Tests"
Cohesion: 0.10
Nodes (8): BomCacheDaoTest, AppDatabase, BomCacheDao, String, BomCacheEntity, AppModule, Context, RoomDatabase

### Community 1 - "Offline Queue Repository & RFID Scan Bus"
Cohesion: 0.08
Nodes (9): ErrorCode, NextAction, Boolean, Int, String, MqttRequestCorrelationTest, TestBody, MqttVocabularyTest (+1 more)

### Community 2 - "Operator Session & App Entry"
Cohesion: 0.21
Nodes (5): StateFlow, String, OperatorSession, OperatorSessionHolder, OperatorSessionHolderTest

### Community 3 - "Pre-Mix Hopper Domain Models"
Cohesion: 0.05
Nodes (5): BomLine, Boolean, String, MixingViewModelTest, MutableSharedFlow

### Community 4 - "MQTT Message Envelope & Repository Impl"
Cohesion: 0.05
Nodes (18): Any, Boolean, Class, Int, Job, Long, Mqtt5AsyncClient, StateFlow (+10 more)

### Community 5 - "Mixing ViewModel State Machine"
Cohesion: 0.12
Nodes (13): ProductionOrder, IngredientExceptionApproval, Boolean, Double, Flow, Int, Job, List (+5 more)

### Community 6 - "Typed MQTT Result & Repository Contract"
Cohesion: 0.13
Nodes (9): PalletLookupResultResponse, Accepted, FailureKind, T, MqttOutcome, NoResponse, Rejected, PalletUseCaseTest (+1 more)

### Community 8 - "Job Resume & Lookup Flow"
Cohesion: 0.11
Nodes (18): File Map, Global Constraints, PPNAM Station 2 Android App — Implementation Plan, Self-Review Checklist, Task 10: MixingUseCase & Job Lookup Screen, Task 11: Remaining Mixing Screens (IngredientScan → MixerCode → PreMixComplete), Task 12: Rajoo Flow, Task 13: RFID Recovery (+10 more)

### Community 9 - "Dashboard & RFID Recovery ViewModels"
Cohesion: 0.07
Nodes (20): HoldingRecoveryPayload, PalletLookupPayload, PalletInfo, PalletState, Unit, String, PalletUseCase, Error (+12 more)

### Community 10 - "Job Cancel & Exception Approval Tests"
Cohesion: 0.08
Nodes (24): Definition of Done, Global Constraints, Handoff to sub-project 2, MQTT Schema 3.0 Protocol Foundation Implementation Plan, Open questions for the Station 2 developer, QoS must be verified by inspection, not by unit test, Sequencing Rationale, Task 10: Pallet lookup and holding recovery (+16 more)

### Community 11 - "MQTT Client Factory & Reconnection Tests"
Cohesion: 0.14
Nodes (3): Mqtt5AsyncClient, MqttClientFactory, MqttRepositoryImplTest

### Community 12 - "Mixing Screen Flow & Navigation"
Cohesion: 0.05
Nodes (35): MainActivity, AppNavGraph(), NavHostController, AppScaffold(), String, Unit, String, LabelValueRow() (+27 more)

### Community 13 - "Rajoo Allocation ViewModel"
Cohesion: 0.09
Nodes (22): 1. The transport owns the envelope, 2. Correlation, 3. Retry, 4. Result type, 5. Error and nextAction vocabulary, 6. Topics, 7. Presence, 8. Clock skew (+14 more)

### Community 14 - "Job Card Lifecycle Planning Docs"
Cohesion: 0.10
Nodes (19): §6 — Contract Doc Sync (already applied), App, App, B1 — Active Job List, B2 — Per-Line Allocation Status, B3 — Cancel With Role-Gated Approval, Backend, Backend (+11 more)

### Community 16 - "Rajoo Use Case & Tests"
Cohesion: 0.27
Nodes (4): Any, String, LoginPayload, RequestEnvelopeTest

### Community 17 - "App Settings Defaults & Tests"
Cohesion: 0.16
Nodes (4): AppSettings, Unit, MqttClientFactoryTest, AppSettingsTest

### Community 18 - "Final MQTT Bugfix Round"
Cohesion: 0.29
Nodes (7): Barcode, SharedFlow, RfidTag, ScanEvent, ScanEventBus, SharedFlow, ScanRepository

### Community 19 - "Shared UI Scaffold & Screens"
Cohesion: 0.15
Nodes (6): EmptyPayload, Any, String, RequestEnvelope, ResponseEnvelopeTest, Gson

### Community 21 - "Dashboard Use Case & Tests"
Cohesion: 0.18
Nodes (25): _apply_finish(), area_overview(), _cycle_payload(), _equipment_payload(), finish(), _finish_next_action(), force_close(), _machine_result() (+17 more)

### Community 22 - "Shared Scan UI Components"
Cohesion: 0.20
Nodes (15): Cancelling, CancelOutcome, Confirmed, EnteringBagDetails, EnteringQuantityDetails, Error, Failed, Idle (+7 more)

### Community 23 - "Login & Session Design Docs"
Cohesion: 0.08
Nodes (24): 1.1 Topics — `MqttTopics` rewritten, 1.2 Device identity — new `AppSettings.deviceId`, 1.3 Envelope — typed per-message classes, no generic wrapper, 1.4 `MqttRepository` — new typed send path, 1.5 Login is never offline-queued, 1. MQTT Layer, 2.1 New `OperatorSession`, 2.2 New `OperatorSessionHolder` (Hilt `@Singleton`, `data/session/`) (+16 more)

### Community 24 - "Production Order & BOM Line Model"
Cohesion: 0.12
Nodes (15): Deferred / open items (carry into SP4b planning), File Structure, Global Constraints, MQTT Schema 4.0 Foundation (SP4a) Implementation Plan, Task 10: Upgrade signal — `client_upgrade_required` as a blocking state, Task 11: SP4a acceptance gate, Task 1: Branch + simulator envelope — schema 4.0 with the §12 compatibility boundary, Task 2: Simulator world state v4 — equipment topology, MixBatch/Cycle/Run (+7 more)

### Community 25 - "Settings ViewModel Tests"
Cohesion: 0.12
Nodes (15): Deleted files, File Map, Global Constraints, Manual Test Checklist, Modified files, MQTT Pre-Mix & Hopper Workflow Implementation Plan, New files, Task 1: Domain Models (+7 more)

### Community 26 - "Settings Feature Design Docs"
Cohesion: 0.08
Nodes (24): Access & Entry, Apply behaviour, Apply state display (below the button), Configuration zone, Data Layer, Data Model, `data/mqtt/MqttClientFactory.kt`, `data/settings/SettingsRepository.kt` (+16 more)

### Community 29 - "Settings PIN State Machine"
Cohesion: 0.13
Nodes (14): Final check, Global Constraints, MQTT Contract Foundation & Operator Login Implementation Plan, Task 10: Operator identity + logout (`AppScaffold`, `HomeViewModel`, `HomeScreen`), Task 11: `SettingsScreen` — Device ID field, Task 1: `AppSettings.deviceId` + persistence, Task 2: `MqttTopics` — contract topic functions, Task 3: Contract envelope DTOs (+6 more)

### Community 30 - "Android App Architecture Design Docs"
Cohesion: 0.08
Nodes (23): 10. Dependencies, 11. Open Items, 1. Purpose & Scope, 2.1 Pattern, 2.2 Package Structure, 2. Architecture, 3. Screens & Navigation, 4.1 Pattern (+15 more)

### Community 31 - "Pre-Mix Hopper Design Docs"
Cohesion: 0.07
Nodes (29): 1.1 Updated and new action strings, 1.2 New broadcast subscription — `station2/hopper/status`, 1.3 MqttRepository interface + MqttRepositoryImpl changes, 1. MQTT Layer, 2.1 Updated `BomLine`, 2.2 New `IngredientValidationResult`, 2.3 New `HopperStatus`, 2.4 Updated `ScannedIngredient` (+21 more)

### Community 32 - "MqttRepository"
Cohesion: 0.24
Nodes (3): ConnectionStatusTest, Boolean, Long

### Community 33 - "MixingViewModel.kt"
Cohesion: 0.09
Nodes (11): IngredientScanPayload, ShortBagWaiverPayload, CollectionResumePayload, IngredientCollectionCancelPayload, JobCardLoadPayload, Double, List, String (+3 more)

### Community 34 - "BomLine"
Cohesion: 0.27
Nodes (3): BomLineTest, Double, Int

### Community 35 - "MQTT Schema 3.0 — Auth & Session Design"
Cohesion: 0.14
Nodes (13): Connection status: surfacing what sub-project 1 exposed, Context, Design decision: intercept `session_required` in the transport, Inherited defect: the MixingViewModel scan race, MQTT Schema 3.0 — Auth & Session Design, Navigation on session loss, Open questions for the Station 2 developer, Scope (+5 more)

### Community 36 - "BOM Line Response & Lookup Tests"
Cohesion: 0.08
Nodes (8): IngredientScanResultResponse, ActiveJobCardsListResponse, ActiveJobCardSummary, BomLineResponse, BomLoadedResponse, CollectionSummaryResponse, IngredientCollectionCancelResultResponse, MixingUseCaseTest

### Community 38 - "UI Modernisation Design Docs"
Cohesion: 0.11
Nodes (18): 1. Color System, 2. Typography, 3. AppScaffold Component, 4. HomeScreen, 5. Mixing Workflow Screens, 6. Rajoo Workflow Screens, 7. RfidRecoveryScreen, 8. DashboardScreen (+10 more)

### Community 39 - "MQTT Reconnection Fix Docs"
Cohesion: 0.13
Nodes (14): 1. New internal transport-state tracking, 2. `connect()` becomes idempotent against a live transport, 3. `connect()` gets the same timeout `reconnectWith()` already has, 4. Subscribe-only retry on the automatic-reconnect path, 5. `onDisconnected` sets `RECONNECTING`, not `DISCONNECTED`, 6. `scheduleReconnectRetry()` scope narrows, Approaches Considered, Context (+6 more)

### Community 40 - "Settings Persistence Repository"
Cohesion: 0.14
Nodes (13): File Map, Global Constraints, Self-Review Checklist, Settings Screen Implementation Plan, Task 1: AppSettings data class + DataStore dependency, Task 2: SettingsRepository, Task 3: MqttClientFactory, Task 4: Interface + DAO + Topics changes (+5 more)

### Community 41 - "App / Hilt Bootstrap"
Cohesion: 0.17
Nodes (8): DataWedgeReceiver, Context, PpnamApplication, Application, BroadcastReceiver, Configuration, HiltWorkerFactory, Intent

### Community 42 - "Sequencing"
Cohesion: 0.15
Nodes (12): Definition of Done, Global Constraints, Handoff to sub-project 3, MQTT Schema 3.0 Auth & Session Implementation Plan, Open questions for the Station 2 developer, Sequencing, Task 1: SessionState through the DTO and model, Task 2: Intercept session_required in the transport (+4 more)

### Community 43 - "MQTT Client Factory Tests"
Cohesion: 0.15
Nodes (12): 1. Scan interaction, 2. Live progress replaces the static snapshot, 3. Exception → manager approval (one uniform flow), 4. Pallet-recovery detour, 5. New `MixingUiState` states, 6. Removed, Context, Data verified from source (not assumed) (+4 more)

### Community 44 - "Gradle Wrapper Script"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 46 - "MQTT Repository Reconnect Contract"
Cohesion: 0.18
Nodes (10): Global Constraints, Task 1: Theme Layer + Material Icons Dependency, Task 2: Shared UI Components — AppScaffold & LabelValueRow, Task 3: ViewModel Connection State Flows, Task 4: HomeScreen Redesign, Task 5: Mixing Screens, Task 6: Rajoo Screens, Task 7: RFID Recovery Screen (+2 more)

### Community 48 - "Repo Rules & Graphify Workflow"
Cohesion: 0.50
Nodes (3): External directory: C:\Dev\PPNAM-Station-2, graphify, Repo Rules

### Community 54 - "Design"
Cohesion: 0.18
Nodes (10): Android app — data layer, Android app — domain layer, Android app — ViewModel/UI, Contract (`C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` only), Design, Error handling, Out of scope, Problem (+2 more)

### Community 55 - "Global Constraints"
Cohesion: 0.20
Nodes (9): Global Constraints, Ingredient Scanning Migration Implementation Plan, Task 1: Ingredient-scan contract DTOs and BomLine bag-progress fields, Task 2: MixingUseCase.scanIngredient, Task 3: MixingUseCase.approveManagerException, Task 4: MixingUseCase.recoverHolding, Task 5: MixingViewModel — pallet-scan-driven ingredient flow, Task 6: IngredientScanScreen — bag-entry sheet and new dialogs (+1 more)

### Community 56 - "BOM Ingredient Progress Display — Design Spec"
Cohesion: 0.20
Nodes (9): 1. `BomLine` gains a `uom` field, 2. `MixingUseCase.lookupJob` maps `uomCode` through, 3. `IngredientScanScreen` per-line card, BOM Ingredient Progress Display — Design Spec, Context, Design, Formatting, Out of Scope (+1 more)

### Community 57 - "Global Constraints"
Cohesion: 0.22
Nodes (8): Global Constraints, Job Card Lifecycle — Android Implementation Plan, Task 1: Per-line allocation status (§B2), Task 2: Active job list — DTOs, use case, view model (§B1), Task 3: `JobLookupScreen` — render active jobs, tap-to-load (§B1), Task 4: Cancel DTOs and use case (§B3), Task 5: `MixingViewModel` cancel state machine and role gate (§B3), Task 6: `IngredientScanScreen` — approval dialog and outcome handling (§B3)

### Community 58 - "Global Constraints"
Cohesion: 0.22
Nodes (8): Global Constraints, Manual Verification (required before this ships, per the spec's Verification Caveat), MQTT Reconnection Reliability Fix Implementation Plan, Task 1: Transport-connected flag guards `connect()` against a live client, Task 2: Generic bounded-retry helper, Task 3: Extract `handleTransportDisconnected`, set `RECONNECTING` not `DISCONNECTED`, Task 4: Bounded subscribe-retry replaces the buggy re-`connect()` path, Task 5: Timeout guard on `connect()`'s connect attempt

### Community 59 - "HomeTile"
Cohesion: 0.12
Nodes (4): StateFlow, String, SettingsViewModel, SettingsViewModelTest

### Community 119 - "Global Constraints"
Cohesion: 0.18
Nodes (10): Global Constraints, Job Card Lookup as Landing Screen — Implementation Plan, Task 1: `MixingViewModel` gains `pauseScanning()`, `session`, and `logout()`, Task 2: `AppScaffold` gains an RFID Pallet Lookup top-bar action, Task 3: `JobLookupScreen` becomes the landing screen (session, logout, settings, RFID button, saveable input), Task 4: `IngredientScanScreen` gets the RFID button and saveable local state, Task 5: `HopperScanScreen` gets the RFID button, Task 6: `PreMixCompleteScreen` gets the RFID button and saveable confirmation state (+2 more)

### Community 120 - "SettingsViewModel.kt"
Cohesion: 0.36
Nodes (8): ApplyState, Failure, Idle, Locked, PinState, Success, Testing, Unlocked

### Community 121 - "Design"
Cohesion: 0.20
Nodes (9): App Redesign Phase 1: Job Card Lookup as Landing Screen — Design, Design, Job Lookup top-bar parity (operator name, Logout, Settings), Navigation graph (`app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`, `NavRoutes.kt`), Out of scope, Problem, Returning to the exact prior state, RFID Pallet Lookup as a top-bar action (+1 more)

### Community 122 - "LoginViewModelTest"
Cohesion: 0.52
Nodes (6): Accepted, IngredientScanOutcome, NeedsApprovalForWaiver, NeedsManagerApproval, NeedsRecovery, Rejected

### Community 123 - "MQTT Schema 3.0 — Hopper Board & Machine Cycles Design"
Cohesion: 0.12
Nodes (16): Adding hoppers later is the same screen, unchanged, Design decisions, Destination choice: Hopper now, Extruder/Rajoo disabled, Finish names the cycle, not the machine, Force-close is privileged, and follows SP2's rule, Inherited from SP2's final review — must be handled here or in SP3, Messages, MQTT Schema 3.0 — Hopper Board & Machine Cycles Design (+8 more)

### Community 124 - "SettingsRepository"
Cohesion: 0.33
Nodes (3): Keys, Flow, SettingsRepository

### Community 125 - "MQTT Schema 3.0 — Collection & Ingredients Design"
Cohesion: 0.12
Nodes (15): 1. `lineNumber` is the line identity — not `materialCode`, 2. `null` and `0.0` are different facts on bag fields, Bag units: full-bag equivalents, Context, Inherited defects that land here, MQTT Schema 3.0 — Collection & Ingredients Design, Open questions for the Station 2 developer, Over-collection tolerance is Station 2's number, never ours (+7 more)

### Community 126 - "BomLine"
Cohesion: 0.12
Nodes (4): Authenticate manager credentials and check the APPROVER's allowedActions., None means bulk material (no bag size)., Sum of remaining quantity across usable Holding pallets of this product., World

### Community 127 - "main"
Cohesion: 0.13
Nodes (14): check(), collect_all(), DirectHandheld, _FakeClient, _FakeMsg, _FakePublishResult, Handheld, load_collection() (+6 more)

### Community 128 - "Sequencing"
Cohesion: 0.14
Nodes (13): Definition of Done, Global Constraints, Handoff to sub-project 4, MQTT Schema 3.0 Collection & Ingredients Implementation Plan, Open questions for the Station 2 developer, Sequencing, Task 1: Unify the BOM line shape and add lineNumber, Task 2: Map the full bom_loaded shape (+5 more)

### Community 129 - "Request to Station 2: the timestamp acceptance window"
Cohesion: 0.17
Nodes (11): Also outstanding, and now load-bearing, Context you may want: this already cost us a design decision, If Option A: why 120 seconds, Option A — implement it (our recommendation), Option B — remove it from the contract, Our honest assessment: it may not be worth much, Request to Station 2: the timestamp acceptance window, Summary (+3 more)

### Community 130 - "build_response"
Cohesion: 0.15
Nodes (12): 1. Architecture and scope, 2. Simulator v4 rework, 3. App changes, 4. Error handling and testing, Backend survey facts this design leans on (verified 2026-07-20), Decisions (user-adjudicated 2026-07-20 — do not re-litigate), Design, MQTT Schema 4.0 — Foundation (SP4a) Design (+4 more)

### Community 131 - "common.py"
Cohesion: 0.22
Nodes (19): collection_is_complete(), collection_progress(), collection_summary(), handheld_lines(), ingredients_payload(), line_payload(), r3(), Shared helpers for message-family handlers. (+11 more)

### Community 132 - "SimLogger"
Cohesion: 0.09
Nodes (11): main(), Station 2 backend simulator — answers the Android handheld's MQTT v3 contract tr, Wire-log payloads with credentials masked. The workflow still receives the     o, _redacted(), Simulator, Logging subsystem for the Station 2 backend simulator.  Four channels per run, a, Deep-copy obj with credential values replaced but their presence preserved., payload: dict, str, or bytes. Logged in full (redacted). (+3 more)

### Community 134 - "Rejection"
Cohesion: 0.15
Nodes (14): approve(), Common JSON envelope handling for contract v4.0: the contract's validation order, Step 5 for privileged actions. Returns approver fields for the response.     Rai, Raised by handlers to short-circuit into a rejected response., Raised when a stored response should be re-published as-is., Run validation steps 1-4. Returns (req_dict, session_or_None).     Raises Reject, Rejection, Replay (+6 more)

### Community 135 - "MixingViewModel.kt"
Cohesion: 0.24
Nodes (8): BadgeLoginPayload, CredentialsLoginPayload, AuthUseCase, Badge, Credentials, String, LoginMethod, message()

### Community 136 - "AuthUseCase"
Cohesion: 0.18
Nodes (5): HomeViewModel, Flow, StateFlow, Unit, HomeViewModelTest

### Community 137 - "Simulator"
Cohesion: 0.33
Nodes (3): Int, String, MqttSessionExpiryTest

### Community 138 - "HomeViewModel"
Cohesion: 0.40
Nodes (5): NavHostController, StateFlow, SessionWatcher(), SessionWatcherViewModel, ViewModel

### Community 139 - "Architecture"
Cohesion: 0.17
Nodes (11): Architecture, Business rules of note, Decisions (user-confirmed), Error handling, Logging (simlog.py) — the second source of truth, MQTT surface, Out of scope, Self-test (selftest.py) (+3 more)

### Community 140 - "state.py"
Cohesion: 0.38
Nodes (4): lookup(), _pallet_fields(), pallet_lookup_requested -> pallet_lookup_result holding_recovery_requested -> ho, recovery()

### Community 141 - "LoginViewModel"
Cohesion: 0.27
Nodes (5): Flow, Job, StateFlow, String, LoginViewModel

### Community 143 - "jobcards.py"
Cohesion: 0.17
Nodes (16): build_response(), Assemble the full response envelope around handler-provided fields., unit_for_uom(), active_list(), bom_loaded_response(), load(), open_sap_list(), active_job_cards_requested   -> active_job_cards_list open_sap_job_cards_request (+8 more)

### Community 146 - "MqttRepository"
Cohesion: 0.29
Nodes (5): Boolean, Long, StateFlow, MqttConnectionState, MqttRepository

### Community 147 - "Station 2 Backend Simulator"
Cohesion: 0.25
Nodes (7): Logs (per run: `logs/<UTC-timestamp>/`), Options, Seed world, Self-test, Setup, Station 2 Backend Simulator, What it deliberately does not do

### Community 148 - "LoginViewModel.kt"
Cohesion: 0.60
Nodes (5): Error, Idle, LoggedIn, LoggingIn, LoginUiState

### Community 150 - ".request"
Cohesion: 0.40
Nodes (4): Any, Class, String, T

### Community 151 - "ConnectionStatus"
Cohesion: 0.50
Nodes (4): ConnectionStatus, Boolean, Long, resolveConnectionStatus()

## Knowledge Gaps
- **445 isolated node(s):** `FailureKind`, `MqttSchema`, `EmptyPayload`, `ResponseEnvelope`, `Keys` (+440 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **70 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Rejection` connect `Rejection` to `MixingViewModel.kt`, `common.py`, `SimLogger`, `state.py`, `jobcards.py`, `Dashboard Use Case & Tests`, `BomLine`?**
  _High betweenness centrality (0.079) - this node is a cross-community bridge._
- **Why does `MqttRepositoryImpl` connect `MQTT Message Envelope & Repository Impl` to `Room DAO Tests`, `Offline Queue Repository & RFID Scan Bus`, `Operator Session & App Entry`, `Simulator`, `MQTT Client Factory & Reconnection Tests`, `MqttRepository`?**
  _High betweenness centrality (0.079) - this node is a cross-community bridge._
- **Why does `MqttRepository` connect `MqttRepository` to `Room DAO Tests`, `Pre-Mix Hopper Domain Models`, `MQTT Message Envelope & Repository Impl`, `BOM Line Response & Lookup Tests`, `Typed MQTT Result & Repository Contract`, `Operator Login & Auth Use Case`, `AuthUseCase`, `Dashboard & RFID Recovery ViewModels`, `App Settings Defaults & Tests`, `LoginViewModelTest`, `.request`, `HomeTile`?**
  _High betweenness centrality (0.077) - this node is a cross-community bridge._
- **Are the 9 inferred relationships involving `OperatorSessionHolder` (e.g. with `.setup()` and `.setup()`) actually correct?**
  _`OperatorSessionHolder` has 9 INFERRED edges - model-reasoned connections that need verification._
- **What connects `FailureKind`, `MqttSchema`, `EmptyPayload` to the rest of the system?**
  _485 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Room DAO Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.10333333333333333 - nodes in this community are weakly interconnected._
- **Should `Offline Queue Repository & RFID Scan Bus` be split into smaller, more focused modules?**
  _Cohesion score 0.08408408408408409 - nodes in this community are weakly interconnected._