"""Station 2 backend simulator — answers the Android handheld's MQTT v3
contract traffic exactly like the real WPF backend would, with extensive
logging (wire.jsonl, sim.log, state snapshots) as a second source of truth.

Usage:
    python sim.py [--host mqtt.sysone.co.za] [--port 1883]
                  [--window 300] [--tolerance 1.0] [--yield-to-real]

The simulator plays the role of station_2:
  - retained `online` on PPNAM/station_2/status (LWT: retained `offline`)
  - subscribes PPNAM/+/req/+ and PPNAM/+/status
  - one worker thread processes requests strictly in arrival order, which is
    exactly the serialization the contract requires of Station 2.
"""

import argparse
import json
import os
import queue
import sys
import threading
import time

import paho.mqtt.client as mqtt
from paho.mqtt.packettypes import PacketTypes
from paho.mqtt.properties import Properties

import envelope
from envelope import Rejection, Replay, build_response
from handlers import REGISTRY, RETIRED_REQUEST_TYPES
from simlog import SimLogger
from state import World

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STATION_ID = "station_2"
STATUS_TOPIC = f"PPNAM/{STATION_ID}/status"


def _redacted(payload):
    """Wire-log payloads with credentials masked. The workflow still receives the
    original bytes — only the log copy is redacted (contract: credentials are
    redacted from application and MQTT logs)."""
    try:
        body = json.loads(payload)
    except (ValueError, UnicodeDecodeError):
        return payload
    if not isinstance(body, dict):
        return payload
    masked = False
    for key in ("password", "managerPassword"):
        if body.get(key) is not None:
            body[key] = "***"
            masked = True
    return json.dumps(body, ensure_ascii=False) if masked else payload


