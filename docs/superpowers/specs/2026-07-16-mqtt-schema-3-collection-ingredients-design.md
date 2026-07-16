# MQTT Schema 3.0 — Collection & Ingredients Design

**Date:** 2026-07-16
**Status:** Approved design (user delegated design decisions; every judgement call is flagged for review)
**Contract:** `C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` v3.0 (read-only reference)
**Scope:** Sub-project 3 of 5
**Depends on:** SP1 (transport, merged `0c3dd9e`), SP2 (session, merged `f970531`)

## Context

SP1 did a mechanical port: `job_card_submitted` split into `job_card_load_requested` /
`collection_resume_requested`, `ingredient_scanned` became `ingredient_scan_requested`, and the
calls moved to `request()`. It deliberately deferred every *payload shape* and the whole approval
workflow. This sub-project delivers them.

This is the largest behavioural sub-project. It also carries the app's one genuinely broken flow:
manager approval currently dead-ends with a toast.

## The headline: manager approval is architecturally inverted

v2 was a **two-message handshake**: send `manager_approval_requested`, receive an `approvalId`,
attach that id to a retried scan.

**v3 deletes both the topic and the concept.** There is no approval message and no approval token.
Instead:

1. The scan is rejected with `requiresManagerApproval: true`, `nextAction: "retry_with_manager_approval"`.
2. The scanner **resubmits the same scan** with `managerUsername` / `managerPassword` / `auditReason`
   added inline — **and a fresh `messageId`**.

The contract is unusually emphatic about that last point:

> *"Attaching manager credentials changes the payload, which makes it a **new operation**. Resending
> the rejected request's `messageId` with credentials bolted on is rejected as `message_id_reused`;
> it does **not** perform the approval. Reuse the message-specific fields, mint a fresh `messageId`."*

**We get the fresh `messageId` for free**, and that is worth stating so nobody "optimises" it away:
SP1's transport mints a new UUID inside every `request()` call. A resubmit is a new call, so it is
automatically a new operation. There is no code path that could reuse the old id short of
deliberately caching one.

`MixingUseCase.approveManagerException` — the `@Deprecated` stub that always fails, kept since SP1
purely so the UI compiled — is **deleted here**, along with `MixingViewModel.submitManagerApproval`'s
dead end.

### What the rejection must carry forward

To resubmit, we need the original scan's fields. So `NeedsManagerApproval` stops carrying an
`exceptionId` (there are no exceptions in v3) and instead carries **everything needed to rebuild the
request**: `collectionId`, `palletRfidTag`, `requestedMaterialCode`, `bagSizeOption`, `bagCount`.

**Judgement call flagged for review:** the pending scan lives in the ViewModel, not in a Room table.
It is short-lived (an operator is standing at the pallet with a manager beside them), and persisting
it would invite resuming a stale approval hours later against a collection that has moved on. If the
app dies mid-approval, the operator re-scans. That is the cheaper failure.

## Two modelling bugs the contract exposes

### 1. `lineNumber` is the line identity — not `materialCode`

> *"Duplicate SAP BOM material rows remain separate using `lineNumber`."*

`BomLine` currently has no `lineNumber` and is keyed by `itemCode`. **Two BOM rows for the same
material would collide** — the second would overwrite or merge with the first, silently corrupting
progress on both. `IngredientScanOutcome.NeedsManagerApproval` carries only `requestedMaterialCode`,
which cannot disambiguate them either.

`BomLine` gains `lineNumber`, and it becomes the identity used for list keys, diffing, and matching
a refreshed line to a displayed one.

**Judgement call flagged for review:** `ingredient_scan_requested` still identifies its target by
`requestedMaterialCode`, not `lineNumber` — that is the contract's shape, not our choice. So with
genuine duplicate rows, Station 2 decides which line a scan lands on. We display `lineNumber` and
key on it; we do not attempt to steer the allocation. If duplicate rows turn out to be common on the
floor, this is worth raising with the Station 2 developer.

### 2. `null` and `0.0` are different facts on bag fields

