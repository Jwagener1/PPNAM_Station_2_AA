"""Contract v4.1 SCRAM-SHA-256 (RFC 7677).

scram_start_requested -> scram_challenge
scram_proof_requested -> operator_context (login) | authorizationToken (manager_action)

Schema 4.1 rejects any message carrying a `password`/`managerPassword` property, so this is the
only way an operator logs in or a manager authorizes a privileged action.
"""

import base64
import hashlib
import hmac
import os
import unicodedata
from datetime import timedelta

from envelope import Rejection, build_response
from state import utc_now, iso
from .auth import _operator_context_fields

SCRAM_ITERATIONS = 4096
CHALLENGE_TTL_SECONDS = 60


def _salted_password(password, salt, iterations):
    """PBKDF2-HMAC-SHA-256 over the NFKC-normalized password's UTF-8 bytes."""
    normalized = unicodedata.normalize("NFKC", password).encode("utf-8")
    return hashlib.pbkdf2_hmac("sha256", normalized, salt, iterations)


def _hmac(key, data):
    return hmac.new(key, data, hashlib.sha256).digest()


def _escape_username(username):
    """RFC 5802 5.1: '=' before ',' — the other order re-escapes the '=' it just introduced."""
    return username.replace("=", "=3D").replace(",", "=2C")


def scram_start(world, log, req, session):
    """Issue a challenge.

    Deliberately issues one even for an unknown username: answering "no such user" here would
    make this a user-enumeration oracle. An unknown user gets a challenge from a deterministic
    decoy salt and fails at the proof step, exactly as a wrong password does.
    """
    username = req.get("username") or ""
    client_nonce = req.get("clientNonce") or ""
    purpose = req.get("purpose") or "login"
    action_target = req.get("actionTarget") or ""
    manager_action = req.get("managerAction") or ""

    if not username or not client_nonce:
        raise Rejection("validation_failed",
                        "username and clientNonce are required.", next_action="login")
    if purpose not in ("login", "manager_action"):
        raise Rejection("validation_failed", "Unknown purpose '%s'." % purpose)
    if purpose == "manager_action" and not (action_target and manager_action):
        raise Rejection("validation_failed",
                        "A manager_action challenge requires actionTarget and managerAction.")

    operator = world.find_operator(username=username)
    # Per-user stable salt so repeat logins agree; unknown users get a deterministic decoy.
    salt = hashlib.sha256(("ppnam-salt::" + username).encode("utf-8")).digest()[:16]
    server_nonce = client_nonce + base64.b64encode(os.urandom(18)).decode("ascii")
    server_first = "r=%s,s=%s,i=%d" % (
        server_nonce, base64.b64encode(salt).decode("ascii"), SCRAM_ITERATIONS)

    challenge_id = world.next_challenge_id()
    world.scram_challenges[challenge_id] = {
        "challengeId": challenge_id,
        "username": username,
        "deviceId": req["deviceId"],
        "clientNonce": client_nonce,
        "serverNonce": server_nonce,
        "salt": salt,
        "iterations": SCRAM_ITERATIONS,
        "serverFirstMessage": server_first,
        "purpose": purpose,
        "actionTarget": action_target,
        "managerAction": manager_action,
        "operatorId": operator["operatorId"] if operator else None,
        "createdAt": utc_now(),
        "used": False,
    }
    scope = ""
    if purpose == "manager_action":
        scope = " target=%s action=%s" % (action_target, manager_action)
    log.step("scram: issued challenge %s for '%s' purpose=%s%s"
             % (challenge_id, username, purpose, scope))

    return build_response(
        world, req,
        session_id=req.get("operatorSessionId", ""),
        response_extras={
            "challengeId": challenge_id,
            "serverNonce": server_nonce,
            "salt": base64.b64encode(salt).decode("ascii"),
            "iterations": SCRAM_ITERATIONS,
            "serverFirstMessage": server_first,
            "expiresAtUtc": iso(utc_now() + timedelta(seconds=CHALLENGE_TTL_SECONDS)),
        },
    )


