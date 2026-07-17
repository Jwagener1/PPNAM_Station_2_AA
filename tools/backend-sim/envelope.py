"""Common JSON envelope handling: the contract's validation order, the
response envelope builder, and the privileged-action approval helper.

Validation order (RFID_MQTT_CONTRACT.md):
  1. JSON and envelope.
  2. Replay identity and body hash.
  3. Schema, topic device, configured device, and timestamp.
  4. Device-bound operator session.
  5. Permission or manager approval when required.   (handlers, via approve())
  6. Message-specific state and business validation. (handlers)
  7. Atomic persistence.                             (handlers)
  8. Response creation and replay storage.           (sim.py)
"""

from state import World, utc_now, iso, parse_iso

SCHEMA_VERSION = "3.0"

ENVELOPE_FIELDS = ("messageId", "schemaVersion", "deviceId", "operatorSessionId", "timestampUtc")


class Rejection(Exception):
    """Raised by handlers to short-circuit into a rejected response."""

    def __init__(self, error_code, reason, next_action="", **extra):
        super().__init__(reason)
        self.error_code = error_code
        self.reason = reason
        self.next_action = next_action
        self.extra = extra


class Replay(Exception):
    """Raised when a stored response should be re-published as-is."""

    def __init__(self, topic, response):
        super().__init__("replay")
        self.topic = topic
        self.response = response


def build_response(world, req, response_extras=None, accepted=True, error_code=None,
                   reason=None, next_action="", session_id=None, correlation=None):
    """Assemble the full response envelope around handler-provided fields."""
    resp = {
        "messageId": world.next_response_id(),
        "inResponseToMessageId": req.get("messageId"),
        "schemaVersion": SCHEMA_VERSION,
        "deviceId": req.get("deviceId"),
        "operatorSessionId": session_id if session_id is not None else req.get("operatorSessionId", ""),
        "timestampUtc": iso(utc_now()),
        "correlationKey": correlation if correlation is not None else req.get("correlationKey"),
        "accepted": accepted,
        "reason": reason,
        "errorCode": error_code,
        "nextAction": next_action,
    }
    if response_extras:
        resp.update(response_extras)
    return resp


