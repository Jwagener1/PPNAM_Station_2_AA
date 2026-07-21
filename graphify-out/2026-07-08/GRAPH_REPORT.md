# Graph Report - .  (2026-07-08)

## Corpus Check
- 140 files · ~92,869 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 780 nodes · 1261 edges · 54 communities (43 shown, 11 thin omitted)
- Extraction: 90% EXTRACTED · 10% INFERRED · 0% AMBIGUOUS · INFERRED: 121 edges (avg confidence: 0.81)
- Token cost: 562,148 input · 0 output

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

## God Nodes (most connected - your core abstractions)
1. `MixingViewModel` - 42 edges
2. `MixingUseCaseTest` - 35 edges
3. `MqttRepositoryImpl` - 30 edges
4. `MixingViewModelTest` - 26 edges
5. `AppSettings` - 25 edges
6. `MqttRepository` - 22 edges
7. `MqttRepositoryImplTest` - 20 edges
8. `AppScaffold()` - 18 edges
9. `AppNavGraph()` - 17 edges
10. `LoginViewModel` - 17 edges

## Surprising Connections (you probably didn't know these)
- `Task 8 Brief — Dashboard Screen` --references--> `LabelValueRow()`  [AMBIGUOUS]
  .superpowers/sdd/task-8-brief.md → app/src/main/java/com/ppnam/station2aa/ui/components/LabelValueRow.kt
- `MixingViewModel` --shares_data_with--> `IngredientValidationResult (sealed Valid/Invalid result type)`  [INFERRED]
  app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt → .superpowers/sdd/task-3-brief.md
- `MixingUseCase` --shares_data_with--> `IngredientValidationResult (sealed Valid/Invalid result type)`  [INFERRED]
  app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt → .superpowers/sdd/task-3-brief.md
- `HomeScreen Revised to Tile Grid with AppScaffold (removes ConnectionStatusBar)` --conceptually_related_to--> `HomeScreen()`  [INFERRED]
  .superpowers/sdd/task-4-report.md → app/src/main/java/com/ppnam/station2aa/ui/home/HomeScreen.kt
- `Premix Task 4 Brief: Pass preMixId from Active-Job Tap Handler` --references--> `ActiveJobCardSummary`  [EXTRACTED]
  .superpowers/sdd/premix-task-4-brief.md → app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Job Resume by preMixId — Sequential Task Chain (contract doc through UI tap handler)** — superpowers_sdd_premix_task_1_brief, superpowers_sdd_premix_task_2_brief, superpowers_sdd_premix_task_3_brief, superpowers_sdd_premix_task_4_brief, concept_job_resume_by_premixid_plan [EXTRACTED 1.00]
- **preMixId Request Plumbing Implemented Across 3 Tasks, Then Reverted** — superpowers_sdd_premix_task_2_report, superpowers_sdd_premix_task_3_report, superpowers_sdd_premix_task_4_report, concept_premixid_resume_feature [EXTRACTED 1.00]
- **Mixing / Rajoo / RFID Feature Screens Integrated via AppNavGraph** — app_src_main_java_com_ppnam_station2aa_ui_mixing_joblookupscreen, app_src_main_java_com_ppnam_station2aa_ui_rajoo_machineselectscreen, app_src_main_java_com_ppnam_station2aa_ui_rfid_rfidrecoveryscreen, app_src_main_java_com_ppnam_station2aa_navigation_appnavgraph [INFERRED 0.85]
- **Ingredient Exception Approval Flow (UseCase to ViewModel to Screen)** — app_src_main_java_com_ppnam_station2aa_domain_usecase_mixingusecase_mixingusecase, app_src_main_java_com_ppnam_station2aa_ui_mixing_mixingviewmodel_mixingviewmodel, app_src_main_java_com_ppnam_station2aa_ui_mixing_ingredientscanscreen_ingredientscanscreen, superpowers_sdd_task_4_brief_supervisor_exception_approval_flow [INFERRED 0.85]
- **Hopper Allocation Flow (ViewModel, HopperScanScreen, PreMixCompleteScreen, NavRoutes)** — app_src_main_java_com_ppnam_station2aa_ui_mixing_mixingviewmodel_mixingviewmodel, app_src_main_java_com_ppnam_station2aa_ui_mixing_hopperscanscreen_hopperscanscreen, app_src_main_java_com_ppnam_station2aa_ui_mixing_premixcompletescreen_premixcompletescreen, app_src_main_java_com_ppnam_station2aa_navigation_navroutes_navroutes [INFERRED 0.85]
- **SDD Task Brief/Report Pairing Pattern (Tasks 3-9)** — superpowers_sdd_task_3_brief, superpowers_sdd_task_3_report, superpowers_sdd_task_4_brief, superpowers_sdd_task_4_report, superpowers_sdd_task_5_brief, superpowers_sdd_task_5_report [INFERRED 0.75]
- **Mixing Pre-Mix Workflow Evolution Across Plans** — docs_superpowers_plans_2026_06_24_android_app_mixingusecase, docs_superpowers_plans_2026_06_24_android_app_mixingviewmodel, docs_superpowers_plans_2026_06_24_android_app_joblookupscreen, docs_superpowers_plans_2026_06_24_android_app_ingredientscanscreen, docs_superpowers_plans_2026_06_30_mqtt_premix_hopper_hopperscanscreen, docs_superpowers_plans_2026_06_24_android_app_premixcompletescreen [INFERRED 0.85]
- **Operator Authentication & Session Flow** — docs_superpowers_plans_2026_07_01_mqtt_contract_foundation_login_authusecase, docs_superpowers_plans_2026_07_01_mqtt_contract_foundation_login_operatorsessionholder, docs_superpowers_plans_2026_07_01_mqtt_contract_foundation_login_operatorsession, docs_superpowers_specs_2026_07_01_mqtt_contract_foundation_login_design_loginviewmodel, docs_superpowers_specs_2026_07_01_mqtt_contract_foundation_login_design_loginscreen, docs_superpowers_plans_2026_07_01_mqtt_contract_foundation_login_sendtyped [EXTRACTED 1.00]
- **Job Card Lifecycle Feature (B1 Active List / B2 Allocation Status / B3 Role-Gated Cancel)** — docs_superpowers_plans_2026_07_03_job_card_android_activejobcardsummary, docs_superpowers_plans_2026_06_24_android_app_productionorder_bomline, docs_superpowers_plans_2026_07_03_job_card_android_canceljob, docs_superpowers_plans_2026_07_03_job_card_android_canceloutcome, docs_superpowers_plans_2026_07_01_mqtt_contract_foundation_login_operatorsessionholder [EXTRACTED 1.00]