class Simulator:
    def __init__(self, args):
        self.args = args
        self.log = SimLogger(BASE_DIR, color=not args.no_color)
        self.world = World(os.path.join(BASE_DIR, "seed"), self.log)
        if args.window is not None:
            self.world.config["timestampWindowSeconds"] = args.window
        if args.tolerance is not None:
            self.world.config["overCollectionToleranceBags"] = args.tolerance
        self.queue = queue.Queue()
        self.announced = False
        self.real_station_seen = threading.Event()
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                                  client_id="station2-simulator",
                                  protocol=mqtt.MQTTv5)
        self.client.will_set(STATUS_TOPIC, "offline", qos=1, retain=True)
        self.client.on_connect = self.on_connect
        self.client.on_message = self.on_message
        self.client.on_disconnect = self.on_disconnect

    # ------------------------------------------------------------ MQTT ----
    def on_connect(self, client, userdata, flags, reason_code, properties):
        self.log.ok(f"connected to {self.args.host}:{self.args.port} (rc={reason_code})")
        # Collision guard: watch for a real Station 2 already online before we
        # claim the retained status topic ourselves.
        client.subscribe([(STATUS_TOPIC, 1)])

    def announce(self):
        self.client.publish(STATUS_TOPIC, "online", qos=1, retain=True)
        self.log.wire("out", STATUS_TOPIC, "online")
        self.client.subscribe([("PPNAM/+/req/+", 1), ("PPNAM/+/status", 1)])
        self.announced = True
        self.log.ok("presence 'online' published (retained, LWT registered); "
                    "subscribed PPNAM/+/req/+ and PPNAM/+/status — simulating station_2")

    def on_disconnect(self, client, userdata, flags, reason_code, properties):
        self.log.warn(f"disconnected from broker (rc={reason_code}); paho will auto-reconnect")

    def on_message(self, client, userdata, msg):
        parts = msg.topic.split("/")
        is_req = len(parts) == 4 and parts[0] == "PPNAM" and parts[2] == "req"
        if not is_req:
            # req messages are wire-logged in handle_request (shared with the
            # selftest's direct in-process transport)
            self.log.wire("in", msg.topic, msg.payload, qos=msg.qos)
        if len(parts) == 3 and parts[0] == "PPNAM" and parts[2] == "status":
            if parts[1] == STATION_ID:
                if not self.announced and msg.payload.decode(errors="replace") == "online":
                    self.real_station_seen.set()
                return
            self.queue.put(("status", parts[1], msg.payload))
        elif len(parts) == 4 and parts[0] == "PPNAM" and parts[2] == "req":
            self.queue.put(("req", parts[1], parts[3], msg.payload))
        else:
            self.log.warn(f"unknown topic '{msg.topic}' — no workflow side effect (per contract)")

    def publish_response(self, device_id, response_type, response):
        topic = f"PPNAM/{device_id}/res/{response_type}"
        payload = json.dumps(response, ensure_ascii=False)
        self.client.publish(topic, payload, qos=1, retain=False)
        self.log.wire("out", topic, response)
        outcome = "ACCEPTED" if response.get("accepted") else \
            f"REJECTED ({response.get('errorCode')})"
        self.log.tx(f"res/{response_type} -> {device_id}: {outcome} "
                    f"msgId={response.get('messageId')} "
                    f"inResponseTo={response.get('inResponseToMessageId')} "
                    f"nextAction={response.get('nextAction')!r}")
        return topic

    # ---------------------------------------------------------- worker ----
    def worker(self):
        while True:
            item = self.queue.get()
            if item is None:
                return
            try:
                if item[0] == "status":
                    _, device_id, payload = item
                    status = payload.decode(errors="replace") if payload else ""
                    if status in ("online", "offline"):
                        self.world.presence_change(device_id, status)
                    elif payload:
                        self.log.warn(f"presence {device_id}: unexpected payload {status!r}")
                else:
                    _, device_id, request_type, payload = item
                    self.handle_request(device_id, request_type, payload)
            except Exception:  # noqa: BLE001 — a bad message must never kill the worker
                import traceback
                self.log.fail("unhandled error in worker:\n" + traceback.format_exc())

    def handle_request(self, device_id, request_type, payload):
        self.log.wire("in", f"PPNAM/{device_id}/req/{request_type}", _redacted(payload))
        if request_type in RETIRED_REQUEST_TYPES:
            self.log.fail(f"req/{request_type} from {device_id}: RETIRED v3 production request "
                          f"— answering client_upgrade_required (v4 tripwire)")
            try:
                body = json.loads(payload)
                if not isinstance(body, dict):
                    body = {}
            except (ValueError, UnicodeDecodeError):
                body = {}
            body.setdefault("deviceId", device_id)
            response = build_response(
                self.world, body, accepted=False,
                error_code="client_upgrade_required",
                reason="This request was retired by contract v4.0. Upgrade the reader "
                       "build for the unified Mixing workflow.",
                next_action="upgrade_reader_for_mixing")
            self.publish_response(device_id, "workflow_upgrade_required", response)
            return
        entry = REGISTRY.get(request_type)
        if not entry:
            self.log.warn(f"req/{request_type} from {device_id}: unknown request type — "
                          f"no workflow side effect, no response (per contract)")
            return
        self.log.rx(f"req/{request_type} from {device_id} ({len(payload)} bytes)")

        ctx, req, session, response = {}, {}, None, None
        try:
            req, session = envelope.validate(self.world, self.log, device_id,
                                             request_type, payload, entry["is_login"],
                                             ctx)
            response = entry["handler"](self.world, self.log, req, session)
        except Replay as rep:
            self.publish_response(device_id, entry["response"], rep.response)
            return
        except Rejection as rej:
            extras = dict(rej.extra)
            if entry["reject_extras"]:
                base = entry["reject_extras"](self.world)
                base.update(extras)
                extras = base
            source = req or ctx
            fallback = {"messageId": source.get("messageId"), "deviceId": device_id,
                        "operatorSessionId": source.get("operatorSessionId", ""),
                        "correlationKey": source.get("correlationKey")}
            response = build_response(
                self.world, fallback,
                accepted=False, error_code=rej.error_code, reason=rej.reason,
                next_action=rej.next_action, response_extras=extras,
                session_id=session["sessionId"] if session else None)
            req = source

        # step 8: replay storage (only when a replay identity existed)
        topic = self.publish_response(device_id, entry["response"], response)
        if req.get("_bodyHash"):
            self.world.replay_store(device_id, request_type, req["messageId"],
                                    req["_bodyHash"], topic, response)
        if response.get("accepted") and entry["mutating"]:
            self.world.log = None  # never serialize the logger
            snapshot = self.world.to_dict()
            self.world.log = self.log
            self.log.snapshot(snapshot, f"{request_type}-{req.get('messageId', 'na')}")

    # ------------------------------------------------------------- run ----
    def run(self):
        self.log.ok(f"Station 2 backend simulator starting "
                    f"(schema {envelope.SCHEMA_VERSION}, contract v4) — "
                    f"window ±{self.world.config['timestampWindowSeconds']}s, "
                    f"tolerance {self.world.config['overCollectionToleranceBags']} bag(s)")
        self.log.ok(f"logs: {self.log.run_dir}")
        self.client.connect(self.args.host, self.args.port, keepalive=30)
        self.client.loop_start()

        # collision guard: give a retained 'online' from the real backend 2s to arrive
        if self.real_station_seen.wait(timeout=2.0):
            self.log.fail("REAL STATION 2 APPEARS ONLINE on this broker "
                          f"({STATUS_TOPIC} is retained 'online'). Both backends would "
                          f"answer every request!")
            if self.args.yield_to_real:
                self.log.fail("--yield-to-real set: exiting without announcing.")
                self.client.loop_stop()
                self.client.disconnect()
                return 1
            self.log.warn("continuing anyway (no --yield-to-real); expect duplicate responses "
                          "if the real backend is truly alive")
        self.announce()

        worker = threading.Thread(target=self.worker, daemon=True, name="sim-worker")
        worker.start()
        self.log.ok("ready — waiting for handheld traffic (Ctrl+C to stop)")
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            self.log.warn("shutting down: publishing retained 'offline'")
            props = Properties(PacketTypes.PUBLISH)
            self.client.publish(STATUS_TOPIC, "offline", qos=1, retain=True,
                                properties=props).wait_for_publish(timeout=3)
            self.log.wire("out", STATUS_TOPIC, "offline")
            self.queue.put(None)
            self.client.loop_stop()
            self.client.disconnect()
            self.log.close()
        return 0


def main():
    parser = argparse.ArgumentParser(description="Station 2 MQTT backend simulator (contract v4)")
    parser.add_argument("--host", default="mqtt.sysone.co.za", help="MQTT broker host")
    parser.add_argument("--port", type=int, default=1883, help="MQTT broker port")
    parser.add_argument("--window", type=int, default=None,
                        help="timestamp acceptance window in seconds (default from seed: 300)")
    parser.add_argument("--tolerance", type=float, default=None,
                        help="over-collection tolerance in full bags (default from seed: 1.0)")
    parser.add_argument("--yield-to-real", action="store_true",
                        help="exit instead of announcing if the real Station 2 is online")
    parser.add_argument("--no-color", action="store_true", help="disable console colours")
    args = parser.parse_args()
    sys.exit(Simulator(args).run())


if __name__ == "__main__":
    main()
