"""mixing_overview_requested -> mixing_overview_result
machine_cycle_start_requested / machine_cycle_finish_requested /
machine_cycle_force_close_requested -> machine_cycle_result

Contract v4.0 §6-§10. Business rejections come back fully formed with a
refreshed areaStatus; envelope/session failures never reach these handlers
and carry no operational area data."""

from envelope import NO_APPROVER, Rejection, approve, build_response
from handlers.common import EPS, r3
from state import iso, utc_now

LEGACY_FIELDS = ("machineCodes", "collectionIds", "preMixId", "preMixIds")


# ------------------------------------------------------------ payloads ----
def _equipment_payload(eq):
    return {
        "mixingArea": eq["mixingArea"],
        "equipmentRole": eq["equipmentRole"],
        "machineCode": eq["machineCode"],
        "displayName": eq["displayName"],
        "isEnabled": eq["isEnabled"],
        "isAvailable": eq["isEnabled"] and eq["status"] == "Available",
        "status": eq["status"],
        "productLayer": eq["productLayer"],
        "currentCycleId": eq["currentCycleId"],
        "currentProductionOrderDocumentNumber": eq["currentProductionOrderDocumentNumber"],
        "currentMixBatchIds": list(eq["currentMixBatchIds"]),
        "validDestinationMachineCodes": list(eq["validDestinationMachineCodes"]),
        "routeDescription": eq["routeDescription"],
    }


def valid_next_machine_codes(world, mix):
    """Server-authoritative destinations for one ReadyForProduction mix."""
    mixer = world.equipment.get(mix["mixerCode"])
    dests = list(mixer["validDestinationMachineCodes"]) if mixer else []
    if mix["mixingArea"] == "JandiBulkMixing":
        if mix["drumCompleted"]:
            return ["JAN-04"]
        return ["JAN-DRUM-01"] + [d for d in dests if d != "JAN-04"]
    return dests


def _mix_payload(world, mix):
    nexts = valid_next_machine_codes(world, mix)
    return {
        "mixBatchId": mix["mixBatchId"],
        "collectionId": mix["collectionId"],
        "mixingArea": mix["mixingArea"],
        "productionOrderDocumentNumber": mix["productionOrderDocumentNumber"],
        "mixerCode": mix["mixerCode"],
        "mixerDisplayName": mix["mixerDisplayName"],
        "productLayer": mix["productLayer"],
        "status": mix["status"],
        "plannedDestinationMachineCode": mix["plannedDestinationMachineCode"],
        "validNextMachineCodes": nexts,
        "nextStepDescription": f"Start one of: {', '.join(nexts)}." if nexts else "",
    }


def _cycle_payload(world, c):
    return {
        "cycleId": c["cycleId"],
        "machineCode": c["machineCode"],
        "mixingArea": c["mixingArea"],
        "equipmentRole": c["equipmentRole"],
        "productionOrderDocumentNumber": c["productionOrderDocumentNumber"],
        "collectionId": c["collectionId"],
        "mixBatchIds": list(c["mixBatchIds"]),
        "productionRunId": c["runId"],
        "startedAtUtc": c["startedAtUtc"],
        "startedByOperatorId": c["startedByOperatorId"],
    }


def _run_payload(world, r):
    return {
        "productionRunId": r["productionRunId"],
        "machineCode": r["machineCode"],
        "productionOrderDocumentNumber": r["productionOrderDocumentNumber"],
        "mixBatchIds": list(r["mixBatchIds"]),
        "startedAtUtc": r["startedAtUtc"],
    }


def area_overview(world, area=None, po=None):
    """The board (§7/§8): equipment, active cycles, ready mixes, active runs.
    area=None means all five areas; po filters mixes/cycles/runs, never equipment."""
    def in_scope(item_area):
        return area is None or item_area == area

    def po_ok(item_po):
        return po is None or str(item_po) == str(po)

    return {
        "accepted": True,
        "mixingArea": area,
        "productionOrderDocumentNumber": po,
        "equipment": [_equipment_payload(e) for e in world.equipment.values()
                      if in_scope(e["mixingArea"])],
        "activeCycles": [_cycle_payload(world, c) for c in world.cycles.values()
                         if c["active"] and in_scope(c["mixingArea"])
                         and po_ok(c["productionOrderDocumentNumber"])],
        "readyMixes": [_mix_payload(world, m) for m in world.mix_batches.values()
                       if m["status"] == "ReadyForProduction"
                       and m["assignedToCycleId"] is None
                       and in_scope(m["mixingArea"])
                       and po_ok(m["productionOrderDocumentNumber"])],
        "activeRuns": [_run_payload(world, r) for r in world.runs.values()
                       if r["active"] and in_scope(world.equipment[r["machineCode"]]["mixingArea"])
                       and po_ok(r["productionOrderDocumentNumber"])],
    }


