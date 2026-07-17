"""pallet_lookup_requested -> pallet_lookup_result
holding_recovery_requested -> holding_recovery_result"""

from envelope import Rejection, build_response
from handlers.common import EPS, handheld_lines
from state import World


def _pallet_fields(world, p):
    usable = World.pallet_usable(p)
    recoverable = World.pallet_recoverable(p)
    return {
        "found": True,
        "usable": usable,
        "recoverable": recoverable,
        "palletRfidTag": p["palletRfidTag"],
        "palletId": p["palletId"],
        "productCode": p["productCode"],
        "productName": p["productName"],
        "batchNumber": p["batchNumber"],
        "remainingQuantity": p["remainingQuantity"],
        "remainingBags": (round(p["remainingQuantity"] / p["fullBagWeight"], 3)
                          if p.get("fullBagWeight") else None),
        "unit": p["unit"],
        "localLocation": p["localLocation"],
        "palletState": p["palletState"],
        "blocked": p["blocked"],
    }


_NOT_FOUND_FIELDS = {
    "found": False, "usable": False, "recoverable": False,
    "palletRfidTag": None, "palletId": None, "productCode": None,
    "productName": None, "batchNumber": None, "remainingQuantity": None,
    "remainingBags": None, "unit": None, "localLocation": None,
    "palletState": None, "blocked": False,
}


def lookup(world, log, req, session):
    tag = req.get("palletRfidTag")
    if not tag:
        raise Rejection("validation_failed", "palletRfidTag is required.")
    p = world.pallets.get(tag)
    if not p:
        # A lookup that ran correctly and found nothing is still accepted: true.
        log.warn(f"pallet lookup: tag '{tag}' unknown -> found: false (no inventory created)")
        extras = dict(_NOT_FOUND_FIELDS)
        extras["palletRfidTag"] = tag
        return build_response(world, req, response_extras=extras, correlation=tag)
    fields = _pallet_fields(world, p)
    next_action = "recover_holding" if fields["recoverable"] else ""
    log.ok(f"pallet lookup: {p['palletId']} '{p['productName']}' state={p['palletState']} "
           f"blocked={p['blocked']} remaining={p['remainingQuantity']}{p['unit']} "
           f"-> usable={fields['usable']} recoverable={fields['recoverable']}")
    return build_response(world, req, response_extras=fields, next_action=next_action,
                          correlation=tag)


def recovery(world, log, req, session):
    tag = req.get("palletRfidTag")
    if not tag:
        raise Rejection("validation_failed", "palletRfidTag is required.")
    if not req.get("auditReason"):
        raise Rejection("validation_failed", "Holding recovery requires an auditReason.")
    p = world.pallets.get(tag)
    if not p:
        log.fail(f"holding recovery: unknown tag '{tag}' — cannot create inventory via handheld")
        raise Rejection("not_found",
                        "Unknown RFID tag; resolve it from Station 1 first. "
                        "Handheld recovery never creates inventory.")

    if p["palletState"] == "Consumed":
        raise Rejection("state_conflict", "A consumed pallet cannot be recovered.")

    col_id = req.get("collectionId")
    if col_id:
        col = world.collections.get(col_id)
        if not col:
            raise Rejection("not_found", f"Collection '{col_id}' was not found.")
        materials = {l["materialCode"] for l in handheld_lines(col)}
        if p["productCode"] not in materials:
            raise Rejection("validation_failed",
                            f"Pallet product {p['productCode']} is not on the manual BOM "
                            f"of collection {col_id}.")

    if p["palletState"] in ("Holding", "Mixing"):
        log.warn(f"holding recovery: {p['palletId']} already in {p['palletState']} — "
                 f"idempotent success, no change")
    else:
        old = p["palletState"]
        p["palletState"] = "Holding"
        p["localLocation"] = "Holding"
        log.transition(f"pallet {p['palletId']} ({tag}): {old} -> Holding "
                       f"(recovered by {session['operatorId']} on {req['deviceId']}; "
                       f"auditReason: {req['auditReason']!r}; movement+exception+audit recorded; "
                       f"local-only, no SAP posting)")

    fields = _pallet_fields(world, p)
    if not fields["usable"]:
        log.warn(f"holding recovery: {p['palletId']} recovered but still NOT usable "
                 f"(blocked={p['blocked']}, remaining={p['remainingQuantity']})")
    return build_response(world, req, response_extras=fields, correlation=tag)
