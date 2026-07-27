"""active_job_cards_requested   -> active_job_cards_list
open_sap_job_cards_requested -> open_sap_job_cards_list
job_card_load_requested      -> bom_loaded
collection_resume_requested  -> bom_loaded
ingredient_collection_cancel_requested -> ingredient_collection_cancel_result"""

from envelope import Rejection, approve, build_response
from handlers.common import (collection_progress, collection_summary,
                             ingredients_payload, r3, unit_for_uom)
from state import iso, utc_now

SAP_STATUS_NAMES = {"boposPlanned": "Planned", "boposReleased": "Released"}


DEFAULT_PAGE_SIZE = 25
MAX_PAGE_SIZE = 100


def active_list(world, log, req, session):
    """4.1 keyset paging, ordered by startedAtUtc DESC, collectionId DESC.

    4.0 returned the whole queue in one message, which grows without bound as collections
    accumulate (backend issue B6). The continuation token carries the snapshot revision so a
    cursor minted against an older revision can be rejected with `page_cursor_stale` rather than
    silently returning rows from a queue that has moved.
    """
    page_size = req.get("pageSize") or DEFAULT_PAGE_SIZE
    if not isinstance(page_size, int) or page_size < 1 or page_size > MAX_PAGE_SIZE:
        raise Rejection("validation_failed",
                        f"pageSize must be between 1 and {MAX_PAGE_SIZE}.")
    statuses = req.get("statuses")
    search = (req.get("search") or "").strip().casefold()

    rows = []
    for col in world.collections.values():
        status = col["status"]
        if status == "Cancelled":
            continue
        if statuses and status not in statuses:
            continue
        if search and search not in col["collectionId"].casefold() \
                and search not in str(col["jobCardNumber"]).casefold():
            continue
        collected, remaining = collection_progress(col)
        rows.append({
            "collectionId": col["collectionId"],
            "jobCardNumber": col["jobCardNumber"],
            "productionOrderDocumentNumber": col["productionOrderDocumentNumber"],
            "productName": col["productName"],
            "status": status,
            "collectedQuantity": collected,
            "remainingQuantity": remaining,
            "claimedByMixBatchId": col["claimedByMixBatchId"],
        })

    # startedAtUtc DESC, collectionId DESC. Collection ids are monotonic here, so ordering by id
    # alone reproduces the contract's order without needing a start timestamp on every row.
    rows.sort(key=lambda r: r["collectionId"], reverse=True)

    revision = world.active_jobs_revision()
    token = req.get("continuationToken")
    start = 0
    if token:
        token_revision, _, token_after = str(token).partition("|")
        if token_revision != revision:
            log.warn(f"active job cards: continuation token is from revision "
                     f"{token_revision}, current is {revision} -> page_cursor_stale")
            raise Rejection("page_cursor_stale",
                            "The collection queue changed; request the first page again.",
                            next_action="refresh_active_jobs")
        start = next((i for i, r in enumerate(rows) if r["collectionId"] == token_after), -1) + 1
        if start == 0:
            raise Rejection("page_cursor_stale",
                            "The collection queue changed; request the first page again.",
                            next_action="refresh_active_jobs")

    page = rows[start:start + page_size]
    has_more = start + page_size < len(rows)
    next_token = f"{revision}|{page[-1]['collectionId']}" if (has_more and page) else None

    log.ok(f"active job cards: page of {len(page)} of {len(rows)} collection(s), "
           f"hasMore={has_more}, revision={revision}")
    return build_response(world, req, response_extras={
        "jobs": page,
        "totalCount": len(rows),
        "hasMore": has_more,
        "nextContinuationToken": next_token,
        "snapshotRevision": revision,
    })