def validate(world, log, topic_device, request_type, payload_bytes, is_login, ctx=None):
    """Run validation steps 1-4. Returns (req_dict, session_or_None).
    Raises Rejection (send error response) or Replay (resend stored response).
    `ctx` (a mutable dict) receives whatever envelope data was parsed before a
    rejection, so the error response can still be correlated to the request."""

    # -- step 1: JSON ------------------------------------------------------
    import json
    try:
        req = json.loads(payload_bytes.decode("utf-8"))
        if not isinstance(req, dict):
            raise ValueError("payload is not a JSON object")
    except (ValueError, UnicodeDecodeError) as e:
        log.fail(f"step 1 JSON: unparseable payload on req/{request_type}: {e}")
        raise Rejection("invalid_json", "Payload could not be parsed.") from e
    if ctx is not None:
        ctx.update(req)
    log.step(f"step 1 JSON: parsed ok ({len(payload_bytes)} bytes)")

    # -- step 1b: envelope fields -----------------------------------------
    missing = [f for f in ENVELOPE_FIELDS if f not in req or req[f] is None]
    if missing:
        log.fail(f"step 1 envelope: missing required field(s) {missing}")
        raise Rejection("invalid_envelope", f"Missing required envelope field(s): {', '.join(missing)}.")
    if req["operatorSessionId"] == "" and not is_login:
        log.fail("step 1 envelope: empty operatorSessionId only allowed on login_requested")
        raise Rejection("invalid_envelope",
                        'operatorSessionId may be "" only on login_requested.')
    log.step("step 1 envelope: all required fields present")

    # -- step 2: replay identity + body hash ------------------------------
    body_hash = World.body_hash(payload_bytes)
    stored = world.replay_lookup(topic_device, request_type, req["messageId"])
    if stored:
        if stored["hash"] == body_hash:
            log.warn(f"step 2 replay: duplicate of {topic_device}/{request_type}/{req['messageId']} "
                     f"with identical body -> re-sending stored response, no workflow action")
            raise Replay(stored["topic"], stored["response"])
        log.fail(f"step 2 replay: messageId '{req['messageId']}' reused with DIFFERENT content "
                 f"(stored hash {stored['hash'][:12]}…, got {body_hash[:12]}…)")
        raise Rejection("message_id_reused",
                        "This messageId was already used for a different request body.")
    log.step(f"step 2 replay: new operation (hash {body_hash[:12]})")
    req["_bodyHash"] = body_hash
    if ctx is not None:
        ctx["_bodyHash"] = body_hash

    # -- step 3: schema / topic device / configured device / timestamp ----
    if req["schemaVersion"] != SCHEMA_VERSION:
        log.fail(f"step 3 schema: got '{req['schemaVersion']}', require '{SCHEMA_VERSION}'")
        raise Rejection("unsupported_schema", f"schemaVersion must be exactly '{SCHEMA_VERSION}'.")
    if req["deviceId"] != topic_device:
        log.fail(f"step 3 device: payload deviceId '{req['deviceId']}' != topic device '{topic_device}'")
        raise Rejection("device_mismatch", "Payload deviceId differs from the MQTT topic.")
    if topic_device not in world.known_devices:
        # Permissive by design: the app's deviceId is its ANDROID_ID, unknowable
        # ahead of time. Auto-register and log loudly instead of rejecting.
        world.known_devices.add(topic_device)
        log.warn(f"step 3 configured: auto-registered new device '{topic_device}' "
                 f"(simulator accepts any device; the real backend requires configuration)")
    try:
        ts = parse_iso(req["timestampUtc"])
    except ValueError as e:
        log.fail(f"step 3 timestamp: unparseable '{req['timestampUtc']}'")
        raise Rejection("invalid_envelope", "timestampUtc is not valid ISO 8601.") from e
    skew = (utc_now() - ts).total_seconds()
    window = world.config["timestampWindowSeconds"]
    log.step(f"step 3 timestamp: device clock skew {skew:+.1f}s (window ±{window}s)")
    if abs(skew) > window:
        log.fail(f"step 3 timestamp: outside acceptance window")
        raise Rejection("message_expired", "Timestamp is outside the configured window.")
    log.step("step 3 schema/device/timestamp: ok")

    # -- step 4: device-bound operator session ----------------------------
    if is_login:
        log.step("step 4 session: skipped (login_requested)")
        return req, None
    session, err = world.get_session(topic_device, req["operatorSessionId"])
    if not session:
        log.fail(f"step 4 session: {err}")
        raise Rejection("session_required", f"No valid session on this device: {err}.",
                        next_action="login")
    log.step(f"step 4 session: {session['sessionId']} Active "
             f"(operator {session['operatorId']})")
    return req, session


def approve(world, log, req, action_id):
    """Step 5 for privileged actions. Returns approver fields for the response.
    Raises Rejection(permission_denied) when the approval fails, and a
    requiresManagerApproval rejection when credentials are absent."""
    username = req.get("managerUsername")
    password = req.get("managerPassword")
    if not username or not password:
        log.fail(f"step 5 permission: privileged action '{action_id}' sent without manager credentials")
        raise Rejection("permission_denied",
                        "This action requires manager approval credentials.",
                        next_action="retry_with_manager_approval",
                        requiresManagerApproval=True)
    approver, err = world.check_approver(username, password, action_id)
    if not approver:
        log.fail(f"step 5 permission: {err}")
        raise Rejection("permission_denied", f"Approval failed: {err}.",
                        next_action="retry_with_manager_approval",
                        requiresManagerApproval=True)
    if not req.get("auditReason"):
        log.fail("step 5 permission: privileged action missing auditReason")
        raise Rejection("validation_failed", "A privileged action requires an auditReason.")
    log.ok(f"step 5 permission: '{action_id}' approved by {approver['operatorId']} "
           f"({approver['displayName']}, role {approver['role']}); auditReason: {req['auditReason']!r}")
    return {
        "approverUserId": approver["operatorId"],
        "approverDisplayName": approver["displayName"],
        "approverRole": approver["role"],
    }


NO_APPROVER = {"approverUserId": None, "approverDisplayName": None, "approverRole": None}
