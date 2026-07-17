"""In-memory world state for the Station 2 backend simulator.

Everything is plain dicts so the whole world can be JSON-snapshotted after
each mutation. State lives only for the process lifetime — a restart is a
fresh, repeatable test world.
"""

import glob
import hashlib
import json
import os
from datetime import datetime, timedelta, timezone


def utc_now():
    return datetime.now(timezone.utc)


def iso(dt):
    if dt is None:
        return None
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(s):
    """Accept 'Z' or offset ISO 8601."""
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    return datetime.fromisoformat(s)


class World:
    def __init__(self, seed_dir, log):
        self.log = log
        with open(os.path.join(seed_dir, "seed.json"), encoding="utf-8") as f:
            seed = json.load(f)
        self.config = seed["config"]
        self.operators = seed["operators"]
        self.materials = seed.get("materials", {})
        self.machines = {}
        for m in seed["machines"]:
            self.machines[m["machineCode"]] = {
                "displayName": m["displayName"],
                "machineCode": m["machineCode"],
                "family": m["family"],
                "status": m.get("status", "Available"),
                "inactiveReason": m.get("inactiveReason"),
                "cycleId": None,
                "collectionId": None,
                "preMixId": None,
                "runId": None,
                "jobCardNumber": None,
                "assignedAtUtc": None,
                "assignedByOperatorId": None,
                "assignedByDisplayName": None,
                "assignedFromDevice": None,
            }
        self.pallets = {p["palletRfidTag"]: p for p in seed["pallets"]}

        # SAP production orders from sample dumps
        self.sap_orders = {}
        for path in sorted(glob.glob(os.path.join(seed_dir, "prod-*.json"))):
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            for order in data.get("value", []):
                if "DocumentNumber" in order:
                    self.sap_orders[str(order["DocumentNumber"])] = order
        log.step(f"seed loaded: {len(self.operators)} operators, {len(self.machines)} machines, "
                 f"{len(self.pallets)} pallets, {len(self.sap_orders)} SAP orders "
                 f"({', '.join(self.sap_orders)})")

        # dynamic state
        self.sessions = {}          # deviceId -> session dict
        self.presence = {}          # deviceId -> "online"/"offline"
        self.collections = {}       # COL_ -> collection dict
        self.premixes = {}          # PMX_ -> premix dict
        self.cycles = {}            # CYC_ -> cycle dict
        self.runs = {}              # RUN_ -> run dict
        self.allocations = {}       # ALLOC_ -> allocation dict
        self.local_jobs = {}        # poDocNum -> {"status": ...}
        self.replay = {}            # (device, reqType, messageId) -> {"hash", "topic", "response"}
        self.known_devices = set()  # auto-registered handheld ids
        self._counters = {"COL": 0, "PMX": 0, "CYC": 0, "ROUTE": 0, "RUN": 0, "ALLOC": 0, "RSP": 0}

    # ---- ids ------------------------------------------------------------
    def next_id(self, kind):
        self._counters[kind] += 1
        return f"{kind}_{self._counters[kind]:06d}"

    def next_response_id(self):
        self._counters["RSP"] += 1
        return f"S2-{self._counters['RSP']:06d}"

    # ---- operators / sessions -------------------------------------------
    def find_operator(self, username=None, badge=None):
        for op in self.operators:
            if username is not None and op["username"] == username:
                return op
            if badge is not None and op.get("badgeTag") == badge:
                return op
        return None

    def operator_by_id(self, operator_id):
        for op in self.operators:
            if op["operatorId"] == operator_id:
                return op
        return None

    def create_session(self, device_id, operator):
        old = self.sessions.get(device_id)
        if old and old["state"] != "Closed":
            old["state"] = "Closed"
            self.log.transition(f"session {old['sessionId']} on {device_id} -> Closed (replaced by new login)")
        now = utc_now()
        session = {
            "sessionId": f"SES-{device_id}-{now.strftime('%Y%m%d%H%M%S')}",
            "deviceId": device_id,
            "operatorId": operator["operatorId"],
            "state": "Active",
            "createdAtUtc": iso(now),
            "expiresAtUtc": iso(now + timedelta(hours=self.config["sessionHours"])),
        }
        self.sessions[device_id] = session
        self.log.transition(f"session {session['sessionId']} on {device_id} -> Active "
                            f"(operator {operator['operatorId']} {operator['displayName']}, "
                            f"expires {session['expiresAtUtc']})")
        return session

    def get_session(self, device_id, session_id):
        """Return the session if valid+usable for this device, resuming a Suspended one.
        Returns (session, error) where error is None or a reason string."""
        s = self.sessions.get(device_id)
        if not s or s["sessionId"] != session_id:
            return None, "no session with that id exists on this device"
        if parse_iso(s["expiresAtUtc"]) <= utc_now():
            if s["state"] != "Closed":
                s["state"] = "Closed"
                self.log.transition(f"session {s['sessionId']} on {device_id} -> Closed (expired)")
            return None, "session has expired"
        if s["state"] == "Closed":
            return None, "session is closed"
        if s["state"] == "Suspended":
            s["state"] = "Active"
            self.log.transition(f"session {s['sessionId']} on {device_id} -> Active "
                                f"(resumed by valid request; presence is a hint, not a gate)")
        return s, None

    def presence_change(self, device_id, status):
        prev = self.presence.get(device_id)
        self.presence[device_id] = status
        if prev != status:
            self.log.transition(f"presence {device_id}: {prev or '(unseen)'} -> {status}")
        s = self.sessions.get(device_id)
        if not s or s["state"] == "Closed":
            return
        if status == "offline" and s["state"] == "Active":
            s["state"] = "Suspended"
            self.log.transition(f"session {s['sessionId']} on {device_id} -> Suspended (device went offline)")
        elif status == "online" and s["state"] == "Suspended":
            s["state"] = "Active"
            self.log.transition(f"session {s['sessionId']} on {device_id} -> Active (device back online)")

    # ---- privileged approval ----------------------------------------------
    def check_approver(self, username, password, action_id):
        """Authenticate manager credentials and check the APPROVER's allowedActions.
        Returns (approver_or_None, reason_or_None)."""
        op = self.find_operator(username=username)
        if not op or op["password"] != password:
            return None, "approver credentials are invalid"
        if action_id not in op["allowedActions"]:
            return None, f"approver '{username}' does not hold action id '{action_id}'"
        return op, None

    # ---- materials / bag weights -------------------------------------------
    def full_bag_weight(self, material_code):
        """None means bulk material (no bag size)."""
        mat = self.materials.get(material_code, {})
        if mat.get("bulk"):
            return None
        return mat.get("fullBagWeight", self.config["defaultFullBagWeightKg"])

    def available_quantity(self, material_code):
        """Sum of remaining quantity across usable Holding pallets of this product."""
        total = 0.0
        for p in self.pallets.values():
            if (p["productCode"] == material_code and p["palletState"] == "Holding"
                    and not p["blocked"]):
                total += p["remainingQuantity"]
        return round(total, 3)

    # ---- pallet helpers ------------------------------------------------------
    @staticmethod
    def pallet_usable(p):
        return (p["palletState"] in ("Holding", "Mixing") and not p["blocked"]
                and p["remainingQuantity"] > 0)

    @staticmethod
    def pallet_recoverable(p):
        return p["palletState"] in ("AtStation1", "Unknown")

    # ---- hopper board -----------------------------------------------------------
    def hopper_board(self):
        board = []
        for m in self.machines.values():
            if m["family"] != "Hopper":
                continue
            board.append({
                "displayName": m["displayName"],
                "machineCode": m["machineCode"],
                "status": m["status"],
                "isAvailable": m["status"] == "Available",
                "cycleId": m["cycleId"],
                "collectionId": m["collectionId"],
                "preMixId": m["preMixId"],
                "jobCardNumber": m["jobCardNumber"],
                "assignedAtUtc": m["assignedAtUtc"],
                "assignedByOperatorId": m["assignedByOperatorId"],
                "assignedByDisplayName": m["assignedByDisplayName"],
                "assignedFromDevice": m["assignedFromDevice"],
                "inactiveReason": m["inactiveReason"],
            })
        return board

    # ---- replay store --------------------------------------------------------
    @staticmethod
    def body_hash(payload_bytes):
        return hashlib.sha256(payload_bytes).hexdigest()

    def replay_lookup(self, device_id, request_type, message_id):
        return self.replay.get((device_id, request_type, message_id))

    def replay_store(self, device_id, request_type, message_id, body_hash, topic, response):
        self.replay[(device_id, request_type, message_id)] = {
            "hash": body_hash, "topic": topic, "response": response,
        }

    # ---- snapshotting -----------------------------------------------------------
    def to_dict(self):
        return {
            "sessions": self.sessions,
            "presence": self.presence,
            "machines": self.machines,
            "pallets": self.pallets,
            "collections": self.collections,
            "premixes": self.premixes,
            "cycles": self.cycles,
            "runs": self.runs,
            "allocations": self.allocations,
            "localJobs": self.local_jobs,
            "counters": self._counters,
            "knownDevices": sorted(self.known_devices),
        }
