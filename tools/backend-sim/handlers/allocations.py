"""allocation_overview_requested   -> allocation_overview_result
full_pallet_allocation_requested -> direct_allocation_result
bag_allocation_requested         -> direct_allocation_result
allocation_action_requested      -> allocation_action_result"""

from envelope import NO_APPROVER, Rejection, approve, build_response
from handlers.common import BAG_FRACTIONS, EPS, r3
from state import World, iso, utc_now
from handlers.cycles import _assign_machine, _job_has_active_run


def overview(world, log, req, session):
    po_filter = req.get("productionOrderDocumentNumber")
    machines = []
    for m in world.machines.values():
        if m["family"] not in ("Extruder", "Rajoo"):
            continue
        run = world.runs.get(m["runId"]) if m["runId"] else None
        machines.append({
            "displayName": m["displayName"],
            "machineCode": m["machineCode"],
            "machineFamily": m["family"],
            "status": m["status"],
            "isAvailable": m["status"] == "Available",
            "cycleId": m["cycleId"],
            "runId": m["runId"],
            "jobCardNumber": m["jobCardNumber"],
            "assignedByDisplayName": m["assignedByDisplayName"],
            "inactiveReason": m["inactiveReason"],
            "activeSourceCount": len([a for a in world.allocations.values()
                                      if run and a["runId"] == run["runId"]
                                      and a["status"] == "Active"]),
        })

    def po_match(v):
        return po_filter is None or str(v) == str(po_filter)

    ready_collections = [{
        "collectionId": c["collectionId"],
        "jobCardNumber": c["jobCardNumber"],
        "productionOrderDocumentNumber": c["productionOrderDocumentNumber"],
        "productName": c["productName"],
        "status": c["status"],
    } for c in world.collections.values()
        if c["status"] == "ReadyForRouting" and po_match(c["productionOrderDocumentNumber"])]

    ready_premixes = [{
        "preMixId": p["preMixId"],
        "collectionId": p["collectionId"],
        "jobCardNumber": p["jobCardNumber"],
        "productionOrderDocumentNumber": p["productionOrderDocumentNumber"],
        "status": p["status"],
    } for p in world.premixes.values()
        if p["status"] == "ReadyForAllocation" and po_match(p["productionOrderDocumentNumber"])]

    active_runs = [{
        "runId": r["runId"],
        "machineCode": r["machineCode"],
        "machineFamily": r["machineFamily"],
        "productionOrderDocumentNumber": r["productionOrderDocumentNumber"],
        "cycleId": r["cycleId"],
        "allocatedSources": [{
            "allocationId": a["allocationId"],
            "sourceType": a["sourceType"],
            "sourceId": a["sourceId"],
            "palletRfidTag": a["palletRfidTag"],
            "quantity": a["quantity"],
            "status": a["status"],
        } for a in world.allocations.values()
            if a["runId"] == r["runId"]],
    } for r in world.runs.values()
        if r["active"] and po_match(r["productionOrderDocumentNumber"])]

    can_complete = (po_filter is not None
                    and not _job_has_active_run(world, po_filter)
                    and str(po_filter) not in world.local_jobs)
    log.ok(f"allocation overview (filter={po_filter}): {len(machines)} machines, "
           f"{len(ready_collections)} ready collections, {len(ready_premixes)} ready pre-mixes, "
           f"{len(active_runs)} active runs")
    return build_response(world, req, response_extras={
        "machines": machines,
        "readyCollections": ready_collections,
        "readyPreMixes": ready_premixes,
        "activeRuns": active_runs,
        "canCompleteStation2Work": can_complete,
    })


