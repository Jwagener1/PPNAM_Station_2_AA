"""Contract self-test: a fake handheld that drives the simulator through the
contract's minimum end-to-end acceptance flow plus negative probes.

Two modes:
    python selftest.py --direct                 # in-process, no broker needed
    python selftest.py [--host …] [--port …]    # over MQTT against a running sim

Exits 0 when every check passes, 1 on the first contract deviation.
"""

import argparse
import json
import queue
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone

import paho.mqtt.client as mqtt

DEVICE = "handheld_selftest"
CHECKS = {"passed": 0}


def now_iso(offset_seconds=0):
    return (datetime.now(timezone.utc) + timedelta(seconds=offset_seconds)) \
        .strftime("%Y-%m-%dT%H:%M:%SZ")


def check(cond, label, detail=""):
    if cond:
        CHECKS["passed"] += 1
        print(f"  PASS  {label}")
    else:
        print(f"  FAIL  {label}  {detail}")
        sys.exit(1)


class Handheld:
    def __init__(self, host, port):
        self.rx = queue.Queue()
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                                  client_id=DEVICE, protocol=mqtt.MQTTv5)
        self.client.will_set(f"PPNAM/{DEVICE}/status", "offline", qos=1, retain=True)
        self.client.on_message = lambda c, u, m: self.rx.put(m)
        self.client.connect(host, port, keepalive=30)
        self.client.loop_start()
        self.client.subscribe([(f"PPNAM/{DEVICE}/res/+", 1)])
        self.client.publish(f"PPNAM/{DEVICE}/status", "online", qos=1, retain=True)
        time.sleep(0.5)
        self.session = ""
        self.msg_seq = 0

    def next_msg_id(self, prefix):
        self.msg_seq += 1
        return f"{prefix}-{uuid.uuid4().hex[:8]}-{self.msg_seq:04d}"

    def send_raw(self, request_type, payload):
        self.client.publish(f"PPNAM/{DEVICE}/req/{request_type}",
                            json.dumps(payload), qos=1)

    def request(self, request_type, fields=None, msg_id=None, session=None,
                timestamp=None, schema="3.0", device=DEVICE, timeout=10):
        msg_id = msg_id or self.next_msg_id(request_type.split("_")[0])
        payload = {
            "messageId": msg_id,
            "schemaVersion": schema,
            "deviceId": device,
            "operatorSessionId": self.session if session is None else session,
            "timestampUtc": timestamp or now_iso(),
        }
        payload.update(fields or {})
        self.send_raw(request_type, payload)
        return self.await_response(msg_id, timeout), payload

    def await_response(self, msg_id, timeout=10):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                m = self.rx.get(timeout=max(0.1, deadline - time.time()))
            except queue.Empty:
                break
            body = json.loads(m.payload)
            if body.get("inResponseToMessageId") == msg_id:
                body["_topic"] = m.topic
                return body
        raise TimeoutError(f"no response for {msg_id} within {timeout}s")

    def close(self):
        self.client.publish(f"PPNAM/{DEVICE}/status", "offline", qos=1, retain=True)
        time.sleep(0.3)
        self.client.loop_stop()
        self.client.disconnect()


class _FakeMsg:
    def __init__(self, topic, payload):
        self.topic = topic
        self.payload = payload.encode() if isinstance(payload, str) else payload
        self.qos = 1


class _FakePublishResult:
    def wait_for_publish(self, timeout=None):
        pass


class _FakeClient:
    """Captures the simulator's publishes and feeds res/ topics back to the
    handheld's receive queue — an in-process loopback replacing the broker."""

    def __init__(self, rx):
        self.rx = rx

    def publish(self, topic, payload=None, qos=0, retain=False, properties=None):
        if "/res/" in topic:
            self.rx.put(_FakeMsg(topic, payload))
        return _FakePublishResult()

    def subscribe(self, *a, **k):
        pass