# ------------------------------------------------------- unified result ----
def _machine_result(world, req, *, accepted=True, error_code=None, reason=None,
                    next_action="", action=None, eq=None, cycle_id=None, po=None,
                    collection_id=None, mix_batch_id=None, run_id=None, affected=None,
                    already_finished=False, force_closed=False, approver=None,
                    correlation=None):
    area = eq["mixingArea"] if eq else None
    extras = {
        "action": action,
        "mixingArea": area,
        "equipmentRole": eq["equipmentRole"] if eq else None,
        "machineCode": eq["machineCode"] if eq else req.get("machineCode"),
        "cycleId": cycle_id,
        "productionOrderDocumentNumber": (str(po) if po is not None
                                          else req.get("productionOrderDocumentNumber")),
        "collectionId": collection_id,
        "mixBatchId": mix_batch_id,
        "productionRunId": run_id,
        "affectedMixBatchIds": list(affected or []),
        "alreadyFinished": already_finished,
        "forceClosed": force_closed,
        "sapIssueQueued": False,
        "sapProductionOrderChanged": False,
        "areaStatus": area_overview(world, area),
    }
    extras.update(approver or NO_APPROVER)
    return build_response(world, req, accepted=accepted, error_code=error_code,
                          reason=reason, next_action=next_action,
                          correlation=correlation, response_extras=extras)


def _mreject(world, log, req, eq, error_code, reason, next_action=""):
    log.fail(f"machine cycle rejected ({error_code}): {reason}")
    return _machine_result(world, req, accepted=False, error_code=error_code,
                           reason=reason, next_action=next_action, eq=eq)


# ------------------------------------------------------------ overview ----
def overview(world, log, req, session):
    area = req.get("mixingArea")
    if area is not None and area not in world.MIXING_AREAS:
        raise Rejection("invalid_mixing_area",
                        f"'{area}' is not one of the five fixed mixing areas.")
    po = req.get("productionOrderDocumentNumber")
    ov = area_overview(world, area, po)
    log.ok(f"mixing overview: area={area or 'ALL'} po={po or 'ALL'} — "
           f"{len(ov['equipment'])} equipment, {len(ov['activeCycles'])} active cycles, "
           f"{len(ov['readyMixes'])} ready mixes, {len(ov['activeRuns'])} active runs")
    extras = dict(ov)
    del extras["accepted"]  # build_response owns the envelope's accepted flag
    return build_response(world, req, next_action="select_collection_mix_or_machine",
                          response_extras=extras)


# --------------------------------------------------------------- start ----
def start(world, log, req, session):
    present = [f for f in LEGACY_FIELDS if f in req]
    if present:
        return _mreject(world, log, req, None, "legacy_request_shape",
                        f"v3 field(s) {', '.join(present)} are rejected in a 4.0 mixing "
                        f"request. Remove them and resend with a NEW messageId.")
    code = req.get("machineCode")
    if not code:
        return _mreject(world, log, req, None, "validation_failed",
                        "machineCode is required.")
    eq = world.equipment.get(code)
    if not eq or not eq["isEnabled"] or eq["status"] == "Disabled":
        return _mreject(world, log, req, None, "unknown_or_disabled_equipment",
                        f"Machine '{code}' is unknown or disabled.")
    po = req.get("productionOrderDocumentNumber")
    if not po:
        return _mreject(world, log, req, eq, "validation_failed",
                        "productionOrderDocumentNumber is required.")
    role = eq["equipmentRole"]
    if role == "Mixer":
        return _start_mixer(world, log, req, session, eq, po)
    if role == "Transfer":
        return _start_drum(world, log, req, session, eq, po)
    return _start_production(world, log, req, session, eq, po)