> *"On a line with no bag size (bulk material — `bagSize` is `null`), a tolerance measured in bags is
> meaningless: `overCollectionToleranceBags` returns `null`, no automatic tolerance applies, and
> *any* over-collection needs manager approval. Every `*Bags` field on such a line is `null`."*

Our `*Bags` fields default to `0.0`. "Zero bags collected" and "bags are meaningless for this
material" are different, and conflating them is how a bulk line ends up displaying "0 of 0 bags"
instead of a weight, or silently appearing satisfied.

All `*Bags` fields become **nullable**. `isBagFullyAllocated` must not treat a bulk line as
bag-complete; a bulk line's completion is decided on quantity alone.

**A bulk line has no automatic tolerance at all** — any over-collection needs approval. That is a
behaviour difference, not just a display one.

## Bag units: full-bag equivalents

> *"Every `*Bags` field in a response is in full-bag equivalents, never in bags-of-the-selected-size
> — otherwise `remainingBags = expectedBags − scannedBags` would not hold across scans that used
> different bag sizes."*

So an operator scanning 3 half-bags **sends** `bagSizeOption: "1/2"`, `bagCount: 3` and **gets back**
`scannedBags: 1.5`. Request units and response units differ deliberately.

**This is a UI trap.** Showing "1.5 bags" to an operator who just counted three is confusing. The UI
must present bag progress in full-bag equivalents (because that is the only unit in which the
arithmetic holds) while making the *entry* natural — the operator picks a size and counts bags of
that size.

**Judgement call flagged for review:** progress is displayed in full-bag equivalents with the unit
made explicit (e.g. "1.5 / 22.3 full bags"), rather than trying to render per-size counts. Any other
choice breaks the contract's own subtraction identity as soon as two different sizes are used on one
line.

## Over-collection tolerance is Station 2's number, never ours

> *"`ingredient_scan_result` returns `overCollectionToleranceBags` — the tolerance Station 2 actually
> applied — so the scanner never hardcodes the threshold itself."*

We display it and never re-derive it. This is the same rule SP1 established for pallet
`usable`/`recoverable` and SP2 for `allowedActions`: **Station 2 decides, the client displays.**

Note the accepted-within-tolerance case has a subtlety worth surfacing in the UI: a scan inside
tolerance credits **only the remaining required amount** to `collectedQuantity` while recording the
**full** `weightReceived`. So the two numbers legitimately differ, and an operator seeing "collected
557.049, received 560.0" is looking at correct data, not a bug.

## The short-bag waiver is a different shape from every other approval

> *"A short-bag waiver is not a reject-then-retry. It carries `managerUsername` / `managerPassword`
> on its **first** submission, because there is no scan to attempt and fail — the operator is
> declaring up front that a line will be short."*

So:
- Credentials go on the **first** submission, not a retry.
- Sent without them → rejected with `requiresManagerApproval: true`.
- `requestedMaterialCode` is **required** — there is no pallet to identify the line.
- It **adjusts the line's requirement directly** and never produces a scanned ingredient line.
- The approver needs `ingredient_approve_short_bag`, a *different* action id from an override's
  `ingredient_approve_override`.

It shares the `ingredient_scan_requested` topic but is a distinct operation with a distinct payload
(`shortBagCount`, no pallet, no bag size). Modelling it as "a scan with extra fields" would be wrong.

**As always: we never check the action id ourselves.** Station 2 checks it against the approver's
account. We collect credentials and send.

## What we display that we currently don't

`bom_loaded`'s `ingredients[]` carries a good deal we drop on the floor today: `availableQuantity`
(how much is actually on hand), `bagSize` (e.g. `"25.000 kg"`), `action`, `collected`,
`weightReceived` vs `collectedQuantity`, `approvedExtraBags`, `approvedShortBags`, and
`collectionSummary` (`waitingProductCount`, `collectedQuantity`, a human `summary` string).

