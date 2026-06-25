# UI Modernisation — Station 2 Design Spec

**Goal:** Replace the boilerplate Material3 purple theme and unstyled screens with a cohesive dark-graphite + amber identity that is visually distinct from Station 1 and polished end-to-end.

**Approach:** Approach C — Theme + AppScaffold + all screens restyled + enhanced data displays (BOM progress bars, machine grid, success/error screens with icons).

**Screens in scope:** HomeScreen, JobLookupScreen, IngredientScanScreen, MixerCodeScreen, PreMixCompleteScreen, PremixConfirmedScreen, MachineSelectScreen, PalletAllocScreen, RfidRecoveryScreen, DashboardScreen.

---

## Global Constraints

- Dark theme only — `dynamicColor = false`, `darkTheme = true` always (factory floor app, no light mode).
- All icons from `material-icons-extended` — no custom vector drawable assets.
- Button height: 56dp minimum (glove-friendly tap targets).
- Card corner radius: 16dp throughout.
- Screen edge padding: 16dp throughout.
- Between-card gap: 12dp.
- Connection status visible on every screen via `AppScaffold` TopBar.
- No changes to business logic, ViewModels, UseCases, or data layer.

---

## 1. Color System

Replace `ui/theme/Color.kt` with:

| Constant | Hex | Role |
|---|---|---|
| `GraphiteBackground` | `#0D0D0D` | Window / screen background |
| `GraphiteSurface` | `#1A1A1A` | Cards, panels, TopBar |
| `GraphiteSurfaceVariant` | `#242424` | Secondary card surfaces |
| `GraphiteBorder` | `#2E2E2E` | Card stroke, dividers |
| `TextPrimary` | `#F5F5F5` | Main text |
| `TextMuted` | `#8A8A8A` | Labels, captions, subtitles |
| `AmberPrimary` | `#F59E0B` | Primary action, active state, progress fill |
| `AmberDark` | `#B45309` | Pressed amber, on-primary text |
| `SuccessGreen` | `#10B981` | Satisfied BOM rows, confirmed screens, Rajoo tile |
| `DangerRed` | `#EF4444` | Error text, offline status, error cards |
| `InfoBlue` | `#3B82F6` | RFID Recovery tile, informational accents |
| `IndigoAccent` | `#6366F1` | Dashboard tile |

**Tile colours (HomeScreen):**

| Destination | Colour constant |
|---|---|
| Mixing | `AmberPrimary` |
| Rajoo Allocation | `SuccessGreen` |
| RFID Recovery | `InfoBlue` |
| Dashboard | `IndigoAccent` |

**Theme wiring (`ui/theme/Theme.kt`):**
- `dynamicColor = false`
- Always use `darkColorScheme`:
  - `primary` → `AmberPrimary`
  - `onPrimary` → `AmberDark`
  - `background` / `onBackground` → `GraphiteBackground` / `TextPrimary`
  - `surface` / `onSurface` → `GraphiteSurface` / `TextPrimary`
  - `surfaceVariant` / `onSurfaceVariant` → `GraphiteSurfaceVariant` / `TextMuted`
  - `error` / `onError` → `DangerRed` / `TextPrimary`
  - `outline` → `GraphiteBorder`

---

## 2. Typography

Replace `ui/theme/Type.kt` with a custom `Typography` object using `Roboto` (system default):

| Token | Size | Weight | Use |
|---|---|---|---|
| `displaySmall` | 28sp | W400 | Success / confirmed screens |
| `headlineMedium` | 24sp | W600 | AppScaffold TopBar titles |
| `headlineSmall` | 20sp | W600 | Card section headers |
| `titleLarge` | 18sp | W500 | Home tile labels |
| `bodyLarge` | 16sp | W400 | Primary data (order no, machine name, pallet data) |
| `bodyMedium` | 14sp | W400 | Secondary data, descriptions, subtitles |
| `labelSmall` | 11sp | W500 | TopBar status label, captions |

---

## 3. AppScaffold Component

**File:** `ui/components/AppScaffold.kt`  
**Replaces:** `ui/components/ConnectionStatusBar.kt` (retired)

```
┌─────────────────────────────────────────┐
│ [←]  Screen Title          [●] Connected │  56dp TopBar
├─────────────────────────────────────────┤
│                                         │
│            content slot                 │
│                                         │
└─────────────────────────────────────────┘
```

**Signature:**
```kotlin
@Composable
fun AppScaffold(
    title: String,
    connectionState: MqttConnectionState,
    pendingCount: Int,
    onBack: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
)
```

