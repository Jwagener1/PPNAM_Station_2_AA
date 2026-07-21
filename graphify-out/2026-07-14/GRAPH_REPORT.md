# Graph Report - PPNAM_Station_2_AA  (2026-07-08)

## Corpus Check
- 106 files · ~86,148 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1029 nodes · 1326 edges · 119 communities (55 shown, 64 thin omitted)
- Extraction: 91% EXTRACTED · 9% INFERRED · 0% AMBIGUOUS · INFERRED: 120 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `2e533051`
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
- Offline Ingredient Validation Design
- Dashboard Screen Tabs
- Settings ViewModel Actions
- Active Job Cards List
- BOM Line Response & Lookup Tests
- Navigation Routes
- UI Modernisation Design Docs
- MQTT Reconnection Fix Docs
- Settings Persistence Repository
- App / Hilt Bootstrap
- Offline Queue Worker
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
- .onCreate
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

## God Nodes (most connected - your core abstractions)
1. `MixingViewModel` - 44 edges
2. `MixingUseCaseTest` - 44 edges
3. `MixingViewModelTest` - 31 edges
4. `MqttRepositoryImpl` - 30 edges
5. `AppSettings` - 25 edges
6. `MqttRepository` - 22 edges
7. `MqttRepositoryImplTest` - 20 edges
8. `AppScaffold()` - 17 edges
9. `LoginViewModel` - 17 edges
10. `RajooViewModel` - 16 edges

## Surprising Connections (you probably didn't know these)
- `AppNavGraph()` --calls--> `DashboardScreen()`  [INFERRED]
  app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt → app/src/main/java/com/ppnam/station2aa/ui/dashboard/DashboardScreen.kt
- `AppNavGraph()` --calls--> `HomeScreen()`  [INFERRED]
  app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt → app/src/main/java/com/ppnam/station2aa/ui/home/HomeScreen.kt
- `AppNavGraph()` --calls--> `LoginScreen()`  [INFERRED]
  app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt → app/src/main/java/com/ppnam/station2aa/ui/login/LoginScreen.kt
- `AppNavGraph()` --calls--> `PreMixCompleteScreen()`  [INFERRED]
  app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt → app/src/main/java/com/ppnam/station2aa/ui/mixing/PreMixCompleteScreen.kt
- `AppNavGraph()` --calls--> `PalletAllocScreen()`  [INFERRED]
  app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt → app/src/main/java/com/ppnam/station2aa/ui/rajoo/PalletAllocScreen.kt

## Import Cycles
- None detected.

## Communities (119 total, 64 thin omitted)

### Community 0 - "Room DAO Tests"
Cohesion: 0.05
Nodes (15): BomCacheDaoTest, OfflineQueueDaoTest, AppDatabase, BomCacheDao, String, BomCacheEntity, Flow, Int (+7 more)

### Community 1 - "Offline Queue Repository & RFID Scan Bus"
Cohesion: 0.10
Nodes (11): Flow, Int, OfflineQueueRepository, Barcode, SharedFlow, RfidTag, ScanEvent, ScanEventBus (+3 more)

### Community 2 - "Operator Session & App Entry"
Cohesion: 0.10
Nodes (11): StateFlow, String, OperatorSession, OperatorSessionHolder, HomeViewModel, Flow, Int, StateFlow (+3 more)

### Community 3 - "Pre-Mix Hopper Domain Models"
Cohesion: 0.09
Nodes (3): String, MixingViewModelTest, MutableSharedFlow

### Community 4 - "MQTT Message Envelope & Repository Impl"
Cohesion: 0.11
Nodes (20): Error, MqttRequest, MqttResponseMessage, MqttResult, Queued, Success, Boolean, Class (+12 more)

### Community 5 - "Mixing ViewModel State Machine"
Cohesion: 0.13
Nodes (13): ProductionOrder, Boolean, Double, Flow, Int, Job, List, SharedFlow (+5 more)

### Community 6 - "Typed MQTT Result & Repository Contract"
Cohesion: 0.07
Nodes (22): Disconnected, Error, T, MqttTypedResult, Queued, Success, HopperAvailability, HopperStatus (+14 more)

### Community 7 - "Operator Login & Auth Use Case"
Cohesion: 0.09
Nodes (12): LoginTagScannedRequest, OperatorContextResponse, ReaderLoginRequest, ReaderLogoutRequest, AuthUseCase, Badge, Credentials, Result (+4 more)

### Community 8 - "Job Resume & Lookup Flow"
Cohesion: 0.11
Nodes (18): File Map, Global Constraints, PPNAM Station 2 Android App — Implementation Plan, Self-Review Checklist, Task 10: MixingUseCase & Job Lookup Screen, Task 11: Remaining Mixing Screens (IngredientScan → MixerCode → PreMixComplete), Task 12: Rajoo Flow, Task 13: RFID Recovery (+10 more)

