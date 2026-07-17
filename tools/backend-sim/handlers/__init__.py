"""Request-type registry: maps each req/{type} leaf to its handler, its
res/{type} leaf, and the extra fields a REJECTED response must still carry
(the contract makes the Hopper board mandatory on several response types
even when the request fails)."""

from handlers import (allocations, auth, completion, cycles, ingredients,
                      jobcards, pallets)


def _board(world):
    return {"hoppers": world.hopper_board()}


def _board_and_empty_cycles(world):
    return {"cycles": [], "conflicts": [], "hoppers": world.hopper_board()}


def _scan_reject(world):
    return {"requiresManagerApproval": False, "hoppers": world.hopper_board()}


REGISTRY = {
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
        "is_login": False, "mutating": False, "reject_extras": _board},
    "open_sap_job_cards_requested": {
        "handler": jobcards.open_sap_list, "response": "open_sap_job_cards_list",
        "is_login": False, "mutating": False, "reject_extras": None},
    "job_card_load_requested": {
        "handler": jobcards.load, "response": "bom_loaded",
        "is_login": False, "mutating": True, "reject_extras": _board},
    "collection_resume_requested": {
        "handler": jobcards.resume, "response": "bom_loaded",
        "is_login": False, "mutating": False, "reject_extras": _board},
    "ingredient_collection_cancel_requested": {
        "handler": jobcards.cancel, "response": "ingredient_collection_cancel_result",
        "is_login": False, "mutating": True, "reject_extras": None},
    "ingredient_scan_requested": {
        "handler": ingredients.scan, "response": "ingredient_scan_result",
        "is_login": False, "mutating": True, "reject_extras": _scan_reject},
    "hopper_overview_requested": {
        "handler": cycles.hopper_overview, "response": "hopper_overview_result",
        "is_login": False, "mutating": False, "reject_extras": _board},
    "allocation_overview_requested": {
        "handler": allocations.overview, "response": "allocation_overview_result",
        "is_login": False, "mutating": False, "reject_extras": None},
    "machine_cycle_start_requested": {
        "handler": cycles.start, "response": "machine_cycle_start_result",
        "is_login": False, "mutating": True, "reject_extras": _board_and_empty_cycles},
    "machine_cycle_finish_requested": {
        "handler": cycles.finish, "response": "machine_cycle_finish_result",
        "is_login": False, "mutating": True, "reject_extras": _board},
    "machine_cycle_force_close_requested": {
        "handler": cycles.force_close, "response": "machine_cycle_force_close_result",
        "is_login": False, "mutating": True, "reject_extras": _board},
    "full_pallet_allocation_requested": {
        "handler": allocations.full_pallet, "response": "direct_allocation_result",
        "is_login": False, "mutating": True, "reject_extras": None},
    "bag_allocation_requested": {
        "handler": allocations.bags, "response": "direct_allocation_result",
        "is_login": False, "mutating": True, "reject_extras": None},
    "allocation_action_requested": {
        "handler": allocations.action, "response": "allocation_action_result",
        "is_login": False, "mutating": True, "reject_extras": None},
    "station2_work_complete_requested": {
        "handler": completion.work_complete, "response": "station2_work_complete_result",
        "is_login": False, "mutating": True, "reject_extras": None},
}