def scram_proof(world, log, req, session):
    """Verify the client proof; issue a session or a scoped single-use authorization token."""
    challenge_id = req.get("challengeId")
    client_final = req.get("clientFinalWithoutProof") or ""
    client_proof_b64 = req.get("clientProof") or ""

    challenge = world.scram_challenges.get(challenge_id)
    if not challenge:
        log.fail("scram: unknown challengeId '%s'" % challenge_id)
        raise Rejection("permission_denied", "Authentication challenge is unknown or expired.",
                        next_action="login")
    # One use, 60 seconds — both checked before any crypto, so a replayed proof can never win.
    if challenge["used"]:
        log.fail("scram: challenge %s already used" % challenge_id)
        raise Rejection("permission_denied", "This authentication challenge was already used.",
                        next_action="login")
    age = (utc_now() - challenge["createdAt"]).total_seconds()
    if age > CHALLENGE_TTL_SECONDS:
        log.fail("scram: challenge %s expired (%.0fs old)" % (challenge_id, age))
        raise Rejection("permission_denied", "Authentication challenge expired.",
                        next_action="login")
    if challenge["deviceId"] != req["deviceId"]:
        log.fail("scram: challenge was issued to a different device")
        raise Rejection("device_mismatch", "This challenge belongs to another device.")
    challenge["used"] = True

    operator = world.operator_by_id(challenge["operatorId"]) if challenge["operatorId"] else None
    if not operator:
        log.fail("scram: no such operator '%s'" % challenge["username"])
        raise Rejection("permission_denied", "Invalid credentials.", next_action="login")

    salted = _salted_password(operator["password"], challenge["salt"], challenge["iterations"])
    client_key = _hmac(salted, b"Client Key")
    stored_key = hashlib.sha256(client_key).digest()
    auth_message = ("n=%s,r=%s,%s,%s" % (
        _escape_username(challenge["username"]),
        challenge["clientNonce"],
        challenge["serverFirstMessage"],
        client_final,
    )).encode("utf-8")
    client_signature = _hmac(stored_key, auth_message)
    expected_proof = bytes(a ^ b for a, b in zip(client_key, client_signature))

    try:
        supplied = base64.b64decode(client_proof_b64)
    except Exception:
        supplied = b""
    if not hmac.compare_digest(expected_proof, supplied):
        log.fail("scram: proof mismatch for '%s'" % challenge["username"])
        raise Rejection("permission_denied", "Invalid credentials.", next_action="login")

    server_key = _hmac(salted, b"Server Key")
    server_signature = base64.b64encode(_hmac(server_key, auth_message)).decode("ascii")

    if challenge["purpose"] == "manager_action":
        action_id = challenge["managerAction"]
        if action_id not in operator.get("allowedActions", []):
            log.fail("scram: %s may not perform '%s'" % (operator["operatorId"], action_id))
            raise Rejection("permission_denied",
                            "%s is not allowed to perform %s."
                            % (operator["displayName"], action_id))
        token = world.issue_authorization_token(
            operator=operator, device_id=req["deviceId"],
            manager_action=action_id, action_target=challenge["actionTarget"])
        log.ok("scram: manager token issued to %s for %s on %s"
               % (operator["operatorId"], action_id, challenge["actionTarget"]))
        return build_response(
            world, req,
            session_id=req.get("operatorSessionId", ""),
            response_extras={
                "serverSignature": server_signature,
                "authorizationToken": token["token"],
                "authorizationExpiresAtUtc": iso(token["expiresAt"]),
                "approverUserId": operator["operatorId"],
                "approverDisplayName": operator["displayName"],
                "approverRole": operator["role"],
            },
        )

    new_session = world.create_session(req["deviceId"], operator)
    log.ok("scram login: %s (%s) session %s"
           % (operator["operatorId"], operator["displayName"], new_session["sessionId"]))
    extras = {"serverSignature": server_signature}
    extras.update(_operator_context_fields(operator, new_session))
    return build_response(
        world, req,
        session_id=new_session["sessionId"],
        response_extras=extras,
    )
