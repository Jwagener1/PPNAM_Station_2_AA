"""Drive a job card's ingredient collection to ReadyForMixing against the BACKEND SIM.

The stock collect.py sources tags from pallets.json (DUMMY-ST2… barcodes) which the sim does
not know. This driver instead sources tags from the sim's own seed via pallets_sim.json
(material_code -> a usable Holding pallet's RFID tag), so every scan resolves on the sim.

A pallet is injected as a *barcode* broadcast (TEST_PLAN §0.4: a barcode is accepted as a
pallet stand-in), carrying the sim's real tag string, so the app sends it as sourceBarcode and
the sim looks it up.

Usage (staged — one line per call keeps the UI script short so it never hangs):
    python collect_sim.py load 510019068          # nav to lookup + load a fresh collection
    python collect_sim.py line <lineNumber>       # arm + scan + confirm ONE line
    python collect_sim.py status                  # print current line satisfaction from the wire
"""
import json
import math
import os
import sys
import time

import sweep2 as s
import ui

HERE = os.path.dirname(os.path.abspath(__file__))
WIRE = os.path.abspath(os.path.join(HERE, "..", "..", "docs", "test-runs", "2026-07-27",
                                    "capture", "wire.jsonl"))
PSIM = {r["materialCode"]: r for r in json.load(open(os.path.join(HERE, "pallets_sim.json")))
        if r["usable"]}


def _records():
    try:
        for line in open(WIRE, encoding="utf-8"):
            try:
                yield json.loads(line)
            except ValueError:
                continue
    except FileNotFoundError:
        return


def latest_collection(jc):
    """Most recent bom_loaded collectionId for this job card, from the wire."""
    col = None
    for r in _records():
        p = r.get("payload")
        if isinstance(p, dict) and r.get("action") == "bom_loaded" \
                and str(p.get("jobCardNumber")) == str(jc):
            col = p.get("collectionId")
    return col


def latest_state(col):
    """Most recent ingredient view (bom_loaded or scan result) for a collection."""
    best = None
    for r in _records():
        p = r.get("payload")
        if isinstance(p, dict) and p.get("collectionId") == col and p.get("ingredients"):
            best = p
    return best


def do_load(jc):
    if not s.goto_lookup():
        print("LOAD FAIL: cannot reach Job Lookup")
        return
    s.lookup(jc)
    time.sleep(9)
    col = latest_collection(jc)
    st = latest_state(col) if col else None
    print(f"LOADED jc={jc} collection={col}")
    if st:
        for ln in st["ingredients"]:
            print(f"  line {ln['lineNumber']} {ln['materialCode']} "
                  f"req={ln['requiredQuantity']} bagSize={ln.get('bagSize')} "
                  f"collected={ln['collected']}")


def _arm(line_no):
    ns = s.nodes(s.dump())
    n = s.find(ns, f"Line {line_no}", exact=True)
    if not n:
        s.sh("shell", "input", "swipe", "540", "1300", "540", "600", "300")
        time.sleep(1.2)
        ns = s.nodes(s.dump())
        n = s.find(ns, f"Line {line_no}", exact=True)
    if not n:
        return False
    s.tap(n["cx"], n["cy"])
    time.sleep(2)
    return True


def do_line(line_no):
    jc = None
    # find the JC from the most recent bom_loaded
    for r in _records():
        p = r.get("payload")
        if isinstance(p, dict) and r.get("action") == "bom_loaded":
            jc = p.get("jobCardNumber")
    col = latest_collection(jc)
    st = latest_state(col)
    if not st:
        print("LINE FAIL: no collection state on the wire")
        return
    ln = next((l for l in st["ingredients"] if l["lineNumber"] == line_no), None)
    if not ln:
        print(f"LINE FAIL: line {line_no} not in BOM")
        return
    if ln["collected"]:
        print(f"line {line_no} already collected")
        return
    mat = ln["materialCode"]
    pal = PSIM.get(mat)
    if not pal:
        print(f"LINE FAIL: no sim pallet for material {mat}")
        return
    if not _arm(line_no):
        print(f"LINE FAIL: could not arm line {line_no}")
        return
    # scan the sim tag as a barcode (accepted as a pallet stand-in)
    s.sh("shell", "am", "broadcast", "-a", "com.scanner.broadcast", "--es", "data",
         pal["palletRfidTag"])
    time.sleep(4)
    ns = s.nodes(s.dump())
    # dialog-open guard: only proceed when the scan dialog for THIS pallet is on screen
    title = s.find(ns, "Bag size", exact=False) or s.find(ns, "Weight", exact=False)
    tag_shown = s.find(ns, pal["palletRfidTag"], exact=False)
    if not title or not tag_shown:
        print(f"LINE FAIL: scan dialog not open for line {line_no} "
              f"(title={bool(title)} tag={bool(tag_shown)})")
        s.shot(f"collectsim-noline-{line_no}")
        return
    need = ln["remainingQuantity"] or ln["requiredQuantity"]
    is_weight = pal["bulk"] or not ln.get("bagSize")
    if is_weight:
        # bulk line: no round-up prefill, so type the weight in kg
        fields = [n for n in ns if "EditText" in n["cls"]]
        # bulk lines have ZERO over-collection tolerance, so send the exact required weight
        # (3 decimals — %g's 6 sig-figs would round 1671.147 up to 1671.15 and over-collect)
        value = ("%.3f" % need)
        f = fields[0]
        s.tap(f["cx"], f["cy"])
        time.sleep(0.8)
        for _ in range(12):
            s.sh("shell", "input", "keyevent", "67")
        s.sh("shell", "input", "text", value)
        time.sleep(0.6)
        print(f"line {line_no} {mat}: weight={value}kg (need {need}kg) -> confirming")
    else:
        # bag line: the app pre-fills the correct round-up; accept it as-is
        print(f"line {line_no} {mat}: accepting round-up prefill (need {need}kg) -> confirming")
    ns = s.nodes(s.dump())
    c = s.find(ns, "Confirm Scan", exact=True) or s.find(ns, "Confirm Weight", exact=True)
    if not c:
        print(f"LINE FAIL: no Confirm button for line {line_no}")
        s.shot(f"collectsim-noconfirm-{line_no}")
        return
    s.tap(c["cx"], c["cy"])
    time.sleep(8)
    st2 = latest_state(col)
    ln2 = next((l for l in st2["ingredients"] if l["lineNumber"] == line_no), ln)
    print(f"  after: collected={ln2['collected']} "
          f"collectedQty={ln2['collectedQuantity']} status={st2.get('collectionStatus')}")


def do_status():
    jc = None
    for r in _records():
        p = r.get("payload")
        if isinstance(p, dict) and r.get("action") == "bom_loaded":
            jc = p.get("jobCardNumber")
    col = latest_collection(jc)
    st = latest_state(col)
    if not st:
        print("no state")
        return
    print(f"collection={col} status={st.get('collectionStatus')}")
    for ln in st["ingredients"]:
        print(f"  line {ln['lineNumber']} {ln['materialCode']} "
              f"collected={ln['collected']} {ln['collectedQuantity']}/{ln['requiredQuantity']}")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "load":
        do_load(sys.argv[2])
    elif cmd == "line":
        do_line(int(sys.argv[2]))
    else:
        do_status()
    print("DONE", flush=True)
