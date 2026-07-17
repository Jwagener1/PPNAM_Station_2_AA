"""hopper_overview_requested          -> hopper_overview_result
machine_cycle_start_requested       -> machine_cycle_start_result
machine_cycle_finish_requested      -> machine_cycle_finish_result
machine_cycle_force_close_requested -> machine_cycle_force_close_result"""

from envelope import NO_APPROVER, Rejection, approve, build_response
from state import iso, utc_now


def hopper_overview(world, log, req, session):
    log.ok("hopper overview requested -> full board")
    return build_response(world, req, response_extras={"hoppers": world.hopper_board()})


# ------------------------------------------------------------------ start ----
def _conflict(world, code, conflict_code, reason):
    m = world.machines.get(code, {})
    return {
        "machineCode": code,
        "conflictCode": conflict_code,
        "reason": reason,
        "cycleId": m.get("cycleId"),
        "collectionId": m.get("collectionId"),
        "preMixId": m.get("preMixId"),
        "jobCardNumber": m.get("jobCardNumber"),
    }


def _start_reject(world, req, po, error_code, reason, conflicts=None):
    return build_response(world, req, accepted=False, error_code=error_code,
                          reason=reason, next_action="review_allocation",
                          response_extras={
        "machineFamily": None,
        "productionOrderDocumentNumber": po,
        "collectionIds": req.get("collectionIds") or [],
        "preMixIds": req.get("preMixIds") or [],
        "linkedPreMixId": None,
        "runId": None,
        "cycles": [],
        "routeIds": [],
        "allocationIds": [],
        "conflicts": conflicts or [],
        "hoppers": world.hopper_board(),
    })


def _assign_machine(world, m, cycle_id, col_id, premix_id, run_id, job, req, session, operator):
    m.update({
        "status": "InUse",
        "cycleId": cycle_id,
        "collectionId": col_id,
        "preMixId": premix_id,
        "runId": run_id,
        "jobCardNumber": job,
        "assignedAtUtc": iso(utc_now()),
        "assignedByOperatorId": session["operatorId"],
        "assignedByDisplayName": operator["displayName"] if operator else None,
        "assignedFromDevice": req["deviceId"],
    })


def _release_machine(world, m):
    m.update({
        "status": "Available" if m["status"] != "Inactive" else "Inactive",
        "cycleId": None, "collectionId": None, "preMixId": None, "runId": None,
        "jobCardNumber": None, "assignedAtUtc": None, "assignedByOperatorId": None,
        "assignedByDisplayName": None, "assignedFromDevice": None,
    })


def start(world, log, req, session):
    po = str(req.get("productionOrderDocumentNumber") or "")
    machine_codes = req.get("machineCodes")
    collection_ids = req.get("collectionIds")
    premix_ids = req.get("preMixIds")
    if not po:
        raise Rejection("validation_failed", "productionOrderDocumentNumber is required.")
    for name, val in (("machineCodes", machine_codes), ("collectionIds", collection_ids),
                      ("preMixIds", premix_ids)):
        if not isinstance(val, list):
            raise Rejection("validation_failed",
                            f"{name} must always be present as an array ([] when empty).")
    if not machine_codes:
        raise Rejection("validation_failed", "machineCodes must contain at least one code.")

    # family comes from CONFIGURATION, never from the request
    conflicts = [_conflict(world, c, "machine_not_configured",
                           f"Machine '{c}' is not configured at Station 2.")
                 for c in machine_codes if c not in world.machines]
    if conflicts:
        log.fail(f"cycle start: unconfigured machine(s) "
                 f"{[c['machineCode'] for c in conflicts]}")
        return _start_reject(world, req, po, "machine_unavailable",
                             "One or more requested machines are not configured.", conflicts)
    families = {world.machines[c]["family"] for c in machine_codes}
    if len(families) > 1:
        return _start_reject(world, req, po, "validation_failed",
                             f"Requested machines span families {sorted(families)}; "
                             f"one start request addresses one family.")
    family = families.pop()
    log.step(f"cycle start: family resolved from configuration -> {family} "
             f"(machines {machine_codes})")

    if family == "Hopper":
        return _start_hopper(world, log, req, session, po, machine_codes,
                             collection_ids, premix_ids)
    return _start_extruder_rajoo(world, log, req, session, po, family,
                                 machine_codes, collection_ids, premix_ids)


