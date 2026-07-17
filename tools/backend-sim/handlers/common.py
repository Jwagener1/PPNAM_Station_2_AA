"""Shared helpers for message-family handlers."""

EPS = 0.001

BAG_FRACTIONS = {"1/4": 0.25, "1/2": 0.5, "3/4": 0.75, "full": 1.0}


def unit_for_uom(uom_code):
    if str(uom_code) == "269":
        return "kg"
    if str(uom_code) == "268":
        return "each"
    return str(uom_code)


def r3(x):
    return round(x + 0.0, 3)


def line_payload(world, line):
    """Contract-shaped ingredient line for bom_loaded / ingredient_scan_result."""
    fbw = line["fullBagWeight"]
    required = line["requiredQuantity"]
    collected = line["collectedQuantity"]
    remaining = max(0.0, required - collected)
    is_bulk = fbw is None
    return {
        "lineNumber": line["lineNumber"],
        "materialCode": line["materialCode"],
        "materialName": line["materialName"],
        "plannedQuantity": r3(line["plannedQuantity"]),
        "issuedQuantity": r3(line["issuedQuantity"]),
        "requiredQuantity": r3(required),
        "collectedQuantity": r3(collected),
        "weightReceived": r3(line["weightReceived"]),
        "remainingQuantity": r3(remaining),
        "availableQuantity": world.available_quantity(line["materialCode"]),
        "bagSize": None if is_bulk else f"{fbw:.3f} kg",
        "expectedBags": None if is_bulk else r3(required / fbw),
        "scannedBags": None if is_bulk else r3(line["scannedBagsEq"]),
        "approvedExtraBags": None if is_bulk else r3(line["approvedExtraBags"]),
        "approvedShortBags": None if is_bulk else r3(line["approvedShortBags"]),
        "remainingBags": None if is_bulk else r3(remaining / fbw),
        "action": "Collected" if remaining <= EPS else "Collect",
        "collected": remaining <= EPS,
        "issueType": line["issueType"],
        "requiresIngredientCollection": line["requiresIngredientCollection"],
        "uomCode": str(line["uomCode"]),
        "unit": line["unit"],
        "warehouse": line["warehouse"],
    }


def handheld_lines(collection):
    """Manual-issue lines only; im_Backflush stays in the snapshot but is
    excluded from the handheld array."""
    return [l for l in collection["lines"] if l["requiresIngredientCollection"]]


def collection_summary(collection):
    lines = handheld_lines(collection)
    waiting = [l for l in lines
               if l["requiredQuantity"] - l["collectedQuantity"] > EPS]
    collected = [l for l in lines if l not in waiting]
    waiting_qty = r3(sum(l["requiredQuantity"] - l["collectedQuantity"] for l in waiting))
    collected_qty = r3(sum(l["collectedQuantity"] for l in lines))
    if not waiting:
        summary = "All products collected."
    elif len(waiting) == 1:
        summary = "1 product waiting for collection."
    else:
        summary = f"{len(waiting)} products waiting for collection."
    return {
        "waitingProductCount": len(waiting),
        "collectedProductCount": len(collected),
        "waitingQuantity": waiting_qty,
        "collectedQuantity": collected_qty,
        "summary": summary,
    }


def ingredients_payload(world, collection):
    return [line_payload(world, l) for l in handheld_lines(collection)]


def collection_is_complete(collection):
    return all(l["requiredQuantity"] - l["collectedQuantity"] <= EPS
               for l in handheld_lines(collection))


def collection_progress(collection):
    lines = handheld_lines(collection)
    collected = r3(sum(l["collectedQuantity"] for l in lines))
    remaining = r3(sum(max(0.0, l["requiredQuantity"] - l["collectedQuantity"]) for l in lines))
    return collected, remaining