# ---------------------------------------------------------- direct pallets ----
def _direct_allocation_common(world, log, req, session):
    """Shared validation for full-pallet and bag allocation.
    Returns (po, machine, pallet)."""
    po = str(req.get("productionOrderDocumentNumber") or "")
    code = req.get("extruderCode")
    tag = req.get("palletRfidTag")
    if not po or not code or not tag:
        raise Rejection("validation_failed",
                        "productionOrderDocumentNumber, extruderCode and palletRfidTag "
                        "are all required.")
    m = world.machines.get(code)
    if not m or m["family"] != "Extruder":
        raise Rejection("machine_unavailable",
                        f"'{code}' is not a configured Extruder; direct pallet allocation "
                        f"is supported only for configured Extruders.")
    if m["status"] == "Inactive":
        raise Rejection("machine_unavailable", f"{m['displayName']} is inactive.")
    pallet = world.pallets.get(tag)
    if not pallet:
        raise Rejection("not_found", f"Pallet tag '{tag}' is not known.")
    if pallet["blocked"]:
        raise Rejection("validation_failed", f"Pallet {pallet['palletId']} is blocked.")
    if World.pallet_recoverable(pallet):
        raise Rejection("state_conflict",
                        f"Pallet {pallet['palletId']} has no Station 2 arrival record "
                        f"({pallet['palletState']}).", next_action="recover_holding")
    if pallet["palletState"] != "Holding":
        raise Rejection("state_conflict",
                        f"Pallet {pallet['palletId']} is {pallet['palletState']}, not Holding.")
    order = world.sap_orders.get(po)
    if not order:
        raise Rejection("not_found", f"Production order '{po}' was not found.")
    bom_materials = {l["ItemNo"] for l in order.get("ProductionOrderLines", [])}
    if pallet["productCode"] not in bom_materials:
        raise Rejection("validation_failed",
                        f"Pallet product {pallet['productCode']} is not on the BOM of "
                        f"production order {po}.")
    if m["status"] == "InUse":
        run = world.runs.get(m["runId"])
        if not (run and str(run["productionOrderDocumentNumber"]) == po):
            raise Rejection("machine_unavailable",
                            f"{m['displayName']} is busy with another job.")
    return po, m, pallet


def _run_and_cycle_for(world, log, req, session, m, po):
    if m["status"] == "InUse":
        return world.runs[m["runId"]], world.cycles[m["cycleId"]]
    run_id = world.next_id("RUN")
    cycle_id = world.next_id("CYC")
    run = {"runId": run_id, "machineCode": m["machineCode"], "machineFamily": m["family"],
           "productionOrderDocumentNumber": po, "active": True,
           "allocationIds": [], "cycleId": cycle_id}
    cycle = {"cycleId": cycle_id, "machineCode": m["machineCode"],
             "machineFamily": m["family"], "collectionId": None, "preMixId": None,
             "runId": run_id, "productionOrderDocumentNumber": po,
             "active": True, "startedAtUtc": iso(utc_now()), "finishedAtUtc": None}
    world.runs[run_id] = run
    world.cycles[cycle_id] = cycle
    operator = world.operator_by_id(session["operatorId"])
    _assign_machine(world, m, cycle_id, None, None, run_id, po, req, session, operator)
    log.transition(f"extruder {m['machineCode']}: Available -> InUse "
                   f"(run {run_id}, cycle {cycle_id}, job {po})")
    return run, cycle


def _direct_result(world, req, alloc, pallet, cycle, source_type,
                   bag_option=None, bag_count=None):
    fbw = pallet.get("fullBagWeight")
    return build_response(world, req, next_action="scan_same_machine_to_finish",
                          correlation=pallet["palletRfidTag"], response_extras={
        "allocationId": alloc["allocationId"],
        "runId": alloc["runId"],
        "productionOrderDocumentNumber": alloc["productionOrderDocumentNumber"],
        "extruderCode": alloc["machineCode"],
        "sourceType": source_type,
        "palletRfidTag": pallet["palletRfidTag"],
        "palletId": pallet["palletId"],
        "productCode": pallet["productCode"],
        "productName": pallet["productName"],
        "batchNumber": pallet["batchNumber"],
        "allocatedQuantity": alloc["quantity"],
        "bagSizeOption": bag_option,
        "bagCount": bag_count,
        "allocatedBags": r3(alloc["quantity"] / fbw) if fbw else None,
        "remainingPalletQuantity": pallet["remainingQuantity"],
        "unit": pallet["unit"],
        "cycleId": cycle["cycleId"],
        "sapProductionOrderChanged": False,
    })