def open_sap_list(world, log, req, session):
    jobs = []
    for doc_num, order in world.sap_orders.items():
        if order.get("ProductionOrderType") != "bopotStandard":
            continue
        status = SAP_STATUS_NAMES.get(order.get("ProductionOrderStatus"))
        if not status:
            continue
        manual_count = sum(1 for l in order.get("ProductionOrderLines", [])
                           if l.get("ProductionOrderIssueType") == "im_Manual")
        jobs.append({
            "jobCardNumber": doc_num,
            "productionOrderDocumentNumber": doc_num,
            "productName": order.get("ProductDescription"),
            "plannedQuantity": order.get("PlannedQuantity"),
            "dueDate": order.get("DueDate"),
            "status": status,
            "manualProductCount": manual_count,
        })
    refresh = bool(req.get("refreshFromSap"))
    log.ok(f"open SAP job cards: {len(jobs)} planned/released order(s) "
           f"(refreshFromSap={refresh})")
    return build_response(world, req, response_extras={
        "jobs": jobs,
        "refreshedAtUtc": iso(utc_now()),
        "isCached": not refresh,
        "sapOnline": True,
    })


def _snapshot_lines(world, order):
    lines = []
    for sap in order.get("ProductionOrderLines", []):
        material = sap["ItemNo"]
        manual = sap.get("ProductionOrderIssueType") == "im_Manual"
        lines.append({
            "lineNumber": sap["LineNumber"],
            "materialCode": material,
            "materialName": sap.get("ItemName"),
            "plannedQuantity": sap.get("PlannedQuantity", 0.0),
            "issuedQuantity": sap.get("IssuedQuantity", 0.0),
            "requiredQuantity": sap.get("PlannedQuantity", 0.0),
            "collectedQuantity": 0.0,
            "weightReceived": 0.0,
            "scannedBagsEq": 0.0,
            "approvedExtraBags": 0.0,
            "approvedShortBags": 0.0,
            "fullBagWeight": world.full_bag_weight(material),
            "issueType": sap.get("ProductionOrderIssueType"),
            "requiresIngredientCollection": manual,
            "uomCode": sap.get("UoMCode"),
            "unit": unit_for_uom(sap.get("UoMCode")),
            "warehouse": sap.get("Warehouse"),
        })
    return lines


def bom_loaded_response(world, req, col, resumed, next_action):
    return build_response(world, req, next_action=next_action,
                          correlation=col["collectionId"], response_extras={
        "jobCardNumber": col["jobCardNumber"],
        "productionOrderDocumentNumber": col["productionOrderDocumentNumber"],
        "collectionId": col["collectionId"],
        "resumed": resumed,
        "collectionStatus": col["status"],
        "bomSnapshotCapturedAtUtc": col["bomSnapshotCapturedAtUtc"],
        "collectionSummary": collection_summary(col),
        "ingredients": ingredients_payload(world, col),
    })


def load(world, log, req, session):
    job = str(req.get("jobCardNumber") or "")
    if not job:
        raise Rejection("validation_failed", "jobCardNumber is required.")
    order = world.sap_orders.get(job)
    if not order:
        log.fail(f"job card load: '{job}' does not map to a SAP production order")
        raise Rejection("not_found", f"Job card '{job}' does not map to a SAP production order.",
                        next_action="scan_job_card")
    if order.get("ProductionOrderType") != "bopotStandard" \
            or order.get("ProductionOrderStatus") not in SAP_STATUS_NAMES:
        raise Rejection("state_conflict",
                        f"Production order '{job}' is not a standard planned/released order.")

    col_id = world.next_id("COL")
    col = {
        "collectionId": col_id,
        "jobCardNumber": job,
        "productionOrderDocumentNumber": job,
        "productName": order.get("ProductDescription"),
        "status": "Collecting",
        "bomSnapshotCapturedAtUtc": iso(utc_now()),
        "lines": _snapshot_lines(world, order),
        "claimedByMixBatchId": None,
        "createdByOperatorId": session["operatorId"],
        "createdFromDevice": req["deviceId"],
    }
    world.collections[col_id] = col
    manual = sum(1 for l in col["lines"] if l["requiresIngredientCollection"])
    log.transition(f"collection {col_id} created from job card {job} "
                   f"('{col['productName']}'): {len(col['lines'])} BOM lines snapshotted, "
                   f"{manual} manual for handheld, status Collecting "
                   f"(new collection — loading never silently resumes)")
    return bom_loaded_response(world, req, col, resumed=False, next_action="scan_ingredient")


