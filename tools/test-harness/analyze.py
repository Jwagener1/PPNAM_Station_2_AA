"""Summarise the captured MQTT wire log."""
import json
import statistics
import sys
from collections import defaultdict

path = "capture/wire.jsonl"
recs = [json.loads(l) for l in open(path, encoding="utf-8")]

print(f"total frames: {len(recs)}")

# ---- job card loads -------------------------------------------------
print("\n=== JOB CARD LOADS ===")
loads = {}
for r in recs:
    if r.get("action") == "job_card_load_requested":
        loads[r["messageId"]] = r["payload"].get("jobCardNumber")
rows = []
for r in recs:
    if r.get("inResponseToMessageId") in loads and r["kind"] == "res":
        p = r["payload"]
        jc = loads[r["inResponseToMessageId"]]
        rows.append((jc, r.get("action"), p.get("accepted"), p.get("reason"),
                     p.get("errorCode"), p.get("status"),
                     len(p.get("ingredients") or []), r.get("latencyMs")))
for jc, act, ok, reason, err, status, nlines, lat in sorted(rows):
    flag = "OK " if ok else "FAIL"
    print(f"{flag} {jc}  {act:<28} status={status!s:<12} lines={nlines:<3} {lat}ms"
          + (f"  reason={reason} err={err}" if not ok else ""))
missing = set(loads.values()) - {r[0] for r in rows}
if missing:
    print("NO RESPONSE for:", sorted(missing))

# ---- latency by action ----------------------------------------------
print("\n=== LATENCY BY ACTION (ms) ===")
lat = defaultdict(list)
for r in recs:
    if r.get("latencyMs") is not None:
        lat[r.get("requestAction") or r.get("action")].append(r["latencyMs"])
for a, v in sorted(lat.items(), key=lambda kv: -statistics.mean(kv[1])):
    print(f"{a:<32} n={len(v):<3} min={min(v):<6} mean={round(statistics.mean(v)):<6} max={max(v)}")

# ---- rejections ------------------------------------------------------
print("\n=== REJECTIONS / ERRORS ===")
n = 0
for r in recs:
    p = r.get("payload")
    if isinstance(p, dict) and (p.get("accepted") is False or p.get("errorCode")):
        n += 1
        print(f"{r['ts']} {r['topic']} err={p.get('errorCode')} reason={p.get('reason')}")
if not n:
    print("(none)")

# ---- contract hygiene ------------------------------------------------
print("\n=== CONTRACT HYGIENE ===")
inverted = 0
reqts = {}
for r in recs:
    p = r.get("payload")
    if not isinstance(p, dict):
        continue
    if r["kind"] == "req":
        reqts[p.get("messageId")] = p.get("timestampUtc")
    irt = p.get("inResponseToMessageId")
    if irt and irt in reqts and p.get("timestampUtc"):
        a = reqts[irt].replace("Z", "+00:00")
        b = p["timestampUtc"].replace("Z", "+00:00")
        if b < a:
            inverted += 1
print(f"responses stamped EARLIER than their request: {inverted}")
missing_em = sum(1 for r in recs
                 if isinstance(r.get("payload"), dict)
                 and r["payload"].get("accepted") is False
                 and "errorMessage" not in r["payload"])
print(f"rejections with no errorMessage field: {missing_em}")