def _start_hopper(world, log, req, session, po, machine_codes, collection_ids, premix_ids):
    if premix_ids:
        return _start_reject(world, req, po, "validation_failed",
                             "preMixIds must be empty on a Hopper start.")
    if len(collection_ids) != 1:
        return _start_reject(world, req, po, "validation_failed",
                             "A Hopper start names exactly one collection.")
    col = world.collections.get(collection_ids[0])
    if not col:
        return _start_reject(world, req, po, "not_found",
                             f"Collection '{collection_ids[0]}' was not found.")
    if str(col["productionOrderDocumentNumber"]) != po:
        return _start_reject(world, req, po, "validation_failed",
                             f"Collection {col['collectionId']} belongs to job "
                             f"{col['productionOrderDocumentNumber']}, not {po}.")

    premix = world.premixes.get(col["linkedPreMixId"]) if col["linkedPreMixId"] else None
    if col["status"] == "ReadyForRouting":
        pass  # first routing
    elif col["status"] == "Routed" and premix and premix["status"] == "Mixing":
        pass  # joining the active shared pre-mix
    elif col["status"] == "Routed" and premix and premix["status"] != "Mixing":
        return _start_reject(world, req, po, "state_conflict",
                             f"Pre-mix {premix['preMixId']} is {premix['status']}; a completed "
                             f"shared pre-mix cannot receive another Hopper.")
    else:
        return _start_reject(world, req, po, "state_conflict",
                             f"Collection {col['collectionId']} is {col['status']}; it must be "
                             f"ReadyForRouting or Routed to an active shared pre-mix.")

    # classify requested hoppers: already-active on THIS premix vs new claims
    already_active, new_codes, conflicts = [], [], []
    for code in machine_codes:
        m = world.machines[code]
        if m["status"] == "Inactive":
            conflicts.append(_conflict(world, code, "machine_inactive",
                                       f"{m['displayName']} is inactive"
                                       + (f": {m['inactiveReason']}" if m["inactiveReason"] else ".")))
        elif m["status"] == "InUse":
            if premix and m["preMixId"] == premix["preMixId"]:
                already_active.append(code)
            else:
                conflicts.append(_conflict(world, code, "machine_in_use",
                                           f"{m['displayName']} is already in use by another collection."))
        else:
            new_codes.append(code)

    if conflicts:
        # atomic: one invalid code means none of the new codes are assigned
        log.fail(f"hopper start: {len(conflicts)} conflict(s) "
                 f"({[c['machineCode'] for c in conflicts]}) -> nothing assigned (atomic)")
        return _start_reject(world, req, po, "machine_unavailable",
                             "One or more requested Hoppers are unavailable; "
                             "no new Hopper was assigned.", conflicts)

    operator = world.operator_by_id(session["operatorId"])
    route_ids = []
    if premix is None:
        premix_id = world.next_id("PMX")
        route_id = world.next_id("ROUTE")
        premix = {
            "preMixId": premix_id, "collectionId": col["collectionId"],
            "jobCardNumber": col["jobCardNumber"],
            "productionOrderDocumentNumber": po,
            "status": "Mixing", "cycleIds": [], "routeId": route_id,
        }
        world.premixes[premix_id] = premix
        col["linkedPreMixId"] = premix_id
        col["status"] = "Routed"
        route_ids.append(route_id)
        log.transition(f"collection {col['collectionId']}: ReadyForRouting -> Routed; "
                       f"shared pre-mix {premix_id} created atomically with first Hopper "
                       f"assignment (route {route_id})")

    cycles_out = []
    for code in already_active:
        m = world.machines[code]
        cyc = world.cycles[m["cycleId"]]
        cycles_out.append({"cycleId": cyc["cycleId"], "machineCode": code,
                           "machineFamily": "Hopper", "alreadyActive": True,
                           "startedAtUtc": cyc["startedAtUtc"]})
        log.warn(f"hopper {code} already active on {premix['preMixId']} "
                 f"(cycle {cyc['cycleId']}) -> alreadyActive: true, no duplicate assignment")
    for code in new_codes:
        cycle_id = world.next_id("CYC")
        cyc = {"cycleId": cycle_id, "machineCode": code, "machineFamily": "Hopper",
               "collectionId": col["collectionId"], "preMixId": premix["preMixId"],
               "runId": None, "productionOrderDocumentNumber": po,
               "active": True, "startedAtUtc": iso(utc_now()), "finishedAtUtc": None}
        world.cycles[cycle_id] = cyc
        premix["cycleIds"].append(cycle_id)
        _assign_machine(world, world.machines[code], cycle_id, col["collectionId"],
                        premix["preMixId"], None, col["jobCardNumber"], req, session, operator)
        cycles_out.append({"cycleId": cycle_id, "machineCode": code,
                           "machineFamily": "Hopper", "alreadyActive": False,
                           "startedAtUtc": cyc["startedAtUtc"]})
        log.transition(f"hopper {code}: Available -> InUse "
                       f"(cycle {cycle_id}, pre-mix {premix['preMixId']}, "
                       f"collection {col['collectionId']}, job {col['jobCardNumber']})")

    return build_response(world, req, next_action="scan_same_machine_to_finish",
                          correlation=col["collectionId"], response_extras={
        "machineFamily": "Hopper",
        "productionOrderDocumentNumber": po,
        "collectionIds": [col["collectionId"]],
        "preMixIds": [],
        "linkedPreMixId": premix["preMixId"],
        "runId": None,
        "cycles": cycles_out,
        "routeIds": route_ids,
        "allocationIds": [],
        "conflicts": [],
        "hoppers": world.hopper_board(),
    })