**TopBar spec:**
- Background: `GraphiteSurface` with 1dp bottom border in `GraphiteBorder`
- Back arrow (`Icons.AutoMirrored.Filled.ArrowBack`, `TextPrimary` tint): shown only when `onBack != null`
- Title: `headlineMedium`, `TextPrimary`, start-aligned
- Status indicator (trailing, right side):
  - 10dp filled `Canvas` circle + `labelSmall` text in one `Row`
  - CONNECTED → `SuccessGreen` circle + "Connected"
  - RECONNECTING → `AmberPrimary` circle + "Reconnecting"
  - DISCONNECTED, pendingCount == 0 → `DangerRed` circle + "Offline"
  - DISCONNECTED, pendingCount > 0 → `DangerRed` circle + "Offline — N queued"

**All ViewModels** that back screens in scope expose two additional `StateFlow`s injected from `MqttRepository`:
- `connectionState: StateFlow<MqttConnectionState>`
- `pendingCount: StateFlow<Int>` (from `OfflineQueueRepository.pendingCount()`)

`HomeViewModel` already has both. The remaining ViewModels (`MixingViewModel`, `RajooViewModel`, `RfidViewModel`, `DashboardViewModel`) each need `MqttRepository` and `OfflineQueueRepository` injected (already available in DI graph) to expose these two flows.

---

## 4. HomeScreen

**Layout:** 2-column grid, large tiles top row (220dp), small tiles bottom row (110dp).

**Tile spec:**
- `ElevatedCard`, `elevation = 4.dp`, `cornerRadius = 16.dp`, background = tile colour
- White ripple on press
- Large tile (220dp): `Factory`/`Science`/`WifiTethering`/`BarChart` icon (48dp, `Color.White`), title in `titleLarge` bold white below, subtitle in `bodyMedium` white at 70% alpha below that
- Small tile (110dp): icon (32dp, `Color.White`) and title (`titleLarge` bold white) in a centred `Row`, vertically centred

**Large tile subtitles:**
- Mixing → "Pre-Mix Flow"
- Rajoo → "Allocation"

**Icons:**
- Mixing → `Icons.Filled.Science`
- Rajoo → `Icons.Filled.Factory`
- RFID Recovery → `Icons.Filled.WifiTethering`
- Dashboard → `Icons.Filled.BarChart`

**No back arrow** on HomeScreen (`onBack = null`).

---

## 5. Mixing Workflow Screens

### JobLookupScreen
- `AppScaffold(title = "Job Lookup", onBack = onBack)`
- `OutlinedTextField` with amber focus ring (`focusedBorderColor = AmberPrimary`)
- `Button` (amber filled, 56dp tall, `fillMaxWidth`) for "Look Up"; shows `CircularProgressIndicator(color = AmberDark)` inside when loading
- Error message in `DangerRed` below button

### IngredientScanScreen
- `AppScaffold(title = "Scan Ingredients", onBack = onBack)`
- Summary header card (`GraphiteSurfaceVariant`): "Order {orderNo}" + "X of Y satisfied" in `bodyMedium` muted
- BOM list: `LazyColumn` of `ElevatedCard`s, one per BOM line:
  - Item name: `bodyLarge`
  - `LinearProgressIndicator(progress = scanned/required, color = AmberPrimary, trackColor = GraphiteBorder)` — turns `SuccessGreen` when satisfied
  - `"scanned / required"` right-aligned in `labelSmall` muted
  - When satisfied: card background tinted `SuccessGreen` at 12% alpha; `Icons.Filled.CheckCircle` (`SuccessGreen`, 20dp) appears trailing
- "Proceed to Mixer Code" pinned at bottom (amber filled, 56dp, disabled until `scannedIngredients.isNotEmpty()`)

### MixerCodeScreen
- `AppScaffold(title = "Mixer Code", onBack = onBack)`
- Instruction card: "Scan or enter the mixer barcode" in `bodyLarge`
- `OutlinedTextField` (amber focus)
- "Confirm Mixer Code" amber button

### PreMixCompleteScreen (review)
- `AppScaffold(title = "Review Pre-Mix", onBack = onBack)`
- Summary chip row: order no + mixer code as `SuggestionChip`s
- Ingredient list in a single `ElevatedCard` using `ListItem`s with `HorizontalDivider`
- "Confirm & Complete" amber button pinned at bottom; spinner inside when loading

### PremixConfirmedScreen
- `AppScaffold(title = "Pre-Mix Complete", onBack = null)` (no back — terminal state)
- Centred column:
  - **Success path:** `Icons.Filled.CheckCircle` (64dp, `SuccessGreen`) + "Pre-mix confirmed by WPF" (`displaySmall`) + order/mixer subtitle (`bodyMedium` muted)
  - **Queued path:** `Icons.Filled.Schedule` (64dp, `AmberPrimary`) + "Pre-mix queued" (`displaySmall`) + "Will send when online" (`bodyMedium` muted)
- "Done" amber button at bottom

---

## 6. Rajoo Workflow Screens