def _start_mixer(world, log, req, session, eq, po):
    if req.get("mixBatchIds"):
        return _mreject(world, log, req, eq, "validation_failed",
                        "A mixer start sends exactly one collectionId and no mixBatchIds.")
    col_id = req.get("collectionId")
    if not col_id:
        return _mreject(world, log, req, eq, "validation_failed",
                        "collectionId is required on a mixer start.")
    if eq["status"] == "InUse":
        return _mreject(world, log, req, eq, "equipment_in_use",
                        f"{eq['machineCode']} is busy on cycle {eq['currentCycleId']}.")
    col = world.collections.get(col_id)
    if not col:
        return _mreject(world, log, req, eq, "source_not_found",
                        f"Collection '{col_id}' was not found.")
    if col["jobCardNumber"] != str(po):
        return _mreject(world, log, req, eq, "job_card_mismatch",
                        f"Collection {col_id} belongs to JC {col['jobCardNumber']}, not {po}.")
    if col["status"] != "ReadyForMixing":
        if col["claimedByMixBatchId"]:
            return _mreject(world, log, req, eq, "source_already_assigned",
                            f"Collection {col_id} was already claimed by "
                            f"{col['claimedByMixBatchId']}. Each collection is claimed once, ever.")
        return _mreject(world, log, req, eq, "source_not_ready",
                        f"Collection {col_id} is {col['status']}; it must be ReadyForMixing.")

    layer_inputs = None
    if eq["mixingArea"] == "RajooMachineMixing":
        layer_inputs, err = _validate_layer_inputs(req, col)
        if err:
            return _mreject(world, log, req, eq, "invalid_layer_inputs", err)
    elif "layerInputs" in req:
        return _mreject(world, log, req, eq, "validation_failed",
                        "layerInputs are only valid on a Rajoo gravimetric mixer start.")

    mix_id = world.next_id("MIX")
    cyc_id = world.next_id("CYC")
    dests = eq["validDestinationMachineCodes"]
    world.mix_batches[mix_id] = {
        "mixBatchId": mix_id,
        "collectionId": col_id,
        "mixingArea": eq["mixingArea"],
        "productionOrderDocumentNumber": str(po),
        "mixerCode": eq["machineCode"],
        "mixerDisplayName": eq["displayName"],
        "productLayer": eq["productLayer"],
        "status": "Mixing",
        "plannedDestinationMachineCode": dests[0] if len(dests) == 1 else None,
        "assignedToCycleId": None,
        "drumCompleted": False,
        "layerInputs": layer_inputs,
    }
    world.cycles[cyc_id] = _new_cycle(cyc_id, eq, po, session, req,
                                      collection_id=col_id, mix_batch_ids=[mix_id])
    col["claimedByMixBatchId"] = mix_id
    col["status"] = "Mixing"
    _occupy(eq, cyc_id, po, [mix_id])
    log.transition(f"mixer start: {eq['machineCode']} claimed {col_id} -> {mix_id} / {cyc_id} "
                   f"(operator {session['operatorId']}, device {req['deviceId']})")
    return _machine_result(world, req, action="Started", eq=eq, cycle_id=cyc_id, po=po,
                           collection_id=col_id, mix_batch_id=mix_id, affected=[mix_id],
                           next_action="scan_same_machine_to_finish", correlation=mix_id)


def _validate_layer_inputs(req, col):
    inputs = req.get("layerInputs")
    if not isinstance(inputs, list) or not (1 <= len(inputs) <= 5):
        return None, "layerInputs must contain one to five material/dose lines."
    collected = {l["materialCode"]: l["collectedQuantity"]
                 for l in col["lines"] if l["requiresIngredientCollection"]}
    for item in inputs:
        if not isinstance(item, dict):
            return None, "Each layer input must be an object with materialCode and dosingQuantity."
        mat = item.get("materialCode")
        dose = item.get("dosingQuantity")
        if not mat or not isinstance(dose, (int, float)) or dose <= 0:
            return None, "Each layer input needs a materialCode and a positive dosingQuantity."
        if mat not in collected:
            return None, f"Material {mat} was not collected in this collection."
        if dose > collected[mat] + EPS:
            return None, (f"Dose {r3(dose)} of {mat} exceeds its collected "
                          f"quantity {r3(collected[mat])}.")
    return inputs, None