def full_pallet(world, log, req, session):
    po, m, pallet = _direct_allocation_common(world, log, req, session)
    for a in world.allocations.values():
        if (a["status"] == "Active" and a["sourceType"] == "FullPallet"
                and a["palletRfidTag"] == pallet["palletRfidTag"]):
            raise Rejection("state_conflict",
                            f"Pallet {pallet['palletId']} already has an active full-pallet "
                            f"allocation ({a['allocationId']}).",
                            next_action="review_allocation")
    if pallet["remainingQuantity"] <= EPS:
        raise Rejection("validation_failed", f"Pallet {pallet['palletId']} is empty.")

    run, cycle = _run_and_cycle_for(world, log, req, session, m, po)
    qty = pallet["remainingQuantity"]
    alloc_id = world.next_id("ALLOC")
    alloc = {"allocationId": alloc_id, "runId": run["runId"], "machineCode": m["machineCode"],
             "sourceType": "FullPallet", "sourceId": pallet["palletRfidTag"],
             "palletRfidTag": pallet["palletRfidTag"],
             "productionOrderDocumentNumber": po, "status": "Active", "quantity": qty}
    world.allocations[alloc_id] = alloc
    run["allocationIds"].append(alloc_id)
    pallet["remainingQuantity"] = 0.0
    pallet["palletState"] = "Mixing"
    pallet["localLocation"] = m["machineCode"]
    log.transition(f"pallet {pallet['palletId']}: full remaining {qty}{pallet['unit']} "
                   f"allocated to {m['machineCode']} (allocation {alloc_id}, run {run['runId']}); "
                   f"pallet -> Mixing at {m['machineCode']}")
    return _direct_result(world, req, alloc, pallet, cycle, "FullPallet")


def bags(world, log, req, session):
    po, m, pallet = _direct_allocation_common(world, log, req, session)
    bag_option = req.get("bagSizeOption")
    bag_count = req.get("bagCount")
    if bag_option not in BAG_FRACTIONS:
        raise Rejection("validation_failed",
                        f"bagSizeOption must be one of {sorted(BAG_FRACTIONS)}.")
    if not isinstance(bag_count, (int, float)) or bag_count <= 0:
        raise Rejection("validation_failed", "bagCount must be a positive number.")
    fbw = pallet.get("fullBagWeight")
    if not fbw:
        raise Rejection("validation_failed",
                        f"Pallet {pallet['palletId']} is bulk stock; bag allocation "
                        f"needs a Station 1 full-bag weight.")
    weight = r3(bag_count * BAG_FRACTIONS[bag_option] * fbw)
    log.step(f"bag allocation math: {bag_count} × {bag_option} × {fbw}kg = {weight}kg")
    if weight > pallet["remainingQuantity"] + EPS:
        raise Rejection("validation_failed",
                        f"Pallet {pallet['palletId']} holds only "
                        f"{pallet['remainingQuantity']}{pallet['unit']}; cannot allocate {weight}.")

    run, cycle = _run_and_cycle_for(world, log, req, session, m, po)
    alloc_id = world.next_id("ALLOC")
    alloc = {"allocationId": alloc_id, "runId": run["runId"], "machineCode": m["machineCode"],
             "sourceType": "PalletBags", "sourceId": pallet["palletRfidTag"],
             "palletRfidTag": pallet["palletRfidTag"],
             "productionOrderDocumentNumber": po, "status": "Active", "quantity": weight}
    world.allocations[alloc_id] = alloc
    run["allocationIds"].append(alloc_id)
    pallet["remainingQuantity"] = r3(pallet["remainingQuantity"] - weight)
    if pallet["remainingQuantity"] <= EPS:
        pallet["remainingQuantity"] = 0.0
        pallet["palletState"] = "Consumed"
        pallet["localLocation"] = "Consumed"
        log.transition(f"pallet {pallet['palletId']}: fully depleted -> Consumed")
    log.transition(f"pallet {pallet['palletId']}: {weight}{pallet['unit']} "
                   f"({bag_count} × {bag_option} bags) allocated to {m['machineCode']} "
                   f"(allocation {alloc_id}); remaining {pallet['remainingQuantity']}")
    return _direct_result(world, req, alloc, pallet, cycle, "PalletBags",
                          bag_option, bag_count)