**Judgement call flagged for review:** we map the full shape into the model now, but only surface
`availableQuantity`, `bagSize`, and `collectionSummary` in the UI this sub-project — those three
change what an operator does next. The rest are carried for correctness and audit without new UI.
Mapping a field is cheap; inventing UI for it is not, and I would rather the floor tell us what it
wants to see.

## Inherited defects that land here

SP2's final review caught these; this sub-project owns the flow they live in.

- **`clearError()` is dead code and `MixingUiState.Error` is a trap state.** `IngredientScanScreen`
  renders the error card with no dismiss. SP2's scan guard nearly shipped a permanently-dead reader
  because of it, and was fixed only by letting scans through in `Error`. **`Error` gets a real
  dismiss/retry affordance here**, and `clearError()` gets a caller or gets deleted.
- **The scan-guard allow-list needs a per-state decision for every state this sub-project adds.**
  Fail-closed is the right default but must be *decided*, not inherited — that is exactly how the
  trap state happened.

## Scope

**In:**
1. `BomLine.lineNumber` as line identity; duplicate material rows kept separate.
2. Nullable `*Bags` fields; bulk lines (`bagSize == null`) modelled and displayed correctly.
3. Full `bom_loaded` shape mapped: `availableQuantity`, `bagSize`, `weightReceived`,
   `collectedQuantity`, `approvedExtraBags`/`approvedShortBags`, `action`, `collected`,
   `collectionSummary`.
4. **Inline manager approval** replacing the `approvalId` handshake; delete
   `approveManagerException`.
5. **Short-bag waiver** as a distinct operation.
6. `overCollectionToleranceBags` displayed, never hardcoded; bulk-line no-tolerance behaviour.
7. `Error` dismiss/retry affordance; `clearError()` wired or deleted.
8. Per-state scan-guard decisions for new states.

**Out:**
- Machine cycles / hopper routing (SP4). Allocation (SP5).
- `open_sap_job_cards_requested` — **deferred to SP5.** It lists planned/released SAP orders and
  creates no collection; it is a convenience lookup, not part of the collection workflow, and
  nothing else depends on it.
- Persisting a pending approval across process death (see above).
- New UI for `approvedExtraBags`/`approvedShortBags`/`action`/`collected` (mapped, not surfaced).

## Testing

Fakes throughout; the Station 2 v3 backend is still in progress.

1. Two BOM rows sharing a `materialCode` but differing in `lineNumber` stay separate and track
   progress independently. *(This test fails against today's model.)*
2. A bulk line (`bagSize: null`) yields null `*Bags` and null `overCollectionToleranceBags`, and is
   not treated as bag-complete.
3. An approval retry sends the original scan's fields **plus** credentials — and a **different
   `messageId`** from the rejected attempt. *(The one that matters most: reusing the id silently
   fails to approve.)*
4. A short-bag waiver sends credentials on its **first** submission, includes
   `requestedMaterialCode`, and carries no pallet or bag size.
5. A waiver without credentials surfaces as needing approval, not as a generic failure.
6. `overCollectionToleranceBags` is read from the response, never computed.
7. A scan accepted within tolerance shows `collectedQuantity` and `weightReceived` differing, both
   as returned.
8. `Error` can be dismissed and the flow resumed without backing out and re-looking-up the job.
9. `3 × "1/2"` sent produces `scannedBags: 1.5` in the model — request and response units differ.

## Open questions for the Station 2 developer

1. **Acceptance window** — answered: not implemented, which contradicts the contract. Escalated in
   `docs/backend/2026-07-16-timestamp-acceptance-window.md`. Not blocking SP3.
2. **Is `station_2` the literal presence-topic device id?** Still open; SP2's banner depends on it.
3. Are message-specific `errorCode` values expected beyond the 14 shared codes?
4. **New, SP3-specific:** with genuine duplicate BOM rows, `ingredient_scan_requested` identifies its
   target by `requestedMaterialCode` only — so Station 2 chooses which `lineNumber` a scan lands on.
   Is that allocation deterministic (e.g. lowest `lineNumber` with remaining requirement first)? The
   operator will see progress land on one of two identical-looking rows, and we would like to explain
   why.