### MachineSelectScreen
- `AppScaffold(title = "Select Machine", onBack = onBack)`
- `LazyVerticalGrid(columns = GridCells.Fixed(2), gap = 12.dp)`
- Machine card (120dp tall): `ElevatedCard`; `Icons.Filled.Factory` (32dp, `AmberPrimary`) centred above machine name (`bodyLarge` bold, `TextPrimary`)
- Amber ripple on press
- Loading: centred `CircularProgressIndicator(color = AmberPrimary)`
- Error: red-tinted `ElevatedCard` with message + amber "Retry" button

### PalletAllocScreen
- `AppScaffold(title = "Allocate — {machineCode}", onBack = onBack)`
- **Idle/listening:** surface card with `WifiTethering` icon (48dp, `AmberPrimary`) with infinite alpha pulse animation + "Scan RFID pallet tag" (`bodyLarge`)
- **Loading:** spinner replaces pulse icon
- **Success:** `ElevatedCard` with `SuccessGreen`-tinted header (`✓ Allocated`), `LabelValueRow`s for Tag ID, Machine, Timestamp
- **Queued offline:** `AmberPrimary`-tinted card, `Icons.Filled.Schedule` icon, "Queued — will send when online"
- **Error:** `DangerRed`-tinted card, message, amber "Try Again" button
- Bottom buttons: "Allocate Another" (amber filled) + "Done" (outlined)

---

## 7. RfidRecoveryScreen

- `AppScaffold(title = "RFID Recovery", onBack = onBack)`
- **Idle:** surface card, centred `WifiTethering` (48dp, `AmberPrimary`, alpha pulse) + "Scan an RFID tag to look up a pallet" (`bodyLarge`)
- **Loading:** spinner, "Looking up pallet…"
- **PalletFound:** `ElevatedCard` with `SuccessGreen`-tinted header row (`✓ Pallet Found`), then `LabelValueRow`s for Tag ID, Batch No, Item Code, Location
- **Error:** `DangerRed`-tinted card, message, amber "Try Again"
- Bottom: "Scan Another" (amber filled) + "Done" (outlined)

**`LabelValueRow` subcomponent** (shared with PalletAllocScreen and DashboardScreen):
```kotlin
@Composable
fun LabelValueRow(label: String, value: String)
// label: bodyMedium TextMuted; value: bodyLarge TextPrimary; Row with weight split
```

---

## 8. DashboardScreen

- `AppScaffold(title = "Dashboard", onBack = onBack)`
- Custom tab strip: `TabRow` with `indicator` overridden to an amber 3dp bottom line; tab text in `labelSmall` uppercase
- **Pallet tab:** `OutlinedTextField` (amber focus) + amber "Look Up" button + result in `ElevatedCard` with `LabelValueRow`s
- **Pre-Mix tab:** `LazyColumn` of pre-mix summary `ElevatedCard`s (order no `bodyLarge` + status `Chip`); loading spinner; "No data" centred muted placeholder
- **Allocation tab:** "No data available" centred muted placeholder (no logic change)
- **Exceptions tab:** `LazyColumn` of `DangerRed`-tinted `ElevatedCard`s; loading spinner; "No exceptions" centred placeholder

---

## File Change Summary

| File | Action |
|---|---|
| `ui/theme/Color.kt` | Rewrite — new graphite + amber palette |
| `ui/theme/Theme.kt` | Rewrite — fixed dark scheme, `dynamicColor = false` |
| `ui/theme/Type.kt` | Rewrite — custom type scale |
| `ui/components/ConnectionStatusBar.kt` | Delete — replaced by AppScaffold |
| `ui/components/AppScaffold.kt` | Create |
| `ui/components/LabelValueRow.kt` | Create |
| `ui/home/HomeScreen.kt` | Rewrite |
| `ui/mixing/JobLookupScreen.kt` | Rewrite |
| `ui/mixing/IngredientScanScreen.kt` | Rewrite |
| `ui/mixing/MixerCodeScreen.kt` | Rewrite |
| `ui/mixing/PreMixCompleteScreen.kt` | Rewrite |
| `ui/mixing/MixingViewModel.kt` | Add `connectionState` + `pendingCount` flows |
| `ui/rajoo/MachineSelectScreen.kt` | Rewrite |
| `ui/rajoo/PalletAllocScreen.kt` | Rewrite |
| `ui/rajoo/RajooViewModel.kt` | Add `connectionState` + `pendingCount` flows |
| `ui/rfid/RfidRecoveryScreen.kt` | Rewrite |
| `ui/rfid/RfidViewModel.kt` | Add `connectionState` + `pendingCount` flows |
| `ui/dashboard/DashboardScreen.kt` | Rewrite |
| `ui/dashboard/DashboardViewModel.kt` | Add `connectionState` + `pendingCount` flows |