### Community 9 - "Dashboard & RFID Recovery ViewModels"
Cohesion: 0.10
Nodes (16): DashboardUiState, DashboardViewModel, Int, StateFlow, String, Error, Idle, Int (+8 more)

### Community 11 - "MQTT Client Factory & Reconnection Tests"
Cohesion: 0.10
Nodes (3): Mqtt5AsyncClient, MqttClientFactory, MqttRepositoryImplTest

### Community 12 - "Mixing Screen Flow & Navigation"
Cohesion: 0.15
Nodes (8): AppNavGraph(), HopperScanScreen(), String, IngredientScanScreen(), String, JobLookupScreen(), MachineSelectScreen(), NavHostController

### Community 13 - "Rajoo Allocation ViewModel"
Cohesion: 0.18
Nodes (12): AllocationSuccess, Error, Idle, Flow, Int, Job, StateFlow, String (+4 more)

### Community 14 - "Job Card Lifecycle Planning Docs"
Cohesion: 0.10
Nodes (19): §6 — Contract Doc Sync (already applied), App, App, B1 — Active Job List, B2 — Per-Line Allocation Status, B3 — Cancel With Role-Gated Approval, Backend, Backend (+11 more)

### Community 16 - "Rajoo Use Case & Tests"
Cohesion: 0.18
Nodes (6): AllocationRecord, List, Result, String, RajooUseCase, RajooUseCaseTest

### Community 17 - "App Settings Defaults & Tests"
Cohesion: 0.05
Nodes (21): Keys, Flow, SettingsRepository, AppSettings, Result, Unit, ApplyState, Failure (+13 more)

### Community 18 - "Final MQTT Bugfix Round"
Cohesion: 0.18
Nodes (11): Error, Idle, Flow, Int, Job, StateFlow, String, LoggedIn (+3 more)

### Community 19 - "Shared UI Scaffold & Screens"
Cohesion: 0.18
Nodes (10): AppScaffold(), Int, String, Unit, LoginScreen(), Boolean, Int, String (+2 more)

### Community 21 - "Dashboard Use Case & Tests"
Cohesion: 0.24
Nodes (4): DashboardUseCase, Result, String, DashboardUseCaseTest

### Community 22 - "Shared Scan UI Components"
Cohesion: 0.18
Nodes (8): String, LabelValueRow(), Modifier, String, ScanPromptCard(), String, PalletAllocScreen(), RfidRecoveryScreen()

### Community 23 - "Login & Session Design Docs"
Cohesion: 0.08
Nodes (24): 1.1 Topics — `MqttTopics` rewritten, 1.2 Device identity — new `AppSettings.deviceId`, 1.3 Envelope — typed per-message classes, no generic wrapper, 1.4 `MqttRepository` — new typed send path, 1.5 Login is never offline-queued, 1. MQTT Layer, 2.1 New `OperatorSession`, 2.2 New `OperatorSessionHolder` (Hilt `@Singleton`, `data/session/`) (+16 more)

### Community 24 - "Production Order & BOM Line Model"
Cohesion: 0.21
Nodes (3): BomLine, Boolean, BomLineTest

### Community 25 - "Settings ViewModel Tests"
Cohesion: 0.12
Nodes (15): Deleted files, File Map, Global Constraints, Manual Test Checklist, Modified files, MQTT Pre-Mix & Hopper Workflow Implementation Plan, New files, Task 1: Domain Models (+7 more)

### Community 26 - "Settings Feature Design Docs"
Cohesion: 0.08
Nodes (24): Access & Entry, Apply behaviour, Apply state display (below the button), Configuration zone, Data Layer, Data Model, `data/mqtt/MqttClientFactory.kt`, `data/settings/SettingsRepository.kt` (+16 more)

### Community 27 - "Mixing Use Case Core Actions"
Cohesion: 0.19
Nodes (10): HoldingRecoveryRequest, IngredientScannedRequest, JobCardSubmittedRequest, PreMixCancelledRequest, HopperCheckResponse, Double, Result, String (+2 more)

### Community 28 - "Settings Screen UI"
Cohesion: 0.36
Nodes (9): ConfigSection(), Boolean, String, SectionLabel(), SettingsScreen(), SettingsTextField(), SettingsToggleRow(), KeyboardType (+1 more)

### Community 29 - "Settings PIN State Machine"
Cohesion: 0.13
Nodes (14): Final check, Global Constraints, MQTT Contract Foundation & Operator Login Implementation Plan, Task 10: Operator identity + logout (`AppScaffold`, `HomeViewModel`, `HomeScreen`), Task 11: `SettingsScreen` — Device ID field, Task 1: `AppSettings.deviceId` + persistence, Task 2: `MqttTopics` — contract topic functions, Task 3: Contract envelope DTOs (+6 more)