## Communities (54 total, 11 thin omitted)

### Community 0 - "Room DAO Tests"
Cohesion: 0.05
Nodes (16): BomCacheDaoTest, OfflineQueueDaoTest, AppDatabase, BomCacheDao, String, BomCacheEntity, Flow, Int (+8 more)

### Community 1 - "Offline Queue Repository & RFID Scan Bus"
Cohesion: 0.05
Nodes (27): Flow, Int, OfflineQueueRepository, DataWedgeReceiver, Context, Barcode, SharedFlow, RfidTag (+19 more)

### Community 2 - "Operator Session & App Entry"
Cohesion: 0.06
Nodes (26): StateFlow, String, OperatorSession, OperatorSessionHolder, MainActivity, ConnectionStatusBar (created Task 9, later deleted), HomeScreen(), HomeTile() (+18 more)

### Community 3 - "Pre-Mix Hopper Domain Models"
Cohesion: 0.06
Nodes (18): MqttRepositoryImpl.handleHopperStatus, MqttTopics.hopperStatus() topic helper, HopperAvailability, HopperStatus, IngredientValidationResult, Invalid, Valid, PreMix (+10 more)

### Community 4 - "MQTT Message Envelope & Repository Impl"
Cohesion: 0.11
Nodes (20): Error, MqttRequest, MqttResponseMessage, MqttResult, Queued, Success, Boolean, Class (+12 more)

### Community 5 - "Mixing ViewModel State Machine"
Cohesion: 0.11
Nodes (24): Cancelling, CancelOutcome, Confirmed, Error, Failed, HopperUnavailable, Idle, IngredientInvalid (+16 more)

### Community 6 - "Typed MQTT Result & Repository Contract"
Cohesion: 0.08
Nodes (20): Disconnected, Error, T, MqttTypedResult, Queued, Success, Pallet, Boolean (+12 more)

### Community 7 - "Operator Login & Auth Use Case"
Cohesion: 0.09
Nodes (12): LoginTagScannedRequest, OperatorContextResponse, ReaderLoginRequest, ReaderLogoutRequest, AuthUseCase, Badge, Credentials, Result (+4 more)

### Community 8 - "Job Resume & Lookup Flow"
Cohesion: 0.15
Nodes (23): JobCardSubmittedRequest, MixingUseCase.lookupJob, JobLookupScreen(), MixerCodeScreen, MixingViewModel.lookupJob, Plan: Job Resume by preMixId (2026-07-06), Plan: MQTT Pre-Mix Hopper Workflow (2026-06-30), preMixId job-resume plumbing — implemented then reverted (contract mismatch) (+15 more)

### Community 9 - "Dashboard & RFID Recovery ViewModels"
Cohesion: 0.10
Nodes (16): DashboardUiState, DashboardViewModel, Int, StateFlow, String, Error, Idle, Int (+8 more)

