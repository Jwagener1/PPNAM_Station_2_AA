"""Mixing-board helpers: start / finish / force-close cycles."""
import json
import os
import sys
import time

import sweep2 as s

HERE = os.path.dirname(os.path.abspath(__file__))
WIRE = os.path.join(HERE, "capture2", "wire.jsonl")


def texts():
    return s.nodes(s.dump())


def tap_scroll(needle, exact=False, tries=6):
    """Find a node, scrolling the board if needed."""
    for i in range(tries):
        ns = texts()
        n = s.find(ns, needle, exact=exact)
        if n and 260 < n["cy"] < 1700:
            s.tap(n["cx"], n["cy"])
            return True
        s.sh("shell", "input", "swipe", "540", "1400", "540", "800", "300")
        time.sleep(1.3)
    return False


def open_area(area):
    if not tap_scroll(area):
        return False
    time.sleep(11)
    return True


def start_cycle(collection, machine):
    print(f"  start {collection} on {machine}")
    if not tap_scroll(collection):
        print("    collection not found")
        return False
    time.sleep(2.5)
    if not tap_scroll(machine, exact=True):
        print("    machine not found")
        return False
    time.sleep(5)
    ns = texts()
    b = s.find(ns, "Start", exact=True)
    if not b:
        print("    no Start button:", [n["text"] for n in ns if n["text"]][:8])
        return False
    s.tap(b["cx"], b["cy"])
    time.sleep(11)
    return True


def cycles_from_wire():
    out = {}
    for line in open(WIRE, encoding="utf-8"):
        r = json.loads(line)
        p = r.get("payload")
        if isinstance(p, dict) and p.get("cycleId"):
            out[p["cycleId"]] = (p.get("machineCode"), p.get("accepted"), r.get("action"))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "start":
        start_cycle(sys.argv[2], sys.argv[3])
    elif cmd == "area":
        open_area(sys.argv[2])
    elif cmd == "show":
        for n in texts():
            if n["text"] or n["desc"]:
                print(n["cy"], repr(n["text"][:55]), repr(n["desc"][:20]))
    elif cmd == "cycles":
        for k, v in cycles_from_wire().items():
            print(k, v)
