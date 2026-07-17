"""login_requested / reader_logout_requested -> operator_context"""

from envelope import Rejection, build_response


def _operator_context_fields(operator, session):
    return {
        "operatorId": operator["operatorId"],
        "username": operator["username"],
        "displayName": operator["displayName"],
        "role": operator["role"],
        "sessionState": session["state"],
        "sessionExpiresAtUtc": session["expiresAtUtc"],
        "allowedActions": operator["allowedActions"],
        "allowedTabs": operator["allowedTabs"],
    }


def login(world, log, req, session):
    username = req.get("username")
    password = req.get("password")
    badge = req.get("badgeTag")
    has_userpass = username is not None or password is not None
    has_badge = badge is not None
    if has_userpass == has_badge:
        raise Rejection("validation_failed",
                        "Exactly one authentication method must be supplied "
                        "(username+password OR badgeTag).", next_action="login")

    if has_badge:
        operator = world.find_operator(badge=badge)
        method = f"badge '{badge}'"
        ok = operator is not None
    else:
        operator = world.find_operator(username=username)
        method = f"username '{username}'"
        ok = operator is not None and operator["password"] == password
    if not ok:
        log.fail(f"login via {method}: invalid credentials")
        raise Rejection("permission_denied", "Invalid credentials.", next_action="login")

    new_session = world.create_session(req["deviceId"], operator)
    log.ok(f"login via {method}: {operator['operatorId']} ({operator['displayName']}) "
           f"session {new_session['sessionId']}")
    return build_response(
        world, req,
        session_id=new_session["sessionId"],
        response_extras=_operator_context_fields(operator, new_session),
    )


def logout(world, log, req, session):
    session["state"] = "Closed"
    operator = world.operator_by_id(session["operatorId"])
    log.transition(f"session {session['sessionId']} on {req['deviceId']} -> Closed (logout)")
    return build_response(
        world, req,
        session_id="",
        response_extras={
            "operatorId": operator["operatorId"] if operator else None,
            "username": operator["username"] if operator else None,
            "displayName": operator["displayName"] if operator else None,
            "role": operator["role"] if operator else None,
            "sessionState": "Closed",
            "sessionExpiresAtUtc": session["expiresAtUtc"],
            "allowedActions": [],
            "allowedTabs": [],
        },
    )