def seed_demo_collections(world, log):
    """Pre-load a few collections at startup (bypassing job_card_load_requested
    and every ingredient scan) so the handheld sees active job cards the
    moment it logs in. One collection per seeded SAP order plus a second
    against 510019068 that starts already ReadyForMixing, for exercising the
    mixing board without manually driving a full collection first."""
    plans = [("510019068", False), ("510019068", True), ("510018531", False)]
    for job, complete in plans:
        order = world.sap_orders.get(job)
        if not order:
            continue
        col_id = world.next_id("COL")
        col = {
            "collectionId": col_id,
            "jobCardNumber": job,
            "productionOrderDocumentNumber": job,
            "productName": order.get("ProductDescription"),
            "status": "Collecting",
            "bomSnapshotCapturedAtUtc": iso(utc_now()),
            "lines": _snapshot_lines(world, order),
            "claimedByMixBatchId": None,
            "createdByOperatorId": "SEEDED",
            "createdFromDevice": "seed",
        }
        world.collections[col_id] = col
        if complete:
            for line in col["lines"]:
                if not line["requiresIngredientCollection"]:
                    continue
                line["collectedQuantity"] = line["requiredQuantity"]
                line["weightReceived"] = line["requiredQuantity"]
                if line["fullBagWeight"]:
                    line["scannedBagsEq"] = r3(line["requiredQuantity"] / line["fullBagWeight"])
            col["status"] = "ReadyForMixing"
        log.transition(f"seed: collection {col_id} for job {job} pre-loaded "
                       f"({'ReadyForMixing, all ingredients collected' if complete else 'Collecting, fresh'})")
        if complete:
            # 4.1 §7: a ReadyForMixing collection needs a WPF-saved mixer plan before any mixer
            # scan. Seed one reserving two Main mixers so the handheld can exercise the plan-
            # driven mixer start, two-mixer intersection, and destination assignment end-to-end.
            world.save_mix_plan(col_id, ["MXR-01", "MXR-02"])


def resume(world, log, req, session):
    col_id = req.get("collectionId")
    if not col_id:
        raise Rejection("validation_failed", "collectionId is required.")
    col = world.collections.get(col_id)
    if not col:
        raise Rejection("not_found", f"Collection '{col_id}' was not found.",
                        next_action="active_job_cards")
    if req.get("jobCardNumber") and str(req["jobCardNumber"]) != col["jobCardNumber"]:
        raise Rejection("validation_failed",
                        f"jobCardNumber '{req['jobCardNumber']}' does not match "
                        f"collection {col_id} (job {col['jobCardNumber']}).")
    status = col["status"]
    if status == "Cancelled":
        raise Rejection("state_conflict", f"Collection {col_id} is cancelled.",
                        next_action="active_job_cards")
    if status == "Collecting":
        next_action = "scan_ingredient"
    elif status == "ReadyForMixing":
        next_action = "start_mixing"
    else:
        raise Rejection("state_conflict",
                        f"Collection {col_id} is {status}; nothing to resume.",
                        next_action="active_job_cards")
    log.ok(f"collection {col_id} resumed on {req['deviceId']} by {session['operatorId']} "
           f"(status {status}, from stored snapshot, no SAP reload) -> {next_action}")
    return bom_loaded_response(world, req, col, resumed=True, next_action=next_action)


def cancel(world, log, req, session):
    col_id = req.get("collectionId")
    if not col_id:
        raise Rejection("validation_failed", "collectionId is required.")
    col = world.collections.get(col_id)
    if not col:
        raise Rejection("not_found", f"Collection '{col_id}' was not found.")
    approver = approve(world, log, req, "ingredient_collection_cancel")
    if col["status"] not in ("Collecting", "ReadyForMixing"):
        raise Rejection("state_conflict",
                        f"Collection {col_id} is {col['status']}; cancellation is rejected "
                        f"after mixing has started.")
    old = col["status"]
    col["status"] = "Cancelled"
    log.transition(f"collection {col_id}: {old} -> Cancelled "
                   f"(approved by {approver['approverUserId']}, "
                   f"auditReason: {req['auditReason']!r})")
    extras = {"collectionId": col_id, "collectionStatus": "Cancelled"}
    extras.update(approver)
    return build_response(world, req, response_extras=extras,
                          next_action="scan_job_card", correlation=col_id)