def _start_extruder_rajoo(world, log, req, session, po, family, machine_codes,
                          collection_ids, premix_ids):
    if len(machine_codes) != 1:
        return _start_reject(world, req, po, "validation_failed",
                             f"A {family} start names exactly one machine code.")
    code = machine_codes[0]
    m = world.machines[code]
    if not collection_ids and not premix_ids:
        return _start_reject(world, req, po, "validation_failed",
                             "At least one source (collection or pre-mix) is required.")

    # validate the whole source bundle before persisting anything (atomic)
    sources = []
    for cid in collection_ids:
        col = world.collections.get(cid)
        if not col:
            return _start_reject(world, req, po, "not_found", f"Collection '{cid}' was not found.")
        if str(col["productionOrderDocumentNumber"]) != po:
            return _start_reject(world, req, po, "validation_failed",
                                 f"Collection {cid} belongs to job "
                                 f"{col['productionOrderDocumentNumber']}, not {po}.")
        if col["status"] != "ReadyForRouting":
            return _start_reject(world, req, po, "state_conflict",
                                 f"Collection {cid} is {col['status']}; it must be ReadyForRouting.")
        sources.append(("collection", col))
    for pid in premix_ids:
        pm = world.premixes.get(pid)
        if not pm:
            return _start_reject(world, req, po, "not_found", f"Pre-mix '{pid}' was not found.")
        if str(pm["productionOrderDocumentNumber"]) != po:
            return _start_reject(world, req, po, "validation_failed",
                                 f"Pre-mix {pid} belongs to job "
                                 f"{pm['productionOrderDocumentNumber']}, not {po}.")
        if pm["status"] != "ReadyForAllocation":
            return _start_reject(world, req, po, "state_conflict",
                                 f"Pre-mix {pid} is {pm['status']}; it must be ReadyForAllocation.")
        sources.append(("premix", pm))
    for kind, src in sources:
        src_id = src["collectionId"] if kind == "collection" else src["preMixId"]
        for a in world.allocations.values():
            if a["status"] == "Active" and a["sourceId"] == src_id:
                return _start_reject(world, req, po, "state_conflict",
                                     f"{kind} {src_id} already has an active allocation "
                                     f"({a['allocationId']}).")

    if m["status"] == "Inactive":
        return _start_reject(world, req, po, "machine_unavailable",
                             f"{m['displayName']} is inactive.",
                             [_conflict(world, code, "machine_inactive",
                                        f"{m['displayName']} is inactive.")])
    run = world.runs.get(m["runId"]) if m["runId"] else None
    if m["status"] == "InUse":
        if not (run and str(run["productionOrderDocumentNumber"]) == po):
            return _start_reject(world, req, po, "machine_unavailable",
                                 f"{m['displayName']} is busy with another job.",
                                 [_conflict(world, code, "machine_in_use",
                                            f"{m['displayName']} is already in use.")])
        cycle = world.cycles[m["cycleId"]]
        log.step(f"{code} already running job {po} (run {run['runId']}) -> adding sources")
        already_active = True
    else:
        run_id = world.next_id("RUN")
        cycle_id = world.next_id("CYC")
        run = {"runId": run_id, "machineCode": code, "machineFamily": family,
               "productionOrderDocumentNumber": po, "active": True,
               "allocationIds": [], "cycleId": cycle_id}
        cycle = {"cycleId": cycle_id, "machineCode": code, "machineFamily": family,
                 "collectionId": None, "preMixId": None, "runId": run_id,
                 "productionOrderDocumentNumber": po,
                 "active": True, "startedAtUtc": iso(utc_now()), "finishedAtUtc": None}
        world.runs[run_id] = run
        world.cycles[cycle_id] = cycle
        operator = world.operator_by_id(session["operatorId"])
        _assign_machine(world, m, cycle_id, None, None, run_id, po, req, session, operator)
        already_active = False
        log.transition(f"{family.lower()} {code}: Available -> InUse "
                       f"(run {run_id}, cycle {cycle_id}, job {po})")

    allocation_ids = []
    for kind, src in sources:
        alloc_id = world.next_id("ALLOC")
        src_id = src["collectionId"] if kind == "collection" else src["preMixId"]
        world.allocations[alloc_id] = {
            "allocationId": alloc_id, "runId": run["runId"], "machineCode": code,
            "sourceType": "Collection" if kind == "collection" else "PreMix",
            "sourceId": src_id, "productionOrderDocumentNumber": po,
            "status": "Active", "quantity": None, "palletRfidTag": None,
        }
        run["allocationIds"].append(alloc_id)
        allocation_ids.append(alloc_id)
        if kind == "collection":
            src["status"] = "Routed"
            log.transition(f"collection {src_id}: ReadyForRouting -> Routed "
                           f"(direct {family} allocation {alloc_id}; NO pre-mix created)")
        else:
            src["status"] = "Allocated"
            log.transition(f"pre-mix {src_id}: ReadyForAllocation -> Allocated "
                           f"(allocation {alloc_id} on {code})")

    return build_response(world, req, next_action="scan_same_machine_to_finish",
                          response_extras={
        "machineFamily": family,
        "productionOrderDocumentNumber": po,
        "collectionIds": collection_ids,
        "preMixIds": premix_ids,
        "linkedPreMixId": None,
        "runId": run["runId"],
        "cycles": [{"cycleId": cycle["cycleId"], "machineCode": code,
                    "machineFamily": family, "alreadyActive": already_active,
                    "startedAtUtc": cycle["startedAtUtc"]}],
        "routeIds": [],
        "allocationIds": allocation_ids,
        "conflicts": [],
        "hoppers": world.hopper_board(),
    })


