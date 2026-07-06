# Resume Active Job by preMixId — Design

## Problem

The Job Lookup screen's "Active Jobs" quick-access list already returns each open job's `preMixId` (via `active_job_cards_list`), but tapping a card ignores it and calls `lookupJob(jobCardNumber)` — the same request used for a brand-new job card scan. Per the MQTT contract, `job_card_submitted` always reloads the production order from SAP, even when the pre-mix already exists. Tapping an active job should instead let Station 2 resume that specific pre-mix directly, skipping the redundant SAP product lookup.

## Design

### Contract (`C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` only)

Add an optional `preMixId` field to the `job_card_submitted` request. When non-blank, Station 2 resumes that pre-mix directly from its stored BOM snapshot instead of re-running the SAP production-order lookup. `jobCardNumber` and `correlationKey` semantics are unchanged (`correlationKey` stays `jobCardNumber`). `bom_loaded` response is unchanged — it already returns `preMixId` and `resumedExistingPreMix`.

This is the only file this change touches in the sibling repo; the actual resume-by-preMixId logic in the WPF backend is out of scope for this implementation and is the receiving team's responsibility once the contract is updated.

### Android app — data layer

`JobCardSubmittedRequest` (`app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt`) gets a new field:

```kotlin
val preMixId: String = ""
```

Defaults empty so it's a no-op for requests that don't have one yet.

### Android app — domain layer

`MixingUseCase.lookupJob` (`app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`) gains a second optional parameter:

```kotlin
suspend fun lookupJob(jobCardNumber: String, preMixId: String = ""): Result<ProductionOrder>
```

The value is passed straight into `JobCardSubmittedRequest.preMixId`. All existing call sites and tests keep compiling unchanged because of the default.

### Android app — ViewModel/UI

`MixingViewModel.lookupJob` (`app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`) gains the same optional second parameter and forwards it to the use case.

`JobLookupScreen.kt`'s active-job card tap handler changes from:

```kotlin
.clickable(enabled = !isLoading) { viewModel.lookupJob(job.jobCardNumber) }
```

to:

```kotlin
.clickable(enabled = !isLoading) { viewModel.lookupJob(job.jobCardNumber, job.preMixId) }
```

The manual "Production Order No." text field + "Look Up" button path is unaffected — it keeps calling `lookupJob(orderInput)` with no `preMixId`, since a freshly typed order number has no known pre-mix yet.

### Error handling

No new error paths. This reuses the existing `bom_loaded` success/failure handling already in `MixingUseCase.lookupJob` — a resume that the backend rejects still comes back through the existing `accepted = false` / `reason` branch, surfaced the same way a rejected fresh job-card scan is today.

### Testing

Add to `MixingUseCaseTest`:
- `lookupJob` includes `preMixId` in the request envelope when the caller supplies one.
- `lookupJob` sends an empty `preMixId` when the caller omits it (existing/default behavior, asserted explicitly).

No changes needed to `MixingViewModelTest` or `JobLookupScreen` UI tests beyond what a straightforward parameter pass-through implies — verify the tap handler passes `job.preMixId` if a UI test already exercises that click.

## Out of scope

- Any change to WPF backend resume logic (lives in the sibling repo, read-only except the contract file).
- Changing `correlationKey` semantics for `job_card_submitted`.
- The manual job-card-number entry path gaining any preMixId awareness — it has none to give.
