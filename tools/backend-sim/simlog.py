"""Logging subsystem for the Station 2 backend simulator.

Four channels per run, all under logs/<UTC-timestamp>/:
  wire.jsonl        every MQTT message in/out, full payload, passwords redacted
  sim.log           human-readable validation traces, decisions, state transitions
  state-snapshots/  full world state after every accepted mutation
  console           colour-coded mirror of sim.log
"""

import json
import os
import sys
import threading
from datetime import datetime, timezone

REDACT_KEYS = {"password", "managerPassword"}

COLORS = {
    "IN":    "\033[96m",   # cyan
    "OUT":   "\033[94m",   # blue
    "OK":    "\033[92m",   # green
    "STEP":  "\033[37m",   # grey
    "WARN":  "\033[93m",   # yellow
    "FAIL":  "\033[91m",   # red
    "STATE": "\033[95m",   # magenta
}
RESET = "\033[0m"


def utc_now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def redact(obj):
    """Deep-copy obj with credential values replaced but their presence preserved."""
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if k in REDACT_KEYS:
                out[k] = "<redacted:present>" if v else "<redacted:empty>"
            else:
                out[k] = redact(v)
        return out
    if isinstance(obj, list):
        return [redact(v) for v in obj]
    return obj


class SimLogger:
    def __init__(self, base_dir, color=True):
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
        self.run_dir = os.path.join(base_dir, "logs", stamp)
        self.snap_dir = os.path.join(self.run_dir, "state-snapshots")
        os.makedirs(self.snap_dir, exist_ok=True)
        self._wire = open(os.path.join(self.run_dir, "wire.jsonl"), "a", encoding="utf-8")
        self._log = open(os.path.join(self.run_dir, "sim.log"), "a", encoding="utf-8")
        self._lock = threading.Lock()
        self._snap_seq = 0
        self.color = color and sys.stdout.isatty()

    # ---- wire channel -------------------------------------------------
    def wire(self, direction, topic, payload, qos=1, note=None):
        """payload: dict, str, or bytes. Logged in full (redacted)."""
        entry = {"ts": utc_now_iso(), "dir": direction, "topic": topic, "qos": qos}
        if isinstance(payload, (bytes, bytearray)):
            try:
                payload = payload.decode("utf-8")
            except UnicodeDecodeError:
                entry["payloadHex"] = payload.hex()
                payload = None
        if isinstance(payload, str):
            try:
                payload = json.loads(payload)
            except (json.JSONDecodeError, ValueError):
                entry["payloadRaw"] = payload
                payload = None
        if isinstance(payload, dict):
            entry["payload"] = redact(payload)
            if "inResponseToMessageId" in payload:
                entry["inResponseToMessageId"] = payload["inResponseToMessageId"]
            if "messageId" in payload:
                entry["messageId"] = payload["messageId"]
        elif isinstance(payload, list):
            entry["payload"] = redact(payload)
        with self._lock:
            self._wire.write(json.dumps(entry, ensure_ascii=False) + "\n")
            self._wire.flush()

    # ---- narrative channel --------------------------------------------
    def _emit(self, tag, msg):
        line = f"{utc_now_iso()} [{tag:5s}] {msg}"
        with self._lock:
            self._log.write(line + "\n")
            self._log.flush()
        if self.color:
            print(f"{COLORS.get(tag, '')}{line}{RESET}")
        else:
            print(line)

    def rx(self, msg):        self._emit("IN", msg)
    def tx(self, msg):        self._emit("OUT", msg)
    def ok(self, msg):        self._emit("OK", msg)
    def step(self, msg):      self._emit("STEP", msg)
    def warn(self, msg):      self._emit("WARN", msg)
    def fail(self, msg):      self._emit("FAIL", msg)
    def transition(self, msg): self._emit("STATE", msg)

    # ---- snapshot channel ----------------------------------------------
    def snapshot(self, world_dict, event):
        self._snap_seq += 1
        safe_event = "".join(c if c.isalnum() or c in "-_" else "_" for c in event)[:60]
        path = os.path.join(self.snap_dir, f"{self._snap_seq:04d}-{safe_event}.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(redact(world_dict), f, indent=2, ensure_ascii=False, default=str)
        self.step(f"state snapshot #{self._snap_seq} written ({event})")

    def close(self):
        with self._lock:
            self._wire.close()
            self._log.close()