### Community 30 - "Android App Architecture Design Docs"
Cohesion: 0.08
Nodes (23): 10. Dependencies, 11. Open Items, 1. Purpose & Scope, 2.1 Pattern, 2.2 Package Structure, 2. Architecture, 3. Screens & Navigation, 4.1 Pattern (+15 more)

### Community 31 - "Pre-Mix Hopper Design Docs"
Cohesion: 0.07
Nodes (29): 1.1 Updated and new action strings, 1.2 New broadcast subscription — `station2/hopper/status`, 1.3 MqttRepository interface + MqttRepositoryImpl changes, 1. MQTT Layer, 2.1 Updated `BomLine`, 2.2 New `IngredientValidationResult`, 2.3 New `HopperStatus`, 2.4 Updated `ScannedIngredient` (+21 more)

### Community 32 - "Offline Ingredient Validation Design"
Cohesion: 0.16
Nodes (5): BomProgressLineResponse, HoldingRecoveryResultResponse, IngredientScanResultResponse, ManagerApprovalRequest, ManagerApprovalResultResponse

### Community 33 - "Dashboard Screen Tabs"
Cohesion: 0.62
Nodes (6): DashboardScreen(), JsonTab(), Boolean, String, PalletTab(), PlaceholderTab()

### Community 34 - "Settings ViewModel Actions"
Cohesion: 0.25
Nodes (13): Cancelling, CancelOutcome, Confirmed, EnteringBagDetails, Error, Failed, HopperUnavailable, Idle (+5 more)

### Community 35 - "Active Job Cards List"
Cohesion: 0.32
Nodes (4): ActiveJobCardsListResponse, ActiveJobCardsRequest, ActiveJobCardSummary, List

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

### Community 42 - "Offline Queue Worker"
Cohesion: 0.40
Nodes (3): Result, OfflineQueueWorker, CoroutineWorker

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
Cohesion: 0.29
Nodes (7): HomeScreen(), HomeTile(), Modifier, String, Color, Dp, ImageVector

### Community 60 - ".onCreate"
Cohesion: 0.29
Nodes (4): MainActivity, PPNAMStation2AATheme(), Bundle, ComponentActivity

### Community 61 - "IngredientScanOutcome"
Cohesion: 0.60
Nodes (5): Accepted, IngredientScanOutcome, NeedsManagerApproval, NeedsRecovery, Rejected

## Knowledge Gaps
- **284 isolated node(s):** `MqttResponseMessage`, `Keys`, `HopperAvailability`, `HopperCheckResponse`, `MixingNavDestination` (+279 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **64 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MqttRepository` connect `Typed MQTT Result & Repository Contract` to `Room DAO Tests`, `Offline Queue Repository & RFID Scan Bus`, `Operator Session & App Entry`, `Pre-Mix Hopper Domain Models`, `MQTT Message Envelope & Repository Impl`, `Operator Login & Auth Use Case`, `Job Cancel & Exception Approval Tests`, `Rajoo Use Case & Tests`, `App Settings Defaults & Tests`, `Dashboard Use Case & Tests`?**
  _High betweenness centrality (0.150) - this node is a cross-community bridge._
- **Why does `MqttConnectionState` connect `Typed MQTT Result & Repository Contract` to `Operator Session & App Entry`, `MQTT Message Envelope & Repository Impl`, `Mixing ViewModel State Machine`, `Dashboard & RFID Recovery ViewModels`, `Rajoo Allocation ViewModel`, `App Settings Defaults & Tests`, `Final MQTT Bugfix Round`, `Shared UI Scaffold & Screens`?**
  _High betweenness centrality (0.102) - this node is a cross-community bridge._
- **Why does `MixingViewModel` connect `Mixing ViewModel State Machine` to `Settings ViewModel Actions`, `Active Job Cards List`, `Pre-Mix Hopper Domain Models`, `Typed MQTT Result & Repository Contract`, `Dashboard & RFID Recovery ViewModels`, `Mixing Screen Flow & Navigation`, `Shared UI Scaffold & Screens`?**
  _High betweenness centrality (0.101) - this node is a cross-community bridge._
- **Are the 16 inferred relationships involving `AppSettings` (e.g. with `.`build returns non-null client with default WSS settings`()` and `.`build returns non-null client with TCP plain settings`()`) actually correct?**
  _`AppSettings` has 16 INFERRED edges - model-reasoned connections that need verification._
- **What connects `MqttResponseMessage`, `Keys`, `HopperAvailability` to the rest of the system?**
  _290 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Room DAO Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.054901960784313725 - nodes in this community are weakly interconnected._
- **Should `Offline Queue Repository & RFID Scan Bus` be split into smaller, more focused modules?**
  _Cohesion score 0.09666666666666666 - nodes in this community are weakly interconnected._