def _start_drum(world, log, req, session, eq, po):
    if req.get("collectionId"):
        return _mreject(world, log, req, eq, "validation_failed",
                        "A drum start takes exactly one completed mixBatchId, never a collection.")
    ids = req.get("mixBatchIds")
    if not isinstance(ids, list) or len(ids) != 1:
        return _mreject(world, log, req, eq, "validation_failed",
                        "A drum start accepts exactly one completed JANDI mixBatchId.")
    if eq["status"] == "InUse":
        return _mreject(world, log, req, eq, "equipment_in_use",
                        f"{eq['machineCode']} is busy on cycle {eq['currentCycleId']}.")
    mix = world.mix_batches.get(ids[0])
    if not mix:
        return _mreject(world, log, req, eq, "source_not_found",
                        f"Mix '{ids[0]}' was not found.")
    if mix["productionOrderDocumentNumber"] != str(po):
        return _mreject(world, log, req, eq, "job_card_mismatch",
                        f"Mix {ids[0]} belongs to JC {mix['productionOrderDocumentNumber']}.")
    if mix["mixingArea"] != "JandiBulkMixing":
        return _mreject(world, log, req, eq, "invalid_route",
                        f"Mix {ids[0]} is a {mix['mixingArea']} mix; the drum serves JANDI only.")
    if mix["status"] != "ReadyForProduction":
        return _mreject(world, log, req, eq, "source_not_ready",
                        f"Mix {ids[0]} is {mix['status']}; it must be ReadyForProduction.")
    if mix["assignedToCycleId"]:
        return _mreject(world, log, req, eq, "source_already_assigned",
                        f"Mix {ids[0]} is already assigned to {mix['assignedToCycleId']}.")
    if mix["drumCompleted"]:
        return _mreject(world, log, req, eq, "invalid_route",
                        f"The drum cycle for {ids[0]} is already complete; start JANDI 4.")
    cyc_id = world.next_id("CYC")
    world.cycles[cyc_id] = _new_cycle(cyc_id, eq, po, session, req, mix_batch_ids=list(ids))
    mix["assignedToCycleId"] = cyc_id
    _occupy(eq, cyc_id, po, list(ids))
    log.transition(f"drum start: {eq['machineCode']} cycle {cyc_id} on {ids[0]}")
    return _machine_result(world, req, action="Started", eq=eq, cycle_id=cyc_id, po=po,
                           mix_batch_id=ids[0], affected=list(ids),
                           next_action="scan_same_machine_to_finish", correlation=cyc_id)