class DirectHandheld(Handheld):
    """Drives the simulator in-process: send_raw calls handle_request directly,
    exactly as the worker thread would (same serialization guarantee)."""

    def __init__(self):
        import sim as sim_module
        args = argparse.Namespace(host="direct", port=0, window=None,
                                  tolerance=None, yield_to_real=False, no_color=True)
        self.sim = sim_module.Simulator(args)
        self.sim.client = _FakeClient(rx=queue.Queue())
        self.rx = self.sim.client.rx
        self.session = ""
        self.msg_seq = 0
        print(f"direct mode: simulator in-process, logs at {self.sim.log.run_dir}")

    def send_raw(self, request_type, payload):
        self.sim.handle_request(DEVICE, request_type, json.dumps(payload).encode())

    def close(self):
        self.sim.log.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="mqtt.sysone.co.za")
    ap.add_argument("--port", type=int, default=1883)
    ap.add_argument("--direct", action="store_true",
                    help="run the simulator in-process (no broker required)")
    args = ap.parse_args()
    hh = DirectHandheld() if args.direct else Handheld(args.host, args.port)
    job = "510019068"

    print("== negative probes before login ==")
    r, _ = hh.request("pallet_lookup_requested",
                      {"palletRfidTag": "300833B2DDD9014000000001"}, session="nope")
    check(not r["accepted"] and r["errorCode"] == "session_required"
          and r["nextAction"] == "login",
          "request without session -> session_required + nextAction login", json.dumps(r)[:200])

    print("== login ==")
    r, _ = hh.request("login_requested", {"username": "operator1", "password": "wrong"},
                      session="")
    check(not r["accepted"] and r["errorCode"] == "permission_denied",
          "wrong password rejected", json.dumps(r)[:200])
    r, _ = hh.request("login_requested", {"username": "operator1", "password": "pass"},
                      session="")
    check(r["accepted"] and r["sessionState"] == "Active" and r["operatorSessionId"],
          "login accepted, Active session", json.dumps(r)[:200])
    hh.session = r["operatorSessionId"]

    print("== envelope negatives ==")
    r, _ = hh.request("hopper_overview_requested", schema="2.0")
    check(not r["accepted"] and r["errorCode"] == "unsupported_schema",
          "schema 2.0 -> unsupported_schema")
    r, _ = hh.request("hopper_overview_requested", timestamp=now_iso(-3600))
    check(not r["accepted"] and r["errorCode"] == "message_expired",
          "1h-old timestamp -> message_expired")
    r, _ = hh.request("hopper_overview_requested", device="handheld_other")
    check(not r["accepted"] and r["errorCode"] == "device_mismatch",
          "payload/topic device mismatch -> device_mismatch")

    print("== replay & idempotency ==")
    r1, p1 = hh.request("hopper_overview_requested")
    check(r1["accepted"] and len(r1["hoppers"]) == 3, "hopper overview: 3 configured hoppers")
    hh.send_raw("hopper_overview_requested", p1)  # byte-identical retry
    r2 = hh.await_response(p1["messageId"])
    check(r2["messageId"] == r1["messageId"],
          "identical replay returns the STORED response (same server messageId)")
    p_mut = dict(p1)
    p_mut["correlationKey"] = "MUTATED-BODY"
    hh.send_raw("hopper_overview_requested", p_mut)
    r3_ = hh.await_response(p1["messageId"])
    check(not r3_["accepted"] and r3_["errorCode"] == "message_id_reused",
          "same messageId + different body -> message_id_reused")

    print("== pallet lookup & holding recovery ==")
    r, _ = hh.request("pallet_lookup_requested", {"palletRfidTag": "NO_SUCH_TAG"})
    check(r["accepted"] and r["found"] is False,
          "unknown tag: accepted true, found false")
    r, _ = hh.request("pallet_lookup_requested",
                      {"palletRfidTag": "300833B2DDD9014000000004"})
    check(r["accepted"] and r["palletState"] == "AtStation1" and r["recoverable"]
          and not r["usable"] and r["nextAction"] == "recover_holding",
          "AtStation1 pallet: recoverable, not usable, nextAction recover_holding")
    r, _ = hh.request("holding_recovery_requested",
                      {"palletRfidTag": "300833B2DDD9014000000004",
                       "auditReason": "Missed door read; pallet is physically here."})
    check(r["accepted"] and r["palletState"] == "Holding" and r["usable"],
          "recovery -> Holding, usable")
    r, _ = hh.request("pallet_lookup_requested",
                      {"palletRfidTag": "300833B2DDD9014000000003"})
    check(r["accepted"] and r["blocked"] and not r["usable"] and not r["recoverable"],
          "blocked Holding pallet: not usable, not recoverable")

    print("== job card load ==")
    r, _ = hh.request("open_sap_job_cards_requested", {"refreshFromSap": True})
    check(r["accepted"] and any(j["jobCardNumber"] == job for j in r["jobs"]),
          f"open SAP job cards contains {job}")
    r, _ = hh.request("job_card_load_requested", {"jobCardNumber": job,
                                                  "correlationKey": job})
    check(r["accepted"] and r["collectionId"].startswith("COL_")
          and r["nextAction"] == "scan_ingredient" and not r["resumed"],
          "bom_loaded: new collection, scan_ingredient")
    col = r["collectionId"]
    check(len(r["ingredients"]) == 7, "7 manual lines (im_Backflush excluded)",
          f"got {len(r['ingredients'])}")
    bulk = [i for i in r["ingredients"] if i["materialCode"] == "1600000217"]
    check(bulk and bulk[0]["bagSize"] is None and bulk[0]["expectedBags"] is None,
          "bulk line: bagSize/expectedBags null")
    check("hoppers" in r and len(r["hoppers"]) == 3, "hopper board present in bom_loaded")

    def scan(fields, **kw):
        f = {"collectionId": col, "correlationKey": col}
        f.update(fields)
        return hh.request("ingredient_scan_requested", f, **kw)

    print("== ingredient collection ==")
    r, _ = scan({"palletRfidTag": "300833B2DDD9014000000001",
                 "requestedMaterialCode": "1600000301",
                 "bagSizeOption": "full", "bagCount": 22})
    line = next(i for i in r["ingredients"] if i["materialCode"] == "1600000301")
    check(r["accepted"] and line["scannedBags"] == 22 and line["collectedQuantity"] == 550,
          "22 full bags HD WHITE: 550kg collected", json.dumps(line)[:300])
    r, _ = scan({"palletRfidTag": "300833B2DDD9014000000001",
                 "requestedMaterialCode": "1600000301",
                 "bagSizeOption": "1/2", "bagCount": 2})
    line = next(i for i in r["ingredients"] if i["materialCode"] == "1600000301")
    check(r["accepted"] and line["collected"]
          and line["collectedQuantity"] == 557.049 and line["weightReceived"] == 575.0,
          "2 half-bags over remaining, within 1-bag tolerance: credited to remaining, "
          "full weightReceived recorded", json.dumps(line)[:300])
    check(r["overCollectionToleranceBags"] == 1.0,
          "tolerance echoed so scanner never hardcodes it")

    r, _ = scan({"palletRfidTag": "300833B2DDD9014000000002",
                 "requestedMaterialCode": "1600000217", "quantity": 1671.147})
    line = next(i for i in r["ingredients"] if i["materialCode"] == "1600000217")
    check(r["accepted"] and line["collected"], "bulk line collected by direct weight")

    # over tolerance -> reject -> approved retry (NEW messageId)
    r, p = scan({"palletRfidTag": "300833B2DDD901400000000C",
                 "requestedMaterialCode": "1600000070", "quantity": 600.0})
    check(not r["accepted"] and r["requiresManagerApproval"]
          and r["nextAction"] == "retry_with_manager_approval",
          "over-tolerance scan rejected with requiresManagerApproval")
    retry = {k: v for k, v in p.items() if k not in
             ("messageId", "timestampUtc")}
    retry.update({"managerUsername": "manager1", "managerPassword": "secret",
                  "auditReason": "Verified spillage allowance."})
    r, _ = hh.request("ingredient_scan_requested",
                      {k: v for k, v in retry.items()
                       if k not in ("schemaVersion", "deviceId", "operatorSessionId")})
    check(r["accepted"] and r["approverUserId"] == "OP-012",
          "approved retry (new messageId) accepted; approver in audit fields",
          json.dumps(r)[:300])

    r, _ = scan({"palletRfidTag": "300833B2DDD9014000000009",
                 "requestedMaterialCode": "1500000326", "quantity": 69.631})
    check(r["accepted"], "masterbatch white collected")
    r, _ = scan({"palletRfidTag": "300833B2DDD901400000000A",
                 "requestedMaterialCode": "1600000233", "quantity": 278.524})
    check(r["accepted"], "pallet wrap collected")
    r, _ = scan({"palletRfidTag": "300833B2DDD9014000000008",
                 "requestedMaterialCode": "1600000309", "quantity": 557.049})
    check(r["accepted"], "stretchhood collected")

    # short-bag waiver: rejected without creds, applied with creds on first submission
    r, _ = scan({"requestedMaterialCode": "1500000331", "shortBagCount": 1})
    check(not r["accepted"] and r["requiresManagerApproval"],
          "waiver without credentials rejected outright")
    r, _ = scan({"requestedMaterialCode": "1500000331", "shortBagCount": 1,
                 "managerUsername": "manager1", "managerPassword": "secret",
                 "auditReason": "One damaged bag unavailable."})
    check(r["accepted"] and r["approverUserId"] == "OP-012",
          "waiver with credentials applied on first submission")
    check(r["nextAction"] == "choose_destination",
          "final requirement satisfied -> ReadyForRouting + choose_destination",
          json.dumps(r["collectionSummary"]))

    print("== hopper cycles (shared pre-mix) ==")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"productionOrderDocumentNumber": job,
                       "machineCodes": ["MXR-01", "MXR-03"],
                       "collectionIds": [col], "preMixIds": [],
                       "correlationKey": col})
    check(not r["accepted"] and r["cycles"] == [] and
          any(c["machineCode"] == "MXR-03" and c["conflictCode"] == "machine_inactive"
              for c in r["conflicts"]),
          "start including inactive hopper: atomic rejection, conflict listed, nothing assigned")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"productionOrderDocumentNumber": job,
                       "machineCodes": ["MXR-01", "MXR-02"],
                       "collectionIds": [col], "preMixIds": [],
                       "correlationKey": col})
    check(r["accepted"] and len(r["cycles"]) == 2 and r["linkedPreMixId"],
          "two hoppers claimed atomically; one linked shared pre-mix")
    premix = r["linkedPreMixId"]
    cyc1 = next(c for c in r["cycles"] if c["machineCode"] == "MXR-01")["cycleId"]
    cyc2 = next(c for c in r["cycles"] if c["machineCode"] == "MXR-02")["cycleId"]
    r, _ = hh.request("machine_cycle_start_requested",
                      {"productionOrderDocumentNumber": job,
                       "machineCodes": ["MXR-01"],
                       "collectionIds": [col], "preMixIds": []})
    check(r["accepted"] and r["cycles"][0]["alreadyActive"]
          and r["cycles"][0]["cycleId"] == cyc1 and r["linkedPreMixId"] == premix,
          "repeat start on active hopper: alreadyActive true, same cycle, no duplicate")

    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "MXR-01", "cycleId": cyc1, "correlationKey": cyc1})
    check(r["accepted"] and not r["isComplete"] and r["preMixStatus"] == "Mixing"
          and r["nextAction"] == "assign_or_finish_hopper"
          and [c["cycleId"] for c in r["remainingActiveCycles"]] == [cyc2],
          "partial hopper finish: pre-mix stays Mixing, other hopper untouched")
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "MXR-01", "cycleId": cyc1})
    check(r["accepted"] and r["alreadyFinished"],
          "finishing a finished cycle: accepted no-op, alreadyFinished true")
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "MXR-02", "cycleId": cyc2})
    check(r["accepted"] and r["isComplete"]
          and r["preMixStatus"] == "ReadyForAllocation"
          and r["nextAction"] == "allocate_premix",
          "final hopper finish: pre-mix ReadyForAllocation, allocate_premix")

    print("== extrusion allocation & completion ==")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"productionOrderDocumentNumber": job,
                       "machineCodes": ["EXT-03"],
                       "collectionIds": [], "preMixIds": [premix]})
    check(r["accepted"] and r["runId"] and len(r["allocationIds"]) == 1
          and r["machineFamily"] == "Extruder",
          "extruder start consuming the pre-mix")
    ext_cycle = r["cycles"][0]["cycleId"]
    r, _ = hh.request("full_pallet_allocation_requested",
                      {"productionOrderDocumentNumber": job,
                       "extruderCode": "EXT-03",
                       "palletRfidTag": "300833B2DDD9014000000008"})
    check(r["accepted"] and r["sourceType"] == "FullPallet"
          and r["remainingPalletQuantity"] == 0 and r["sapProductionOrderChanged"] is False,
          "direct full-pallet allocation to the running extruder")
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "EXT-03", "cycleId": ext_cycle})
    check(r["accepted"] and r["isComplete"]
          and r["nextAction"] == "complete_station2_work",
          "extruder finish: no active runs left -> complete_station2_work")
    r, _ = hh.request("station2_work_complete_requested",
                      {"productionOrderDocumentNumber": job, "correlationKey": job})
    check(r["accepted"] and r["localJobStatus"] == "Station2Completed"
          and r["sapProductionOrderChanged"] is False and r["sapIssueQueued"] is False,
          "local Station 2 completion; SAP untouched")

    print("== logout ==")
    r, _ = hh.request("reader_logout_requested")
    check(r["accepted"] and r["sessionState"] == "Closed"
          and r["operatorSessionId"] == "" and r["allowedActions"] == [],
          "logout closes session")
    r, _ = hh.request("hopper_overview_requested")
    check(not r["accepted"] and r["errorCode"] == "session_required",
          "request on closed session -> session_required")

    hh.close()
    print(f"\nALL {CHECKS['passed']} CHECKS PASSED — simulator is contract-conformant")
    return 0


if __name__ == "__main__":
    sys.exit(main())
