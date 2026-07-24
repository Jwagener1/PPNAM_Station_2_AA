"""ingredient_scan_requested -> ingredient_scan_result

Covers ordinary bag scans, direct-weight scans, over-collection tolerance,
manager-approved overrides (reject-then-retry), and first-submission
short-bag waivers."""

from envelope import NO_APPROVER, Rejection, approve, build_response
from handlers.common import (BAG_FRACTIONS, EPS, collection_is_complete,
                             collection_summary, handheld_lines,
                             ingredients_payload, r3)
from state import World


def _result(world, req, col, accepted=True, error_code=None, reason=None,
            next_action="", approver=None, requires_approval=False, tolerance="unset"):
    extras = {
        "collectionId": col["collectionId"],
        "collectionStatus": col["status"],
        "requiresManagerApproval": requires_approval,
        "overCollectionToleranceBags": (world.config["overCollectionToleranceBags"]
                                        if tolerance == "unset" else tolerance),
        "collectionSummary": collection_summary(col),
        "ingredients": ingredients_payload(world, col),
    }
    extras.update(approver or NO_APPROVER)
    return build_response(world, req, accepted=accepted, error_code=error_code,
                          reason=reason, next_action=next_action,
                          correlation=col["collectionId"], response_extras=extras)


def _reject(world, req, col, error_code, reason, next_action="",
            requires_approval=False, tolerance="unset"):
    return _result(world, req, col, accepted=False, error_code=error_code,
                   reason=reason, next_action=next_action,
                   requires_approval=requires_approval, tolerance=tolerance)


def _find_line(col, material_code):
    """First manual line of this material with remaining requirement; falls back
    to the last matching line when all are complete (duplicate BOM rows keep
    their own lineNumber identity)."""
    matches = [l for l in handheld_lines(col) if l["materialCode"] == material_code]
    if not matches:
        return None
    for l in matches:
        if l["requiredQuantity"] - l["collectedQuantity"] > EPS:
            return l
    return matches[-1]


def _completion_check(world, log, col):
    if col["status"] == "Collecting" and collection_is_complete(col):
        col["status"] = "ReadyForMixing"
        log.transition(f"collection {col['collectionId']}: Collecting -> ReadyForMixing "
                       f"(all adjusted manual requirements satisfied)")
        return "start_mixing"
    return "scan_ingredient"


def scan(world, log, req, session):
    col_id = req.get("collectionId")
    if not col_id:
        raise Rejection("validation_failed", "collectionId is required.")
    col = world.collections.get(col_id)
    if not col:
        raise Rejection("not_found", f"Collection '{col_id}' was not found.",
                        next_action="active_job_cards")
    if col["status"] != "Collecting":
        log.fail(f"ingredient scan on {col_id}: status is {col['status']}, not Collecting")
        return _reject(world, req, col, "state_conflict",
                       f"Collection {col_id} is {col['status']}; ingredient scanning "
                       f"is only valid while Collecting.",
                       next_action="start_mixing"
                       if col["status"] == "ReadyForMixing" else "active_job_cards")

    if req.get("shortBagCount") is not None:
        return _waiver(world, log, req, session, col)
    return _ordinary_scan(world, log, req, session, col)


# ---------------------------------------------------------------- waiver ----
def _waiver(world, log, req, session, col):
    material = req.get("requestedMaterialCode")
    if not material or not str(material).strip():
        # fail-closed: a blank material code must never resolve to a line
        log.fail("short-bag waiver: requestedMaterialCode missing/blank — rejected fail-closed")
        return _reject(world, req, col, "validation_failed",
                       "requestedMaterialCode is required on a short-bag waiver.")
    count = req.get("shortBagCount")
    if not isinstance(count, (int, float)) or count <= 0:
        return _reject(world, req, col, "validation_failed",
                       "shortBagCount must be a positive number.")
    line = _find_line(col, material)
    if line is None:
        return _reject(world, req, col, "validation_failed",
                       f"Material {material} is not a manual BOM line of this collection.")
    if line["fullBagWeight"] is None:
        return _reject(world, req, col, "validation_failed",
                       f"Material {material} is a bulk line; a waiver counted in bags "
                       f"is meaningless.", tolerance=None)

    # A waiver carries manager credentials on its FIRST submission.
    try:
        approver = approve(world, log, req, "ingredient_approve_short_bag")
    except Rejection as rej:
        return _reject(world, req, col, rej.error_code, rej.reason,
                       next_action=rej.next_action,
                       requires_approval=bool(rej.extra.get("requiresManagerApproval")))

    fbw = line["fullBagWeight"]
    reduction = count * fbw
    old_required = line["requiredQuantity"]
    line["requiredQuantity"] = max(line["collectedQuantity"],
                                   r3(old_required - reduction))
    line["approvedShortBags"] = r3(line["approvedShortBags"] + count)
    log.ok(f"short-bag waiver on {col['collectionId']} line {line['lineNumber']} "
           f"({material}): {count} bag(s) × {fbw}kg = {reduction}kg waived; "
           f"required {old_required} -> {line['requiredQuantity']} "
           f"(no scanned ingredient line created)")
    next_action = _completion_check(world, log, col)
    return _result(world, req, col, approver=approver, next_action=next_action)


