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
def _reservation_for(world, code):
    """The (plan, item) reserving this mixer for a collection, if any is still Reserved."""
    for plan in world.mix_plans.values():
        for item in plan["items"]:
            if item["machineCode"] == code and item["status"] == "Reserved":
                return plan, item
    return None, None


def _equipment_payload(world, eq):
    # 4.1 §7: a mixer reserved by a collection's saved plan is not generally Available; its status
    # is `Reserved` and it is scan-allowed for that collection. Android must not infer this locally.
    plan, item = _reservation_for(world, eq["machineCode"])
    reserved = item is not None and eq["status"] == "Available"
    status = "Reserved" if reserved else eq["status"]
    return {
        "mixingArea": eq["mixingArea"],
        "equipmentRole": eq["equipmentRole"],
        "machineCode": eq["machineCode"],
        "displayName": eq["displayName"],
        "isEnabled": eq["isEnabled"],
        "isAvailable": eq["isEnabled"] and status == "Available",
        "status": status,
        "productLayer": eq["productLayer"],
        "currentCycleId": eq["currentCycleId"],
        "currentProductionOrderDocumentNumber": eq["currentProductionOrderDocumentNumber"],
        "currentMixBatchIds": list(eq["currentMixBatchIds"]),
        "validDestinationMachineCodes": list(eq["validDestinationMachineCodes"]),
        "routeDescription": eq["routeDescription"],
        "mixPlanId": plan["mixPlanId"] if reserved else None,
        "planItemStatus": item["status"] if reserved else None,
        "reservationCollectionId": plan["collectionId"] if reserved else None,
        "reservationJobCardNumber": plan["jobCardNumber"] if reserved else None,
        "scanAllowed": reserved,
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
        # 4.1 / backend issue B2: a force-closed cycle used to still yield a usable mix.
        "completionMode": mix.get("completionMode", "Normal"),
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
        "mixingArea": area,
        "productionOrderDocumentNumber": po,
        "equipment": [_equipment_payload(world, e) for e in world.equipment.values()
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
        # 4.1: the overview is now authoritative for which collections can start a mixer.
        # Filtering active_job_cards_list to "ReadyForMixing" cannot work any more, because a
        # planned collection's status is "MixingPlanned" and that list carries no plan data.
        "readyCollections": [_ready_collection_payload(world, c, area)
                             for c in world.collections.values()
                             if _collection_selectable(world, c)
                             and po_ok(c["productionOrderDocumentNumber"])],
        "mixDestinations": [_destination_payload(world, d) for d in world.mix_destinations],
    }


def _collection_selectable(world, col):
    """A collection appears in readyCollections while it can still start a mixer: an unplanned
    ReadyForMixing collection, or a MixingPlanned collection with a reservation still remaining.
    Once every reserved mixer has been started, the collection leaves the board."""
    if col["claimedByMixBatchId"] is not None:
        return False
    status = col["status"]
    if status == "ReadyForMixing":
        return True
    if status == "MixingPlanned":
        plan = world.mix_plans.get(col["collectionId"])
        return bool(plan and plan["remainingMixerCodes"])
    return False


def _ready_collection_payload(world, col, area=None):
    """One readyCollections[] row, including its saved mixer plan when there is one."""
    plan = world.mix_plans.get(col["collectionId"])
    if not plan:
        # No plan saved yet: the operator must save one in Station 2 (WPF) before any mixer
        # scan. validMixerCodes is scoped to enabled, unreserved mixers in the requested area.
        reserved = {code for p in world.mix_plans.values()
                    for code in p["remainingMixerCodes"]}
        valid = [e["machineCode"] for e in world.equipment.values()
                 if e["equipmentRole"] == "Mixer" and e["isEnabled"]
                 and (area is None or e["mixingArea"] == area)
                 and e["machineCode"] not in reserved]
        return {
            "collectionId": col["collectionId"],
            "jobCardNumber": col["jobCardNumber"],
            "productionOrderDocumentNumber": col["productionOrderDocumentNumber"],
            "productCode": col.get("productCode", ""),
            "productName": col["productName"],
            "status": col["status"],
            "mixPlanId": None,
            "mixPlanStatus": None,
            "plannedMixerCount": 0,
            "startedMixerCount": 0,
            "remainingMixerCount": 0,
            "plannedMixerCodes": [],
            "startedMixerCodes": [],
            "remainingMixerCodes": [],
            "mixerPlanItems": [],
            "validMixerCodes": valid,
            "nextAction": "save_mixer_plan_in_station_2",
        }

    remaining = plan["remainingMixerCodes"]
    return {
        "collectionId": col["collectionId"],
        "jobCardNumber": col["jobCardNumber"],
        "productionOrderDocumentNumber": col["productionOrderDocumentNumber"],
        "productCode": col.get("productCode", ""),
        "productName": col["productName"],
        "status": col["status"],
        "mixPlanId": plan["mixPlanId"],
        "mixPlanStatus": plan["status"],
        "plannedMixerCount": len(plan["plannedMixerCodes"]),
        "startedMixerCount": len(plan["startedMixerCodes"]),
        "remainingMixerCount": len(remaining),
        "plannedMixerCodes": list(plan["plannedMixerCodes"]),
        "startedMixerCodes": list(plan["startedMixerCodes"]),
        "remainingMixerCodes": list(remaining),
        "mixerPlanItems": [dict(i) for i in plan["items"]],
        "validMixerCodes": list(remaining),
        "nextAction": ("scan_reserved_mixer:" + ",".join(remaining)) if remaining
                      else "select_collection_mix_or_machine",
    }


def _destination_payload(world, d):
    return {
        "mixBatchId": d["mixBatchId"],
        "machineCode": d["machineCode"],
        "productionRunId": d["productionRunId"],
        "linkStatus": d["linkStatus"],
        "runStatus": d["runStatus"],
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
    if col["status"] not in ("ReadyForMixing", "MixingPlanned"):
        if col["claimedByMixBatchId"]:
            return _mreject(world, log, req, eq, "source_already_assigned",
                            f"Collection {col_id} was already claimed by "
                            f"{col['claimedByMixBatchId']}. Each collection is claimed once, ever.")
        return _mreject(world, log, req, eq, "source_not_ready",
                        f"Collection {col_id} is {col['status']}; it must be ReadyForMixing.")

    # 4.1 §7: the mixer must be a Reserved item of this collection's WPF-saved plan. Without a
    # plan there is nothing to start; a mixer not (or no longer) reserved is not_in_plan.
    plan = world.mix_plans.get(col_id)
    if not plan:
        return _mreject(world, log, req, eq, "mixer_plan_required",
                        f"Collection {col_id} has no saved mixer plan. Save the collection's "
                        f"mixer plan in Station 2 before scanning a mixer.",
                        next_action="save_mixer_plan_in_station_2")
    plan_item = next((i for i in plan["items"]
                      if i["machineCode"] == eq["machineCode"] and i["status"] == "Reserved"),
                     None)
    if not plan_item:
        remaining = ", ".join(plan["remainingMixerCodes"]) or "none"
        return _mreject(world, log, req, eq, "mixer_not_in_plan",
                        f"{eq['machineCode']} is not a remaining reserved mixer for collection "
                        f"{col_id}. Scan one of: {remaining}.",
                        next_action="scan_reserved_mixer")

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
    # 4.1 §7: mark ONLY this plan item Started; the collection stays MixingPlanned while another
    # reservation remains, and leaves the board once none does. It is not single-claimed any more.
    plan_item["status"] = "Started"
    plan_item["mixBatchId"] = mix_id
    plan_item["cycleId"] = cyc_id
    plan["startedMixerCodes"].append(eq["machineCode"])
    plan["remainingMixerCodes"] = [c for c in plan["remainingMixerCodes"]
                                   if c != eq["machineCode"]]
    plan["status"] = "InProgress" if plan["remainingMixerCodes"] else "Completed"
    col["status"] = "MixingPlanned"
    _occupy(eq, cyc_id, po, [mix_id])
    log.transition(f"mixer start: {eq['machineCode']} started plan item "
                   f"{plan_item['planItemId']} of {plan['mixPlanId']} on {col_id} -> "
                   f"{mix_id} / {cyc_id} (remaining reserved: "
                   f"{', '.join(plan['remainingMixerCodes']) or 'none'}; operator "
                   f"{session['operatorId']}, device {req['deviceId']})")
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
    if len(ids) != len(set(ids)):
        return _mreject(world, log, req, eq, "validation_failed",
                        "mixBatchIds contains duplicate entries.")
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
        mix = world.mix_batches.get(cycle["mixBatchIds"][0])
        if mix:
            if forced:
                # 4.1 / backend issue B2: a force-close no longer simply voids the mix. It
                # produces a BLOCKED, Quarantined mix that is never assignable until an audited
                # Manager/Admin Release or Discard — the material physically exists and has to be
                # accounted for, which "Cancelled" quietly hid.
                mix["status"] = "Quarantined"
                mix["completionMode"] = "ForceClosed"
                col = world.collections.get(cycle["collectionId"])
                plan = world.mix_plans.get(cycle["collectionId"]) if col else None
                if plan:
                    # 4.1 §7: release only this plan item back to Reserved so its mixer can be
                    # re-scanned; the quarantined mix stays quarantined and never flows downstream.
                    for it in plan["items"]:
                        if it["cycleId"] == cycle["cycleId"]:
                            it["status"] = "Reserved"
                            it["mixBatchId"] = None
                            it["cycleId"] = None
                            code = it["machineCode"]
                            if code in plan["startedMixerCodes"]:
                                plan["startedMixerCodes"].remove(code)
                            if code not in plan["remainingMixerCodes"]:
                                plan["remainingMixerCodes"].append(code)
                    plan["status"] = "InProgress" if plan["startedMixerCodes"] else "Saved"
                    col["status"] = "MixingPlanned"
                    log.transition(f"force-close quarantined {mix['mixBatchId']} "
                                   f"(completionMode=ForceClosed, not assignable); plan "
                                   f"{plan['mixPlanId']} item for cycle {cycle['cycleId']} "
                                   f"released back to Reserved on collection {col['collectionId']}")
                elif col:
                    col["claimedByMixBatchId"] = None
                    col["status"] = "ReadyForMixing"
                    log.transition(f"force-close quarantined {mix['mixBatchId']} "
                                   f"(completionMode=ForceClosed, not assignable); collection "
                                   f"{col['collectionId']} released back to ReadyForMixing")
            else:
                mix["status"] = "ReadyForProduction"
    elif role == "Transfer":
        mix = world.mix_batches.get(cycle["mixBatchIds"][0])
        if mix:
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


def assign_destinations(world, log, req, session):
    """`mix_destination_assignment_requested` -> `mix_destination_assignment_result` (4.1).

    Phase 2 of the strict two-phase Mixing contract: one or more completed same-JC mixes to one or
    more distinct compatible production machines. This is the ONLY request that may commit a
    destination — a production-machine `machine_cycle_start_requested` is rejected with
    `destination_assignment_required` (see `start`).

    Schema 4.1 sends `mixBatchIds[]` (plural); singular `mixBatchId` is accepted as temporary 4.0
    compatibility. Validation is ATOMIC: one invalid mix or machine rejects the whole request
    rather than partially assigning, so there is never a half-assigned mix to unwind.
    """
    mix_ids = req.get("mixBatchIds")
    if mix_ids is None:
        single = req.get("mixBatchId")
        mix_ids = [single] if single else []
    codes = req.get("machineCodes") or []
    if not isinstance(mix_ids, list) or not mix_ids:
        raise Rejection("validation_failed", "One or more mixBatchIds are required.")
    if len(set(mix_ids)) != len(mix_ids):
        raise Rejection("validation_failed", "mixBatchIds must be distinct.")
    if not codes:
        raise Rejection("validation_failed", "At least one machineCode is required.")
    if len(set(codes)) != len(codes):
        raise Rejection("validation_failed", "machineCodes must be distinct.")

    # Resolve and validate every mix first — same JC, ready, not quarantined, not already assigned.
    mixes = []
    po = None
    for mid in mix_ids:
        mix = world.mix_batches.get(mid)
        if not mix:
            raise Rejection("not_found", f"Mix '{mid}' was not found.")
        # 4.1 / backend issue B2: a force-closed mix is quarantined and never assignable until an
        # audited Manager/Admin Release or Discard.
        if mix.get("completionMode") == "ForceClosed" or mix["status"] == "Quarantined":
            log.fail(f"destination assignment: {mid} is quarantined")
            raise Rejection("state_conflict",
                            f"Mix {mid} is quarantined after a force-close and cannot be "
                            f"assigned until it is released or discarded.")
        if mix["status"] not in ("ReadyForProduction", "ReadyForTransfer"):
            raise Rejection("state_conflict",
                            f"Mix {mid} is {mix['status']}; it is not ready for a destination.")
        if mix["assignedToCycleId"]:
            raise Rejection("source_already_assigned",
                            f"Mix {mid} is already assigned to {mix['assignedToCycleId']}.")
        if po is None:
            po = mix["productionOrderDocumentNumber"]
        elif mix["productionOrderDocumentNumber"] != po:
            raise Rejection("job_card_mismatch",
                            f"Mix {mid} belongs to JC {mix['productionOrderDocumentNumber']}, "
                            f"not {po}. Every selected mix must share one job card.")
        mixes.append(mix)

    # Every selected code must be a valid route for EVERY selected mix (the intersection), enabled,
    # and not busy on another job card.
    valid = None
    for mix in mixes:
        routes = set(valid_next_machine_codes(world, mix))
        valid = routes if valid is None else (valid & routes)
    valid = valid or set()
    for code in codes:
        eq = world.equipment.get(code)
        if not eq or not eq["isEnabled"]:
            raise Rejection("unknown_or_disabled_equipment",
                            f"'{code}' is not a known, enabled machine.")
        if code not in valid:
            raise Rejection("invalid_planned_destination",
                            f"'{code}' is not a valid route for every selected mix.")
        busy = next((r for r in world.runs.values()
                     if r["active"] and r["machineCode"] == code
                     and str(r["productionOrderDocumentNumber"]) != str(po)), None)
        if busy:
            raise Rejection("destination_busy",
                            f"'{code}' is busy on another job card.")

    # Everything validated — only now mutate. One run per machine consuming the whole mix set.
    assigned = []
    for code in codes:
        run_id = world.next_id("RUN")
        world.runs[run_id] = {
            "productionRunId": run_id,
            "machineCode": code,
            "productionOrderDocumentNumber": po,
            "mixBatchIds": list(mix_ids),
            "startedAtUtc": iso(utc_now()),
            "active": True,
        }
        for mid in mix_ids:
            # A mix leaves readyMixes once assigned (area_overview filters on this).
            world.mix_batches[mid]["assignedToCycleId"] = run_id
            world.mix_destinations.append({
                "mixBatchId": mid,
                "machineCode": code,
                "productionRunId": run_id,
                "linkStatus": "Active",
                "runStatus": "Running",
            })
        assigned.append({"machineCode": code, "productionRunId": run_id})
        log.transition(f"mixes {', '.join(mix_ids)} -> {code} as run {run_id}")

    log.ok(f"destination assignment: {', '.join(mix_ids)} -> {len(assigned)} machine(s)")
    return build_response(world, req, correlation=mix_ids[0], response_extras={
        "mixBatchId": mix_ids[0],
        "mixBatchIds": list(mix_ids),
        "assignedDestinations": assigned,
        "areaStatus": area_overview(world, mixes[0]["mixingArea"], po),
    })


def force_close(world, log, req, session):
    eq, cycle, rej = _resolve(world, log, req)
    if rej:
        return rej
    try:
        approver = approve(world, log, req, "machine_force_close",
                           target_id=cycle["cycleId"])
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