# ------------------------------------------------------------ return/transfer ----
def action(world, log, req, session):
    alloc_id = req.get("allocationId")
    act = req.get("action")
    if act not in ("return", "transfer"):
        raise Rejection("validation_failed", "action must be 'return' or 'transfer'.")
    alloc = world.allocations.get(alloc_id)
    if not alloc:
        raise Rejection("not_found", f"Allocation '{alloc_id}' was not found.")
    if alloc["status"] != "Active":
        raise Rejection("state_conflict",
                        f"Allocation {alloc_id} is {alloc['status']}, not Active.",
                        next_action="review_allocation")
    approver = approve(world, log, req,
                       "allocation_return" if act == "return" else "allocation_transfer")

    previous_status = alloc["status"]
    replacement_id = None
    replacement_run_id = None
    restored = None

    if act == "return":
        alloc["status"] = "Returned"
        restored = _restore_source(world, log, alloc)
    else:
        dest_code = req.get("destinationMachineCode")
        dest = world.machines.get(dest_code) if dest_code else None
        if not dest or dest["family"] not in ("Extruder", "Rajoo"):
            raise Rejection("validation_failed",
                            "destinationMachineCode must name a configured Extruder or Rajoo.")
        if dest["status"] == "Inactive":
            raise Rejection("machine_unavailable", f"{dest['displayName']} is inactive.")
        po = alloc["productionOrderDocumentNumber"]
        if dest["status"] == "InUse":
            run = world.runs.get(dest["runId"])
            if not (run and str(run["productionOrderDocumentNumber"]) == str(po)):
                raise Rejection("machine_unavailable",
                                f"{dest['displayName']} is busy with another job.")
        dest_run, _ = _run_and_cycle_for(world, log, req, session, dest, po)
        alloc["status"] = "Transferred"
        replacement_id = world.next_id("ALLOC")
        replacement = dict(alloc)
        replacement.update({"allocationId": replacement_id, "runId": dest_run["runId"],
                            "machineCode": dest_code, "status": "Active"})
        world.allocations[replacement_id] = replacement
        dest_run["allocationIds"].append(replacement_id)
        replacement_run_id = dest_run["runId"]
        log.transition(f"allocation {alloc_id} -> Transferred; replacement "
                       f"{replacement_id} Active on {dest_code} (run {dest_run['runId']})")

    log.ok(f"allocation {act} on {alloc_id} approved by {approver['approverUserId']} "
           f"(auditReason: {req['auditReason']!r})")
    extras = {
        "allocationId": alloc_id,
        "action": act,
        "previousStatus": previous_status,
        "newStatus": alloc["status"],
        "replacementAllocationId": replacement_id,
        "replacementRunId": replacement_run_id,
        "restoredSource": restored,
        "sapProductionOrderChanged": False,
    }
    extras.update(approver)
    return build_response(world, req, next_action="review_allocation",
                          correlation=alloc_id, response_extras=extras)


def _restore_source(world, log, alloc):
    st = alloc["sourceType"]
    if st == "Collection":
        col = world.collections.get(alloc["sourceId"])
        if col:
            col["status"] = "ReadyForRouting"
            log.transition(f"collection {col['collectionId']}: Routed -> ReadyForRouting "
                           f"(allocation returned)")
            return {"type": "Collection", "id": col["collectionId"],
                    "status": col["status"]}
    elif st == "PreMix":
        pm = world.premixes.get(alloc["sourceId"])
        if pm:
            pm["status"] = "ReadyForAllocation"
            log.transition(f"pre-mix {pm['preMixId']}: Allocated -> ReadyForAllocation "
                           f"(allocation returned)")
            return {"type": "PreMix", "id": pm["preMixId"], "status": pm["status"]}
    else:  # FullPallet / PalletBags
        pallet = world.pallets.get(alloc["palletRfidTag"])
        if pallet:
            pallet["remainingQuantity"] = r3(pallet["remainingQuantity"] + (alloc["quantity"] or 0))
            pallet["palletState"] = "Holding"
            pallet["localLocation"] = "Holding"
            log.transition(f"pallet {pallet['palletId']}: {alloc['quantity']}{pallet['unit']} "
                           f"restored -> Holding ({pallet['remainingQuantity']} remaining)")
            return {"type": st, "id": pallet["palletRfidTag"],
                    "status": pallet["palletState"],
                    "remainingQuantity": pallet["remainingQuantity"]}
    return None
