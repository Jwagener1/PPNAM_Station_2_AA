"""Drive a job card's collection to completion, line by line."""
import json
import math
import os
import sys
import time

import sweep2 as s

HERE = os.path.dirname(os.path.abspath(__file__))
WIRE = os.path.join(HERE, "capture2", "wire.jsonl")
PALLETS = json.load(open(os.path.join(HERE, "pallets.json")))


def pallet_for(material_code):
    """Prefer a big InHolding pallet; return (pallet_id, kg_per_bag)."""
    # The backend accepts pallets that are in Holding *or* Mixing; anything else
    # (Extrusion, Consumed, AtStation1) needs the recovery flow first.
    cands = [p for p in PALLETS if p["item_code"] == material_code
             and p["is_blocked"] == "0"
             and p["location"] in ("Holding", "Mixing")]
    if not cands:
        return None, None
    # prefer Holding over Mixing, then the fullest pallet
    cands.sort(key=lambda p: (p["location"] != "Holding", -float(p["remaining_quantity"])))
    p = cands[0]
    bags = float(p["bags"]) or 1
    kg_per_bag = float(p["original_quantity"]) / bags
    return p["pallet_id"], kg_per_bag


def _scan_bom(jc, since_frame=0):
    best = None
    col = None
    for i, line in enumerate(open(WIRE, encoding="utf-8")):
        if i < since_frame:
            continue
        r = json.loads(line)
        p = r.get("payload")
        if not isinstance(p, dict) or not p.get("ingredients"):
            continue
        if r.get("action") == "bom_loaded" and p.get("jobCardNumber") == jc:
            best, col = p, p.get("collectionId")
        elif r.get("action") == "ingredient_scan_result" and col and p.get("collectionId") == col:
            best = p
    return best


def frame_count():
    try:
        return sum(1 for _ in open(WIRE, encoding="utf-8"))
    except FileNotFoundError:
        return 0


def wait_bom(jc, since_frame, timeout=45):
    """Poll the wire capture until this job card's BOM lands."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        b = _scan_bom(jc, since_frame)
        if b:
            return b
        time.sleep(2)
    return None


def latest_bom(jc):
    return _scan_bom(jc)


def dump_texts():
    return s.nodes(s.dump())


def arm_line(line_no):
    ns = dump_texts()
    n = s.find(ns, f"Line {line_no}", exact=True)
    if not n:
        # scroll and retry
        s.sh("shell", "input", "swipe", "540", "1200", "540", "600", "300")
        time.sleep(1.2)
        ns = dump_texts()
        n = s.find(ns, f"Line {line_no}", exact=True)
    if not n:
        return False
    s.tap(n["cx"], n["cy"])
    time.sleep(2)
    return True


def scan_and_confirm(pallet_id, bags):
    s.sh("shell", "am", "broadcast", "-a", "com.scanner.broadcast", "--es", "data", pallet_id)
    time.sleep(3.5)
    ns = dump_texts()
    f = [n for n in ns if "EditText" in n["cls"]]
    if not f:
        print("    no bag dialog")
        return False
    s.tap(f[0]["cx"], f[0]["cy"])
    time.sleep(1.0)
    for _ in range(8):
        s.sh("shell", "input", "keyevent", "67")
    s.sh("shell", "input", "text", str(int(bags)))
    time.sleep(0.6)
    ns = dump_texts()
    c = s.find(ns, "Confirm Scan", exact=True)
    if not c:
        print("    no confirm button")
        return False
    s.tap(c["cx"], c["cy"])
    time.sleep(8)
    return True


def collect(jc):
    print(f"### {jc}")
    if not s.goto_lookup():
        print("  cannot reach lookup")
        return False
    mark = frame_count()
    s.lookup(jc)
    bom = wait_bom(jc, mark, timeout=60)
    if not bom:
        print("  no BOM captured (timed out)")
        return False
    time.sleep(2)
    for ln in bom["ingredients"]:
        if ln.get("collected"):
            print(f"  line {ln['lineNumber']} already collected")
            continue
        pid, kgpb = pallet_for(ln["materialCode"])
        if not pid:
            print(f"  line {ln['lineNumber']} {ln['materialCode']}: NO PALLET AVAILABLE")
            continue
        need = ln["remainingQuantity"] or ln["requiredQuantity"]
        bags = max(1, math.ceil(need / kgpb))
        print(f"  line {ln['lineNumber']} {ln['materialCode']} need {need}kg @ {kgpb}kg/bag -> {bags} bags")
        if not arm_line(ln["lineNumber"]):
            print("    could not arm")
            continue
        scan_and_confirm(pid, bags)
    ns = dump_texts()
    for n in ns:
        if "satisfied" in n["text"] or "ready to mix" in n["text"]:
            print(f"  => {n['text']}")
    s.shot(f"collect-{jc}")
    return True


if __name__ == "__main__":
    for jc in sys.argv[1:]:
        collect(jc)
    print("COLLECT DONE", flush=True)