# ----------------------------------------------------------------- finish ----
def _lifecycle_fields(world, cyc, already_finished, is_complete, premix_status,
                      remaining):
    return {
        "machineFamily": cyc["machineFamily"],
        "machineCode": cyc["machineCode"],
        "cycleId": cyc["cycleId"],
        "alreadyFinished": already_finished,
        "productionOrderDocumentNumber": cyc["productionOrderDocumentNumber"],
        "collectionId": cyc["collectionId"],
        "linkedPreMixId": cyc["preMixId"],
        "runId": cyc["runId"],
        "isComplete": is_complete,
        "preMixStatus": premix_status,
        "remainingActiveCycles": remaining,
        "hoppers": world.hopper_board(),
    }


def _job_has_active_run(world, po):
    return any(r["active"] and str(r["productionOrderDocumentNumber"]) == str(po)
               for r in world.runs.values())


def _do_finish(world, log, req, session, forced, approver):
    machine_code = req.get("machineCode")
    cycle_id = req.get("cycleId")
    if not machine_code or not cycle_id:
        raise Rejection("validation_failed", "Both machineCode and cycleId are required.")
    cyc = world.cycles.get(cycle_id)
    if not cyc:
        raise Rejection("not_found", f"Cycle '{cycle_id}' was not found.")
    if cyc["machineCode"] != machine_code:
        raise Rejection("validation_failed",
                        f"Cycle {cycle_id} belongs to machine {cyc['machineCode']}, "
                        f"not {machine_code}.")

    verb = "force-close" if forced else "finish"
    if not cyc["active"]:
        # A machine is reused; a cycle never is. A stale duplicate scan is a
        # true accepted no-op and NEVER touches the machine's newer cycle.
        log.warn(f"{verb} {cycle_id} on {machine_code}: cycle already finished at "
                 f"{cyc['finishedAtUtc']} -> accepted no-op (alreadyFinished: true); "
                 f"the machine's current cycle is untouched")
        premix = world.premixes.get(cyc["preMixId"]) if cyc["preMixId"] else None
        extras = _lifecycle_fields(world, cyc, True,
                                   premix["status"] != "Mixing" if premix else True,
                                   premix["status"] if premix else None, [])
        if forced:
            extras["forceClosed"] = False
            extras.update(approver)
        return build_response(world, req, correlation=cyc["preMixId"] or cycle_id,
                              response_extras=extras)

    cyc["active"] = False
    cyc["finishedAtUtc"] = iso(utc_now())
    m = world.machines[machine_code]
    _release_machine(world, m)

    if cyc["machineFamily"] == "Hopper":
        premix = world.premixes[cyc["preMixId"]]
        remaining = [{"cycleId": c, "machineCode": world.cycles[c]["machineCode"]}
                     for c in premix["cycleIds"] if world.cycles[c]["active"]]
        log.transition(f"hopper {machine_code}: InUse -> Available "
                       f"(cycle {cycle_id} {verb}ed; only this Hopper is released)")
        if remaining:
            next_action = "assign_or_finish_hopper"
            log.ok(f"pre-mix {premix['preMixId']} still Mixing: "
                   f"{len(remaining)} active Hopper cycle(s) remain "
                   f"({[r['machineCode'] for r in remaining]})")
            extras = _lifecycle_fields(world, cyc, False, False, "Mixing", remaining)
        else:
            premix["status"] = "ReadyForAllocation"
            next_action = "allocate_premix"
            log.transition(f"pre-mix {premix['preMixId']}: Mixing -> ReadyForAllocation "
                           f"(final active Hopper cycle ended; ingredient completeness "
                           f"inherited from the routed collection — no re-check; "
                           f"no SAP Issue queued)")
            extras = _lifecycle_fields(world, cyc, False, True, "ReadyForAllocation", [])
    else:
        run = world.runs[cyc["runId"]]
        run["active"] = False
        for alloc_id in run["allocationIds"]:
            a = world.allocations[alloc_id]
            if a["status"] == "Active":
                a["status"] = "Completed"
                log.transition(f"allocation {alloc_id} ({a['sourceType']} {a['sourceId']}) "
                               f"-> Completed")
        log.transition(f"{cyc['machineFamily'].lower()} {machine_code}: InUse -> Available "
                       f"(run {run['runId']} {verb}ed; parent local job stays open)")
        po = cyc["productionOrderDocumentNumber"]
        next_action = ("complete_station2_work"
                       if not _job_has_active_run(world, po) else "")
        extras = _lifecycle_fields(world, cyc, False, True, None, [])

    if forced:
        extras["forceClosed"] = True
        extras.update(approver)
    return build_response(world, req, next_action=next_action,
                          correlation=cyc["collectionId"] or cyc["runId"] or cycle_id,
                          response_extras=extras)


def finish(world, log, req, session):
    return _do_finish(world, log, req, session, forced=False, approver=NO_APPROVER)


def force_close(world, log, req, session):
    approver = approve(world, log, req, "machine_force_close")
    return _do_finish(world, log, req, session, forced=True, approver=approver)