# ---------------------------------------------------------- ordinary scan ----
def _ordinary_scan(world, log, req, session, col):
    # 4.1: `sourceBarcode` is canonical because a source may now be a Station 3 master-batch
    # label rather than a pallet. The legacy `palletRfidTag` is still accepted from older
    # handhelds, but sending BOTH with different values is rejected — silently preferring one
    # would let a handheld believe it captured a source it did not.
    source = req.get("sourceBarcode")
    legacy = req.get("palletRfidTag")
    if source and legacy and source.strip().casefold() != legacy.strip().casefold():
        return _reject(world, req, col, "conflicting_source_barcodes",
                       "sourceBarcode and palletRfidTag identify different sources.")
    tag = source or legacy
    if not tag:
        return _reject(world, req, col, "source_barcode_required",
                       "sourceBarcode is required on an ingredient scan.")
    pallet = world.pallets.get(tag)
    if not pallet:
        return _reject(world, req, col, "not_found", f"Pallet tag '{tag}' is not known.")
    if World.pallet_recoverable(pallet):
        log.fail(f"ingredient scan: pallet {pallet['palletId']} is {pallet['palletState']} "
                 f"-> offering recovery")
        return _reject(world, req, col, "state_conflict",
                       f"Pallet {pallet['palletId']} has no Station 2 arrival record "
                       f"({pallet['palletState']}).", next_action="recover_holding")
    if pallet["palletState"] not in ("Holding", "Mixing"):
        return _reject(world, req, col, "state_conflict",
                       f"Pallet {pallet['palletId']} is {pallet['palletState']}.")
    if pallet["blocked"]:
        return _reject(world, req, col, "validation_failed",
                       f"Pallet {pallet['palletId']} is blocked from use.")

    requested = req.get("requestedMaterialCode")
    material = requested or pallet["productCode"]
    approver = None
    needs_approval_reasons = []

    if requested and requested != pallet["productCode"]:
        needs_approval_reasons.append(
            f"pallet product {pallet['productCode']} does not match requested "
            f"material {requested} (alternate product)")

    line = _find_line(col, material)
    if line is None:
        return _reject(world, req, col, "validation_failed",
                       f"Material {material} is not a manual BOM line of this collection.")

    # ---- quantity: bag-based or direct weight ----
    bag_option = req.get("bagSizeOption")
    bag_count = req.get("bagCount")
    quantity = req.get("quantity")
    if bag_option is not None or bag_count is not None:
        if quantity is not None:
            return _reject(world, req, col, "validation_failed",
                           "Send either bag fields or quantity, not both.")
        if bag_option not in BAG_FRACTIONS:
            return _reject(world, req, col, "validation_failed",
                           f"bagSizeOption must be one of {sorted(BAG_FRACTIONS)}.")
        if not isinstance(bag_count, (int, float)) or bag_count <= 0:
            return _reject(world, req, col, "validation_failed",
                           "bagCount must be a positive number (decimals allowed).")
        fbw = pallet.get("fullBagWeight")  # Station 1 full-bag weight is authoritative
        if not fbw:
            return _reject(world, req, col, "validation_failed",
                           f"Pallet {pallet['palletId']} is bulk stock (no bag size); "
                           f"send quantity instead of bag fields.")
        fraction = BAG_FRACTIONS[bag_option]
        weight = r3(bag_count * fraction * fbw)
        bag_eq = r3(bag_count * fraction)
        log.step(f"bag math: {bag_count} × {bag_option} bags × {fbw}kg full-bag "
                 f"= {weight}kg ({bag_eq} full-bag equivalents)")
    elif quantity is not None:
        if not isinstance(quantity, (int, float)) or quantity <= 0:
            return _reject(world, req, col, "validation_failed",
                           "quantity must be a positive number.")
        weight = r3(quantity)
        line_fbw = line["fullBagWeight"]
        bag_eq = r3(weight / line_fbw) if line_fbw else None
        log.step(f"direct-weight scan: {weight}kg"
                 + (f" ({bag_eq} full-bag equivalents)" if bag_eq is not None else " (bulk)"))
    else:
        return _reject(world, req, col, "validation_failed",
                       "Provide bagSizeOption+bagCount or quantity.")

    if weight > pallet["remainingQuantity"] + EPS:
        return _reject(world, req, col, "validation_failed",
                       f"Pallet {pallet['palletId']} holds only "
                       f"{pallet['remainingQuantity']}{pallet['unit']}; "
                       f"scan of {weight} exceeds it.")

    # ---- over-collection tolerance ----
    line_fbw = line["fullBagWeight"]
    is_bulk_line = line_fbw is None
    tolerance_bags = None if is_bulk_line else world.config["overCollectionToleranceBags"]
    remaining = r3(line["requiredQuantity"] - line["collectedQuantity"])
    over = r3(weight - remaining)

    if remaining <= EPS:
        needs_approval_reasons.append(
            f"line {line['lineNumber']} ({line['materialCode']}) has no remaining requirement")
    elif over > EPS:
        if is_bulk_line:
            needs_approval_reasons.append(
                f"over-collection of {over}kg on a bulk line — no bag tolerance applies, "
                f"any over-collection needs approval")
        else:
            tol_weight = r3(tolerance_bags * line_fbw)
            if over <= tol_weight + EPS:
                log.ok(f"over-collection {over}kg within tolerance "
                       f"({tolerance_bags} bag(s) = {tol_weight}kg) -> auto-accepted; "
                       f"crediting only the remaining {remaining}kg, recording full "
                       f"weightReceived {weight}kg")
            else:
                needs_approval_reasons.append(
                    f"over-collection of {over}kg exceeds tolerance of "
                    f"{tolerance_bags} bag(s) ({tol_weight}kg)")

    if needs_approval_reasons:
        reason_text = "; ".join(needs_approval_reasons)
        try:
            approver = approve(world, log, req, "ingredient_approve_override")
        except Rejection:
            log.fail(f"ingredient scan requires manager approval: {reason_text}")
            return _reject(world, req, col, "validation_failed",
                           f"Manager approval required: {reason_text}.",
                           next_action="retry_with_manager_approval",
                           requires_approval=True,
                           tolerance=tolerance_bags)
        log.ok(f"override approved for: {reason_text}")

    # ---- apply (atomic: single-threaded worker) ----
    credited = min(weight, max(remaining, 0.0))
    line["collectedQuantity"] = r3(line["collectedQuantity"] + credited)
    line["weightReceived"] = r3(line["weightReceived"] + weight)
    if not is_bulk_line:
        eq = r3(weight / line_fbw)
        line["scannedBagsEq"] = r3(line["scannedBagsEq"] + eq)
        over_bags = r3(max(0.0, weight - credited) / line_fbw)
        if approver and over_bags > 0:
            line["approvedExtraBags"] = r3(line["approvedExtraBags"] + over_bags)

    old_pallet_qty = pallet["remainingQuantity"]
    pallet["remainingQuantity"] = r3(max(0.0, pallet["remainingQuantity"] - weight))
    if pallet["remainingQuantity"] <= EPS:
        pallet["remainingQuantity"] = 0.0
        pallet["palletState"] = "Consumed"
        pallet["localLocation"] = "Consumed"
        log.transition(f"pallet {pallet['palletId']}: fully depleted -> Consumed")

    log.ok(f"scan ACCEPTED on {col['collectionId']} line {line['lineNumber']} "
           f"({line['materialCode']} '{line['materialName']}'): +{credited}kg credited "
           f"(weightReceived +{weight}kg), line {line['collectedQuantity']}/"
           f"{line['requiredQuantity']}kg; pallet {pallet['palletId']} "
           f"{old_pallet_qty} -> {pallet['remainingQuantity']}{pallet['unit']} "
           f"[pallet={pallet['palletId']} batch={pallet['batchNumber']} "
           f"operator={session['operatorId']} device={req['deviceId']}]")

    next_action = _completion_check(world, log, col)
    return _result(world, req, col, approver=approver, next_action=next_action,
                   tolerance=tolerance_bags)
