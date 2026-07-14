# App Redesign Phase 1: Job Card Lookup as Landing Screen — Design

## Problem

The client has changed direction: there will no longer be separate operating "modes" selected from a Home screen. Everything flows from the job card lookup, through collecting the ingredient mix. Concretely, for this first phase:

- After login, the app must go straight into the Job Lookup window — no Home/mode-select screen in between.
- RFID Pallet Lookup (today's "RFID Recovery" screen) stops being a Home-screen tile and becomes a button in the top bar, reachable from anywhere in the job/mixing flow. Closing it must return the operator to exactly the screen and state they were in before opening it.
- Job Lookup itself is unchanged functionally.

This phase does not decide what happens to Rajoo allocation or the Dashboard beyond making room for the new flow — the client will specify their fate in a later change request.

## Design

### Navigation graph (`app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`, `NavRoutes.kt`)

- `NavHost`'s start destination stays `NavRoutes.LOGIN`.
- `LoginScreen`'s `onLoggedIn` changes from navigating to `NavRoutes.HOME` to navigating to `NavRoutes.MIXING` (the existing nested graph, whose start destination is already `JOB_LOOKUP`):
  ```kotlin
  navController.navigate(NavRoutes.MIXING) {
      popUpTo(NavRoutes.LOGIN) { inclusive = true }
  }
  ```
  Operators land directly on Job Lookup after logging in.
- Remove the `composable(NavRoutes.HOME) { ... }`, `composable(NavRoutes.MACHINE_SELECT) { ... }`, `composable(NavRoutes.PALLET_ALLOC) { ... }`, and `composable(NavRoutes.DASHBOARD) { ... }` blocks from `AppNavGraph.kt`. Remove the corresponding route constants from `NavRoutes`.
- `PreMixCompleteScreen`'s `onCompleted` currently does:
  ```kotlin
  navController.navigate(NavRoutes.HOME) { popUpTo(NavRoutes.HOME) { inclusive = true } }
  ```
  This becomes:
  ```kotlin
  navController.navigate(NavRoutes.MIXING) { popUpTo(NavRoutes.MIXING) { inclusive = true } }
  ```
  Completing a pre-mix pops the whole finished flow (including the old `MixingViewModel` instance) and lands back on a fresh Job Lookup, ready for the next job card.
- `JOB_LOOKUP`, `INGREDIENT_SCAN`, `HOPPER_SCAN`, `PREMIX_COMPLETE` routes, their screens, and their MQTT/domain logic are unchanged.

**Files left untouched on disk** (unreachable after this change, revisited in a later phase): `HomeScreen.kt`, `HomeViewModel.kt`, `HomeViewModelTest.kt`, `RajooViewModel.kt`, `MachineSelectScreen.kt`, `PalletAllocScreen.kt`, `DashboardScreen.kt`, `DashboardViewModel.kt`.

### RFID Pallet Lookup as a top-bar action

`AppScaffold` (`app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt`) gains a new optional parameter:

```kotlin
onRfidLookup: (() -> Unit)? = null
```

Rendered as an `IconButton` in the top bar's `actions` (next to the existing Settings icon), shown only when non-null.

`JobLookupScreen`, `IngredientScanScreen`, `HopperScanScreen`, and `PreMixCompleteScreen` all pass `onRfidLookup`, wired in `AppNavGraph.kt` as:

```kotlin
onRfidLookup = {
    viewModel.pauseScanning()
    navController.navigate(NavRoutes.RFID_RECOVERY)
}
```

`RfidRecoveryScreen`'s `onDone` and `onBack` callbacks, wired in `AppNavGraph.kt`, change from navigating to `NavRoutes.HOME` to plain `navController.popBackStack()`. This returns to whichever screen pushed the RFID lookup, wherever it was in the job/mixing flow.

### Returning to the exact prior state

Two mechanisms are needed so "return to the state it was in before" actually holds:

**1. Scanner isolation.** `MixingViewModel` currently starts a single shared `scanJob` coroutine (in `startListeningForPalletScans` / `startListeningForHopperBarcode`) that keeps running in `viewModelScope` even after its screen leaves composition — it is not tied to the screen's lifecycle. If RFID Pallet Lookup is opened while, say, Ingredient Scan is active, both `RfidViewModel` and `MixingViewModel` would receive the same physical scan events from `ScanEventBus`, silently advancing the ingredient flow's state in the background.

  Fix: add a small method to `MixingViewModel`:
  ```kotlin
  fun pauseScanning() {
      scanJob?.cancel()
  }
  ```
  called from each screen's `onRfidLookup` handler before navigating (see above). No explicit "resume" call is needed: Navigation-Compose fully disposes a destination's composition when another destination is pushed on top, and recomposes it fresh when popped back to. Each screen's existing `LaunchedEffect(orderNo) { viewModel.startListeningForPalletScans(orderNo) }` (or the hopper-barcode equivalent) therefore fires again automatically on return, restarting the scan listener.

**2. Local UI state surviving the round trip.** Because the underlying screen's composition is disposed while RFID Pallet Lookup is on top, any state held in plain `remember { mutableStateOf(...) }` is lost — it resets to its initial value when the screen recomposes on return. `rememberSaveable` state, by contrast, is persisted per back-stack entry by Navigation-Compose and correctly restored. The following existing `remember` calls must become `rememberSaveable` so they survive opening/closing the RFID lookup:

  - `JobLookupScreen.kt`: `orderInput`
  - `IngredientScanScreen.kt`: `showCancelDialog`, `showBackConfirmDialog`, `showApprovalDialog`, `managerUsername`, `managerPassword`, `selectedBagFraction`, `bagCountText`, `exceptionUsername`, `exceptionPassword`
  - `PreMixCompleteScreen.kt`: `showConfirmation`

  `HopperScanScreen.kt` has no local `remember` state and needs no change here.

### Job Lookup top-bar parity (operator name, Logout, Settings)

Today, the operator name badge, Logout, and Settings icon are only wired up on `HomeScreen`, via `HomeViewModel` exposing `session: StateFlow<OperatorSession?>`, `logoutEvent`, and `logout()`. With Home removed, Job Lookup becomes the permanent post-login screen, so it needs the same capability — otherwise there is no way to log out or reach Settings once logged in.

- `MixingViewModel` gains the same shape `HomeViewModel` has today:
  ```kotlin
  val session: StateFlow<OperatorSession?> = sessionHolder.session
  private val _logoutEvent = MutableSharedFlow<Unit>()
  val logoutEvent: SharedFlow<Unit> = _logoutEvent.asSharedFlow()
  fun logout() { /* same pattern as HomeViewModel.logout() */ }
  ```
- `JobLookupScreen`'s `AppScaffold` call gains `operatorName`, `operatorRole`, `onLogout`, and `onSettings = { navController.navigate(NavRoutes.SETTINGS) }`, matching what `HomeScreen` passed today.
- `JobLookupScreen` adds the same `LaunchedEffect(Unit) { viewModel.logoutEvent.collect { onLogout() } }` pattern `HomeScreen` uses, with `onLogout` threaded through from `AppNavGraph.kt` the same way it is for `HomeScreen` today (navigate to `LOGIN` with `popUpTo(0)`).
- `IngredientScanScreen`, `HopperScanScreen`, `PreMixCompleteScreen` are unaffected — they don't currently show operator/logout/settings and this phase doesn't add it to them.

### Testing

- `MixingViewModelTest`: add coverage for `pauseScanning()` cancelling an active `scanJob`, and for the new `session`/`logoutEvent`/`logout()` surface (mirroring existing `HomeViewModelTest` cases).
- No changes needed to `MixingUseCaseTest` or MQTT contract/DTO tests — nothing in this phase touches the MQTT layer.
- Manual verification: log in → confirm landing on Job Lookup directly; open RFID Pallet Lookup from each of the four mixing screens and confirm returning lands back on the same screen with prior state (typed order number, open dialogs, in-progress bag entry) intact and scanning resumes; complete a pre-mix and confirm it returns to a fresh Job Lookup; log out and reach Settings from Job Lookup's top bar.

## Out of scope

- Any change to Rajoo (Machine Select → Pallet Allocation) or Dashboard beyond removing their now-unreachable routes from the nav graph. Their screens/ViewModels are left in place, untouched, pending a future change request.
- Any change to `IngredientScanScreen`, `HopperScanScreen`, or `PreMixCompleteScreen`'s functional behavior beyond the `onRfidLookup` button and the `rememberSaveable` fixes listed above.
- Any MQTT contract change — this phase is purely Android-side navigation/UI restructuring.
- Deciding whether a Dialog-based (rather than full-screen `composable`) presentation for RFID Pallet Lookup would be preferable; the full-screen `composable` + `rememberSaveable` approach was chosen because it matches the existing screen's look (its own `AppScaffold`/top bar) and needs no new dependency, whereas a Dialog destination is atypical for a full page and doesn't remove the need to handle scan isolation anyway.