def _start_production(world, log, req, session, eq, po):
    if req.get("collectionId"):
        return _mreject(world, log, req, eq, "validation_failed",
                        "No collection/pallet/bag ID is valid in a downstream start.")
    ids = req.get("mixBatchIds")
    if not isinstance(ids, list) or not ids:
        return _mreject(world, log, req, eq, "validation_failed",
                        "One or more completed same-JC mixBatchIds are required.")
    # Resolve and validate ALL mixes first — a single failure rejects the whole request.
    code = eq["machineCode"]
    for mid in ids:
        mix = world.mix_batches.get(mid)
        if not mix:
            return _mreject(world, log, req, eq, "source_not_found",
                            f"Mix '{mid}' was not found.")
        if mix["productionOrderDocumentNumber"] != str(po):
            return _mreject(world, log, req, eq, "job_card_mismatch",
                            f"Mix {mid} belongs to JC {mix['productionOrderDocumentNumber']}, "
                            f"not {po}. Remove mixes from other JCs.")
        if mix["status"] != "ReadyForProduction":
            return _mreject(world, log, req, eq, "source_not_ready",
                            f"Mix {mid} is {mix['status']}; it must be ReadyForProduction.")
        if mix["assignedToCycleId"]:
            return _mreject(world, log, req, eq, "source_already_assigned",
                            f"Mix {mid} is already assigned to {mix['assignedToCycleId']}.")
        allowed = valid_next_machine_codes(world, mix)
        if code not in allowed:
            if (mix["mixingArea"] == "JandiBulkMixing" and code == "JAN-04"
                    and not mix["drumCompleted"]):
                return _mreject(world, log, req, eq, "drum_cycle_required",
                                f"Start and finish {'JAN-DRUM-01'} before JANDI 4 for {mid}.")
            return _mreject(world, log, req, eq, "invalid_route",
                            f"{code} is not a valid destination for {mid}; "
                            f"valid: {', '.join(allowed)}.")
    if eq["status"] == "InUse":
        run = world.runs.get(eq["currentCycleId"])
        if run and run["active"] and run["productionOrderDocumentNumber"] == str(po):
            # Accumulate additional same-JC mixes into the active run.
            run["mixBatchIds"].extend(ids)
            for mid in ids:
                world.mix_batches[mid]["assignedToCycleId"] = run["productionRunId"]
            world.cycles[run["productionRunId"]]["mixBatchIds"].extend(ids)
            eq["currentMixBatchIds"].extend(ids)
            log.transition(f"production run {run['productionRunId']} on {code}: "
                           f"accumulated {', '.join(ids)}")
            return _machine_result(world, req, action="Started", eq=eq,
                                   cycle_id=run["productionRunId"], po=po,
                                   run_id=run["productionRunId"], affected=list(ids),
                                   next_action="scan_same_machine_to_finish",
                                   correlation=run["productionRunId"])
        return _mreject(world, log, req, eq, "equipment_in_use",
                        f"{code} is busy on another job card.")
    run_id = world.next_id("RUN")
    world.runs[run_id] = {
        "productionRunId": run_id,
        "machineCode": code,
        "productionOrderDocumentNumber": str(po),
        "mixBatchIds": list(ids),
        "active": True,
        "startedAtUtc": iso(utc_now()),
    }
    # A production cycle's durable id IS the run id (§8: cycleId == productionRunId).
    world.cycles[run_id] = _new_cycle(run_id, eq, po, session, req,
                                      mix_batch_ids=list(ids), run_id=run_id)
    for mid in ids:
        world.mix_batches[mid]["assignedToCycleId"] = run_id
    _occupy(eq, run_id, po, list(ids))
    log.transition(f"production start: {code} run {run_id} consuming {', '.join(ids)}")
    return _machine_result(world, req, action="Started", eq=eq, cycle_id=run_id, po=po,
                           run_id=run_id, affected=list(ids),
                           next_action="scan_same_machine_to_finish", correlation=run_id)


def _new_cycle(cyc_id, eq, po, session, req, collection_id=None, mix_batch_ids=None,
               run_id=None):
    return {
        "cycleId": cyc_id,
        "machineCode": eq["machineCode"],
        "mixingArea": eq["mixingArea"],
        "equipmentRole": eq["equipmentRole"],
        "productionOrderDocumentNumber": str(po),
        "collectionId": collection_id,
        "mixBatchIds": list(mix_batch_ids or []),
        "runId": run_id,
        "active": True,
        "startedAtUtc": iso(utc_now()),
        "finishedAtUtc": None,
        "forceClosed": False,
        "startedByOperatorId": session["operatorId"],
        "fromDevice": req["deviceId"],
    }


def _occupy(eq, cyc_id, po, mix_ids):
    eq["status"] = "InUse"
    eq["currentCycleId"] = cyc_id
    eq["currentProductionOrderDocumentNumber"] = str(po)
    eq["currentMixBatchIds"] = list(mix_ids)


# ------------------------------------------------- finish / force-close ----
def _resolve(world, log, req):
    """Returns (eq, cycle, reject_response_or_None) for finish/force-close."""
    code = req.get("machineCode")
    cyc_id = req.get("cycleId")
    if not code or not cyc_id:
        return None, None, _mreject(world, log, req, None, "validation_failed",
                                    "Both machineCode and cycleId are required.")
    eq = world.equipment.get(code)
    if not eq:
        return None, None, _mreject(world, log, req, None, "unknown_or_disabled_equipment",
                                    f"Machine '{code}' is unknown.")
    cycle = world.cycles.get(cyc_id)
    if not cycle or cycle["machineCode"] != code:
        return eq, None, _mreject(world, log, req, eq, "cycle_mismatch",
                                  f"Cycle '{cyc_id}' is not a cycle of {code}. A stale "
                                  f"cycle ID can never finish a newer use of the machine.")
    return eq, cycle, None