### Community 11 - "MQTT Client Factory & Reconnection Tests"
Cohesion: 0.10
Nodes (3): Mqtt5AsyncClient, MqttClientFactory, MqttRepositoryImplTest

### Community 12 - "Mixing Screen Flow & Navigation"
Cohesion: 0.19
Nodes (17): AppNavGraph(), HopperScanScreen(), String, IngredientScanScreen(), String, MixerCodeScreen (deleted in Task 5), NavHostController, Task 4 Brief — MixingViewModel New States and Exception Flow (+9 more)

### Community 13 - "Rajoo Allocation ViewModel"
Cohesion: 0.18
Nodes (12): AllocationSuccess, Error, Idle, Flow, Int, Job, StateFlow, String (+4 more)

### Community 14 - "Job Card Lifecycle Planning Docs"
Cohesion: 0.18
Nodes (17): IngredientScanScreen, JobLookupScreen, MixingViewModel, ProductionOrder / BomLine domain model, OperatorSession data class, Job Card Lifecycle Android Implementation Plan, ActiveJobCardSummary / ActiveJobCardsListResponse, MixingUseCase.cancelJob / PreMixCancelResultResponse (+9 more)

### Community 16 - "Rajoo Use Case & Tests"
Cohesion: 0.19
Nodes (6): AllocationRecord, List, Result, String, RajooUseCase, RajooUseCaseTest

### Community 18 - "Final MQTT Bugfix Round"
Cohesion: 0.19
Nodes (10): MixingUseCase.completePremix, MixingUseCase.validateIngredient / validateIngredientOffline, MixingViewModel.startListeningForBarcode, MachineSelectScreen(), Final Fix Round: 6 bugfixes (MQTT action rename, BOM cache removal, Queued-state handling, reactive queue drain, Gson serialization), Final Fix Report, Task 12 Brief: Rajoo Flow, Task 12 Report: Rajoo Allocation Flow (+2 more)

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
Cohesion: 0.21
Nodes (12): MqttTopics, SettingsRepository (DataStore-backed), MQTT Contract Foundation & Operator Login Implementation Plan, AuthUseCase, OperatorSessionHolder, MqttRepository.sendTyped / MqttTypedResult, MQTT Contract Foundation & Operator Login Design Spec, Login Mandatory at Startup (LoginScreen is nav-graph start destination) (+4 more)

### Community 24 - "Production Order & BOM Line Model"
Cohesion: 0.25
Nodes (4): BomLine, Boolean, ProductionOrder, BomLineTest

### Community 26 - "Settings Feature Design Docs"
Cohesion: 0.24
Nodes (11): AppModule (Hilt DI), MqttRepository interface, Settings Screen Implementation Plan, AppSettings data class, MqttClientFactory, SettingsScreen, SettingsViewModel, Settings Screen Design Spec (+3 more)

### Community 27 - "Mixing Use Case Core Actions"
Cohesion: 0.31
Nodes (5): PreMixCancelledRequest, List, Result, String, Unit

### Community 28 - "Settings Screen UI"
Cohesion: 0.36
Nodes (9): ConfigSection(), Boolean, String, SectionLabel(), SettingsScreen(), SettingsTextField(), SettingsToggleRow(), KeyboardType (+1 more)

### Community 29 - "Settings PIN State Machine"
Cohesion: 0.36
Nodes (8): ApplyState, Failure, Idle, Locked, PinState, Success, Testing, Unlocked

### Community 30 - "Android App Architecture Design Docs"
Cohesion: 0.22
Nodes (10): PPNAM Station 2 Android App Implementation Plan, DataWedgeReceiver, OfflineQueueRepository, ScanEventBus, PPNAM Station 2 Android App Design Spec, DataWedge RFID/Barcode Scan Integration via ScanEventBus, Layered MVVM + Clean Architecture Pattern, MQTT Request/Response Correlation-ID Pattern (+2 more)

### Community 31 - "Pre-Mix Hopper Design Docs"
Cohesion: 0.27
Nodes (10): MixerCodeScreen, MixingUseCase, PreMix / ScannedIngredient domain model, MQTT Pre-Mix & Hopper Workflow Implementation Plan, HopperScanScreen, HopperStatus / HopperAvailability, IngredientValidationResult (Valid/Invalid), MQTT Pre-Mix & Hopper Workflow Design Spec (+2 more)

### Community 32 - "Offline Ingredient Validation Design"
Cohesion: 0.25
Nodes (7): ApprovalResponse, HopperCheckResponse, MixingUseCase, Task 3 Brief — MixingUseCase Full Update, IngredientValidationResult (sealed Valid/Invalid result type), Optimistic Offline Ingredient Validation, Task 3 Report — MixingUseCase Full Update

### Community 33 - "Dashboard Screen Tabs"
Cohesion: 0.44
Nodes (8): DashboardScreen(), JsonTab(), Boolean, String, PalletTab(), PlaceholderTab(), Task 8 Brief — Dashboard Screen, Task 8 Report — Dashboard Screen

