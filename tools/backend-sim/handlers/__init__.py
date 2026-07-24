"""Request-type registry: maps each req/{type} leaf to its handler and its
res/{type} leaf. RETIRED_REQUEST_TYPES lists every v3 production mutation the
contract retired — sim.py answers those on res/workflow_upgrade_required."""

from handlers import auth, ingredients, jobcards, mixing, pallets, scram


def _scan_reject(world):
    # Envelope/session-layer scan rejections still carry the approval flag the
    # scanner branches on; business rejections come fully formed from the handler.
    return {"requiresManagerApproval": False}


REGISTRY = {
    # Contract v4.1 SCRAM-SHA-256. Both legs are "login-like" in the envelope sense: they run
    # before a session exists, so operatorSessionId is legitimately "" on them.
    "scram_start_requested": {
        "handler": scram.scram_start, "response": "scram_challenge",
        "is_login": True, "mutating": False, "reject_extras": None},
    "scram_proof_requested": {
        "handler": scram.scram_proof, "response": "operator_context",
        "is_login": True, "mutating": True, "reject_extras": None},
    "mix_destination_assignment_requested": {
        "handler": mixing.assign_destinations, "response": "mix_destination_assignment_result",
        "is_login": False, "mutating": True, "reject_extras": None},
    "login_requested": {
        "handler": auth.login, "response": "operator_context",
        "is_login": True, "mutating": True, "reject_extras": None},
    "reader_logout_requested": {
        "handler": auth.logout, "response": "operator_context",
        "is_login": False, "mutating": True, "reject_extras": None},
    "pallet_lookup_requested": {
        "handler": pallets.lookup, "response": "pallet_lookup_result",
        "is_login": False, "mutating": False, "reject_extras": None},
    "holding_recovery_requested": {
        "handler": pallets.recovery, "response": "holding_recovery_result",
        "is_login": False, "mutating": True, "reject_extras": None},
    "active_job_cards_requested": {
        "handler": jobcards.active_list, "response": "active_job_cards_list",
        "is_login": False, "mutating": False, "reject_extras": None},
    "open_sap_job_cards_requested": {
        "handler": jobcards.open_sap_list, "response": "open_sap_job_cards_list",
        "is_login": False, "mutating": False, "reject_extras": None},
    "job_card_load_requested": {
        "handler": jobcards.load, "response": "bom_loaded",
        "is_login": False, "mutating": True, "reject_extras": None},
    "collection_resume_requested": {
        "handler": jobcards.resume, "response": "bom_loaded",
        "is_login": False, "mutating": False, "reject_extras": None},
    "ingredient_collection_cancel_requested": {
        "handler": jobcards.cancel, "response": "ingredient_collection_cancel_result",
        "is_login": False, "mutating": True, "reject_extras": None},
    "ingredient_scan_requested": {
        "handler": ingredients.scan, "response": "ingredient_scan_result",
        "is_login": False, "mutating": True, "reject_extras": _scan_reject},
    "mixing_overview_requested": {
        "handler": mixing.overview, "response": "mixing_overview_result",
        "is_login": False, "mutating": False, "reject_extras": None},
    "machine_cycle_start_requested": {
        "handler": mixing.start, "response": "machine_cycle_result",
        "is_login": False, "mutating": True, "reject_extras": None},
    "machine_cycle_finish_requested": {
        "handler": mixing.finish, "response": "machine_cycle_result",
        "is_login": False, "mutating": True, "reject_extras": None},
    "machine_cycle_force_close_requested": {
        "handler": mixing.force_close, "response": "machine_cycle_result",
        "is_login": False, "mutating": True, "reject_extras": None},
}

# Contract §12: retired v3 production mutations. The real backend answers these
# on res/workflow_upgrade_required; so do we — a tripwire for any v3 call left
# in the app.
RETIRED_REQUEST_TYPES = frozenset({
    "hopper_overview_requested",
    "allocation_overview_requested",
    "full_pallet_allocation_requested",
    "bag_allocation_requested",
    "allocation_action_requested",
    "station2_work_complete_requested",
})