def _apply_finish(world, log, eq, cycle, forced):
    cycle["active"] = False
    cycle["finishedAtUtc"] = iso(utc_now())
    cycle["forceClosed"] = forced
    if eq["currentCycleId"] == cycle["cycleId"]:
        eq["status"] = "Disabled" if not eq["isEnabled"] else "Available"
        eq["currentCycleId"] = None
        eq["currentProductionOrderDocumentNumber"] = None
        eq["currentMixBatchIds"] = []
    role = cycle["equipmentRole"]
    if role == "Mixer":
        mix = world.mix_batches[cycle["mixBatchIds"][0]]
        if forced:
            mix["status"] = "Cancelled"
            col = world.collections.get(cycle["collectionId"])
            if col:
                col["claimedByMixBatchId"] = None
                col["status"] = "ReadyForMixing"
                log.transition(f"force-close voided {mix['mixBatchId']}; collection "
                               f"{col['collectionId']} released back to ReadyForMixing")
        else:
            mix["status"] = "ReadyForProduction"
    elif role == "Transfer":
        mix = world.mix_batches[cycle["mixBatchIds"][0]]
        mix["assignedToCycleId"] = None
        if not forced:
            mix["drumCompleted"] = True
    else:  # ProductionMachine
        run = world.runs.get(cycle["runId"])
        if run:
            run["active"] = False
        for mid in cycle["mixBatchIds"]:
            mix = world.mix_batches.get(mid)
            if not mix:
                continue
            if forced:
                mix["assignedToCycleId"] = None  # released, not consumed
            else:
                mix["status"] = "Consumed"
    log.transition(f"cycle {cycle['cycleId']} on {eq['machineCode']} -> "
                   f"{'FORCE-CLOSED' if forced else 'Finished'}")


def _finish_next_action(cycle):
    return "" if cycle["equipmentRole"] == "ProductionMachine" \
        else "select_collection_mix_or_machine"


def finish(world, log, req, session):
    eq, cycle, rej = _resolve(world, log, req)
    if rej:
        return rej
    if not cycle["active"]:
        log.warn(f"re-finish of completed cycle {cycle['cycleId']}: idempotent no-op")
        return _machine_result(world, req, action="Finished", eq=eq,
                               cycle_id=cycle["cycleId"], po=cycle["productionOrderDocumentNumber"],
                               collection_id=cycle["collectionId"],
                               mix_batch_id=(cycle["mixBatchIds"] or [None])[0],
                               run_id=cycle["runId"], affected=cycle["mixBatchIds"],
                               already_finished=True, force_closed=cycle["forceClosed"],
                               next_action=_finish_next_action(cycle),
                               correlation=cycle["cycleId"])
    _apply_finish(world, log, eq, cycle, forced=False)
    return _machine_result(world, req, action="Finished", eq=eq, cycle_id=cycle["cycleId"],
                           po=cycle["productionOrderDocumentNumber"],
                           collection_id=cycle["collectionId"],
                           mix_batch_id=(cycle["mixBatchIds"] or [None])[0],
                           run_id=cycle["runId"], affected=cycle["mixBatchIds"],
                           next_action=_finish_next_action(cycle),
                           correlation=cycle["cycleId"])


def force_close(world, log, req, session):
    eq, cycle, rej = _resolve(world, log, req)
    if rej:
        return rej
    try:
        approver = approve(world, log, req, "machine_force_close")
    except Rejection as r:
        return _machine_result(world, req, accepted=False, error_code=r.error_code,
                               reason=r.reason, next_action=r.next_action, eq=eq,
                               cycle_id=cycle["cycleId"],
                               po=cycle["productionOrderDocumentNumber"],
                               correlation=cycle["cycleId"])
    if not cycle["active"]:
        return _machine_result(world, req, action="Finished", eq=eq,
                               cycle_id=cycle["cycleId"],
                               po=cycle["productionOrderDocumentNumber"],
                               already_finished=True, force_closed=cycle["forceClosed"],
                               approver=approver, next_action=_finish_next_action(cycle),
                               correlation=cycle["cycleId"])
    _apply_finish(world, log, eq, cycle, forced=True)
    return _machine_result(world, req, action="ForceClosed", eq=eq,
                           cycle_id=cycle["cycleId"],
                           po=cycle["productionOrderDocumentNumber"],
                           collection_id=cycle["collectionId"],
                           mix_batch_id=(cycle["mixBatchIds"] or [None])[0],
                           run_id=cycle["runId"], affected=cycle["mixBatchIds"],
                           force_closed=True, approver=approver,
                           next_action=_finish_next_action(cycle),
                           correlation=cycle["cycleId"])
