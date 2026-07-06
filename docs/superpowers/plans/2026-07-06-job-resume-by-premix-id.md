# Resume Active Job by preMixId Implementation Plan

> **SUPERSEDED (2026-07-06):** Tasks 2-4 were implemented and reviewed clean, then reverted. The backend dev confirmed the authoritative MQTT contract does not add `preMixId` to the `job_card_submitted` request — Station 2 already auto-resumes by `jobCardNumber` + operator + handheld. See the companion design doc's superseded note. Kept here for history only.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let tapping an active job on the Job Lookup screen pass its already-known `preMixId` through `job_card_submitted`, so Station 2 can resume that pre-mix directly instead of re-running the SAP production-order lookup.

**Architecture:** Add an optional `preMixId` field to the existing `job_card_submitted` request (data → domain → ViewModel → UI), defaulting to empty so the manual job-card-number entry path is unaffected. Document the new field and its resume semantics in the MQTT contract. No new message types, no changes to `bom_loaded`.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Gson, JUnit + Mockito-Kotlin, Gradle.

## Global Constraints

- Only `RFID_MQTT_CONTRACT.md` may be edited under `C:\Dev\PPNAM-Station-2` — every other file there is read-only (see this repo's `CLAUDE.md`).
- `correlationKey` for `job_card_submitted` stays `jobCardNumber` — do not change it to `preMixId`.
- `preMixId` defaults to `""` at every layer so existing call sites keep compiling and behaving unchanged.
- Run tests with: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*.MixingUseCaseTest"` (and the ViewModel equivalent in Task 3).

---

### Task 1: Document preMixId in the MQTT contract

**Files:**
- Modify: `C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md:158-187` (the "Job card and BOM" section)

**Interfaces:**
- Produces: the documented `preMixId` field name and resume-semantics wording that Task 2's request DTO must match exactly.

- [ ] **Step 1: Add the `preMixId` field to the `job_card_submitted` request example**

Replace the request JSON block at lines 166-176:

```json
{
  "messageId": "job-0001",
  "schemaVersion": "1.0",
  "deviceId": "handheld_1",
  "operatorSessionId": "session-id",
  "timestampUtc": "2026-06-30T10:31:00Z",
  "correlationKey": "510019068",
  "jobCardNumber": "510019068"
}
```

with:

```json
{
  "messageId": "job-0001",
  "schemaVersion": "1.0",
  "deviceId": "handheld_1",
  "operatorSessionId": "session-id",
  "timestampUtc": "2026-06-30T10:31:00Z",
  "correlationKey": "510019068",
  "jobCardNumber": "510019068",
  "preMixId": ""
}
```

- [ ] **Step 2: Document the resume semantics**

Immediately below that JSON block (before the existing "A successful `bom_loaded` response:" paragraph at line 178), insert:

```markdown
`preMixId` is optional and blank by default. Populate it only when the handheld already knows the pre-mix it wants to resume — e.g. tapping an entry from `active_job_cards_list`, which already returns each open job's `preMixId`. When `preMixId` is non-blank, Station 2 resumes that specific pre-mix directly from its stored BOM snapshot and skips the SAP production-order lookup. When blank, behavior is unchanged from today: Station 2 matches by `jobCardNumber` + operator + handheld and loads the production order from SAP.
```

- [ ] **Step 3: Verify the edit**

Run: `grep -n "preMixId" "C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md" | Select-String "job-0001|resumes that specific"` (PowerShell) — confirm both the JSON field and the new paragraph are present near line 158-190, and that no other part of the file changed.

- [ ] **Step 4: Commit**

```bash
cd "C:/Dev/PPNAM-Station-2"
git add RFID_MQTT_CONTRACT.md
git commit -m "docs(mqtt): document optional preMixId resume field on job_card_submitted"
```

---

### Task 2: Add preMixId to JobCardSubmittedRequest and MixingUseCase.lookupJob

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt:3-11` (`JobCardSubmittedRequest`)
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt:48-101` (`lookupJob`)
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt`

**Interfaces:**
- Consumes: `JobCardSubmittedRequest(messageId, schemaVersion = "1.0", deviceId, operatorSessionId = "", timestampUtc, correlationKey, jobCardNumber)` (current constructor, from `JobCardMessages.kt`).
- Produces: `MixingUseCase.lookupJob(jobCardNumber: String, preMixId: String = ""): Result<ProductionOrder>` — the exact signature Task 3's `MixingViewModel.lookupJob` calls into.

- [ ] **Step 1: Write the failing tests**

Add these two tests to `MixingUseCaseTest.kt`, directly after the existing `` `lookupJob sends job_card_submitted on the correct request envelope` `` test (after line 217, before the `// --- cancelJob ---` comment):

```kotlin
    @Test
    fun `lookupJob includes preMixId in the request envelope when supplied`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("job_card_submitted"), eq("bom_loaded"), any(),
                eq(BomLoadedResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Error("timeout"))

        useCase.lookupJob("510019068", "premix-1")

        val captor = argumentCaptor<String>()
        verify(mockMqtt).sendTyped(
            eq("job_card_submitted"), eq("bom_loaded"), captor.capture(),
            eq(BomLoadedResponse::class.java), eq(false)
        )
        assertTrue(captor.firstValue.contains("\"preMixId\":\"premix-1\""))
    }

    @Test
    fun `lookupJob sends an empty preMixId when the caller omits it`() = runTest {
        whenever(
            mockMqtt.sendTyped(
                eq("job_card_submitted"), eq("bom_loaded"), any(),
                eq(BomLoadedResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Error("timeout"))

        useCase.lookupJob("510019068")

        val captor = argumentCaptor<String>()
        verify(mockMqtt).sendTyped(
            eq("job_card_submitted"), eq("bom_loaded"), captor.capture(),
            eq(BomLoadedResponse::class.java), eq(false)
        )
        assertTrue(captor.firstValue.contains("\"preMixId\":\"\""))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*.MixingUseCaseTest"`
Expected: FAIL — `useCase.lookupJob("510019068", "premix-1")` does not compile (no such overload) / the two new tests fail to resolve.

- [ ] **Step 3: Add the field to JobCardSubmittedRequest**

In `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt`, replace:

```kotlin
data class JobCardSubmittedRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val jobCardNumber: String
)
```

with:

```kotlin
data class JobCardSubmittedRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val jobCardNumber: String,
    val preMixId: String = ""
)
```

- [ ] **Step 4: Add the parameter to MixingUseCase.lookupJob**

In `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`, replace the method signature and request construction (lines 48-59):

```kotlin
    suspend fun lookupJob(jobCardNumber: String): Result<ProductionOrder> {
        val deviceId = settingsRepository.current().deviceId
        val requestJson = gson.toJson(
            JobCardSubmittedRequest(
                messageId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = jobCardNumber,
                jobCardNumber = jobCardNumber
            )
        )
```

with:

```kotlin
    suspend fun lookupJob(jobCardNumber: String, preMixId: String = ""): Result<ProductionOrder> {
        val deviceId = settingsRepository.current().deviceId
        val requestJson = gson.toJson(
            JobCardSubmittedRequest(
                messageId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = jobCardNumber,
                jobCardNumber = jobCardNumber,
                preMixId = preMixId
            )
        )
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*.MixingUseCaseTest"`
Expected: PASS — all `MixingUseCaseTest` tests green, including the two new ones.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt
git commit -m "feat(mixing): add optional preMixId to job_card_submitted request"
```

---

### Task 3: Wire preMixId through MixingViewModel.lookupJob

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt:92-103` (`lookupJob`)
- Test: `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt`

**Interfaces:**
- Consumes: `MixingUseCase.lookupJob(jobCardNumber: String, preMixId: String = ""): Result<ProductionOrder>` (Task 2).
- Produces: `MixingViewModel.lookupJob(orderNo: String, preMixId: String = "")` — the exact signature Task 4's `JobLookupScreen.kt` tap handler calls into.

- [ ] **Step 1: Write the failing test**

Add this test to `MixingViewModelTest.kt`, directly after the existing `` `lookupJob failure sets Error state` `` test (after line 94):

```kotlin
    @Test
    fun `lookupJob forwards preMixId to the use case`() = runTest {
        whenever(mockUseCase.lookupJob("510019068", "premix-1")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068", "premix-1")
        advanceUntilIdle()
        verify(mockUseCase).lookupJob("510019068", "premix-1")
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*.MixingViewModelTest"`
Expected: FAIL — `viewModel.lookupJob("510019068", "premix-1")` does not compile (no such overload).

- [ ] **Step 3: Add the parameter to MixingViewModel.lookupJob**

In `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`, replace (lines 92-103):

```kotlin
    fun lookupJob(orderNo: String) {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.lookupJob(orderNo)
                .onSuccess { order ->
                    currentOrderNo = orderNo
                    cachedOrder = order
                    _uiState.value = MixingUiState.OrderLoaded(order)
                }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Unknown error") }
        }
    }
```

with:

```kotlin
    fun lookupJob(orderNo: String, preMixId: String = "") {
        viewModelScope.launch {
            _uiState.value = MixingUiState.Loading
            useCase.lookupJob(orderNo, preMixId)
                .onSuccess { order ->
                    currentOrderNo = orderNo
                    cachedOrder = order
                    _uiState.value = MixingUiState.OrderLoaded(order)
                }
                .onFailure { e -> _uiState.value = MixingUiState.Error(e.message ?: "Unknown error") }
        }
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "*.MixingViewModelTest"`
Expected: PASS — all `MixingViewModelTest` tests green, including the new one.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt
git commit -m "feat(mixing): forward preMixId from ViewModel.lookupJob to the use case"
```

---

### Task 4: Pass preMixId from the active-job tap handler

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/JobLookupScreen.kt:71-86` (active job card `clickable`)

**Interfaces:**
- Consumes: `MixingViewModel.lookupJob(orderNo: String, preMixId: String = "")` (Task 3); `ActiveJobCardSummary.preMixId` (already present, `data/mqtt/dto/JobCardMessages.kt:55`).

- [ ] **Step 1: Update the tap handler**

In `app/src/main/java/com/ppnam/station2aa/ui/mixing/JobLookupScreen.kt`, replace (line 75):

```kotlin
                                .clickable(enabled = !isLoading) { viewModel.lookupJob(job.jobCardNumber) },
```

with:

```kotlin
                                .clickable(enabled = !isLoading) { viewModel.lookupJob(job.jobCardNumber, job.preMixId) },
```

- [ ] **Step 2: Build to confirm it compiles**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manually verify in the running app**

Launch the app, log in, open Job Lookup, and confirm an entry appears under "Active Jobs" (requires at least one open pre-mix on the backend). Tap it and confirm the app still navigates to the ingredient-scan screen for that job as before (behavior is externally identical from the app's side — the only observable difference is on the backend, which now receives a non-blank `preMixId` and, once the WPF side implements the contract change from Task 1, skips the SAP lookup).

- [ ] **Step 4: Run the full unit test suite**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/mixing/JobLookupScreen.kt
git commit -m "feat(mixing): tap-to-load an active job now resumes by preMixId"
```