### Community 34 - "Settings ViewModel Actions"
Cohesion: 0.22
Nodes (4): Int, StateFlow, String, SettingsViewModel

### Community 35 - "Active Job Cards List"
Cohesion: 0.38
Nodes (3): ActiveJobCardsListResponse, ActiveJobCardsRequest, ActiveJobCardSummary

### Community 38 - "UI Modernisation Design Docs"
Cohesion: 0.33
Nodes (7): HomeViewModel, PreMixCompleteScreen, UI Modernisation Implementation Plan, AppScaffold shared composable, LabelValueRow shared composable, UI Modernisation Design Spec, Dark Graphite + Amber Design System

### Community 39 - "MQTT Reconnection Fix Docs"
Cohesion: 0.38
Nodes (7): MqttRepositoryImpl, MQTT Reconnection Reliability Fix Implementation Plan, handleTransportDisconnected, isTransportConnected AtomicBoolean flag, retryBounded generic retry helper, MQTT Reconnection Reliability Fix Design Spec, Dual Reconnection Mechanism Race Causing Permanent DISCONNECTED Hang

### Community 40 - "Settings Persistence Repository"
Cohesion: 0.33
Nodes (3): Keys, Flow, SettingsRepository

### Community 41 - "App / Hilt Bootstrap"
Cohesion: 0.40
Nodes (4): PpnamApplication, Application, Configuration, HiltWorkerFactory

### Community 42 - "Offline Queue Worker"
Cohesion: 0.40
Nodes (3): Result, OfflineQueueWorker, CoroutineWorker

### Community 44 - "Gradle Wrapper Script"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 48 - "Repo Rules & Graphify Workflow"
Cohesion: 0.67
Nodes (3): CLAUDE.md — Repo Rules, External Repo Read-Only Except RFID_MQTT_CONTRACT.md, Graphify Query-First Workflow for Codebase Questions

## Ambiguous Edges - Review These
- `LabelValueRow()` → `Task 8 Brief — Dashboard Screen`  [AMBIGUOUS]
  .superpowers/sdd/task-8-brief.md · relation: references

## Knowledge Gaps
- **34 isolated node(s):** `MqttResponseMessage`, `Keys`, `PreMixStatus`, `ApprovalResponse`, `HopperCheckResponse` (+29 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **11 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `LabelValueRow()` and `Task 8 Brief — Dashboard Screen`?**
  _Edge tagged AMBIGUOUS (relation: references) - confidence is low._
- **Why does `MqttRepository` connect `Typed MQTT Result & Repository Contract` to `Room DAO Tests`, `Offline Queue Repository & RFID Scan Bus`, `Operator Session & App Entry`, `Pre-Mix Hopper Domain Models`, `MQTT Message Envelope & Repository Impl`, `Operator Login & Auth Use Case`, `Job Cancel & Exception Approval Tests`, `MQTT Repository Reconnect Contract`, `Rajoo Use Case & Tests`, `Dashboard Use Case & Tests`, `Settings ViewModel Tests`?**
  _High betweenness centrality (0.223) - this node is a cross-community bridge._
- **Why does `MqttConnectionState` connect `Typed MQTT Result & Repository Contract` to `Offline Queue Repository & RFID Scan Bus`, `Operator Session & App Entry`, `Settings ViewModel Actions`, `MQTT Message Envelope & Repository Impl`, `Mixing ViewModel State Machine`, `Dashboard & RFID Recovery ViewModels`, `Rajoo Allocation ViewModel`, `Shared UI Scaffold & Screens`?**
  _High betweenness centrality (0.173) - this node is a cross-community bridge._
- **Why does `MixingViewModel` connect `Mixing ViewModel State Machine` to `Offline Ingredient Validation Design`, `Offline Queue Repository & RFID Scan Bus`, `Pre-Mix Hopper Domain Models`, `Active Job Cards List`, `Typed MQTT Result & Repository Contract`, `Job Resume & Lookup Flow`, `Dashboard & RFID Recovery ViewModels`, `Mixing Screen Flow & Navigation`, `Shared UI Scaffold & Screens`, `Production Order & BOM Line Model`?**
  _High betweenness centrality (0.151) - this node is a cross-community bridge._
- **Are the 16 inferred relationships involving `AppSettings` (e.g. with `.`build returns non-null client with default WSS settings`()` and `.`build returns non-null client with TCP plain settings`()`) actually correct?**
  _`AppSettings` has 16 INFERRED edges - model-reasoned connections that need verification._
- **What connects `MqttResponseMessage`, `Keys`, `PreMixStatus` to the rest of the system?**
  _41 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Room DAO Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.05297532656023222 - nodes in this community are weakly interconnected._