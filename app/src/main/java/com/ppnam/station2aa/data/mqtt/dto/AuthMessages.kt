package com.ppnam.station2aa.data.mqtt.dto

/**
 * v3 collapses v2's two login topics (reader_login_requested / login_tag_scanned) into one
 * `login_requested`. The authentication method is identified by which payload is sent — the
 * contract requires exactly one method per request.
 *
 * Message-specific fields only; the transport injects the envelope.
 */
data class CredentialsLoginPayload(
    val username: String,
    val password: String,
)

data class BadgeLoginPayload(
    val badgeTag: String,
)

/**
 * Response to both `login_requested` and `reader_logout_requested`.
 *
 * Note this DTO reads `operatorSessionId`, which is an envelope field rather than a body field —
 * the one deliberate overlap in the codebase. Login is where the session is issued, so the use case
 * has no other way to obtain it, and Gson parses envelope and body from the same flat JSON object.
 *
 * `role` is informational only. Nothing in the contract gates on it — see the privileged-actions
 * rules, which authorise on the approver's allowedActions, never on a role.
 *
 * `sessionState` and `sessionExpiresAtUtc` are deliberately unmapped here; sub-project 2 adds them
 * along with the session-lifecycle behaviour they drive. Gson ignores unmapped JSON fields.
 */
data class OperatorContextResponse(
    val operatorSessionId: String = "",
    val operatorId: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val role: String? = null,
    val allowedActions: List<String> = emptyList(),
    val allowedTabs: List<String> = emptyList(),
)
