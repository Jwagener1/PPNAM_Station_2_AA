"""mixing_overview_requested -> mixing_overview_result
machine_cycle_{start,finish,force_close}_requested -> machine_cycle_result

Implemented in the next task; the registry imports these names now so the
capture-only simulator already runs as a valid v4 backend."""

from envelope import Rejection


def overview(world, log, req, session):
    raise Rejection("validation_failed", "mixing_overview is not implemented yet.")


def start(world, log, req, session):
    raise Rejection("validation_failed", "machine_cycle_start is not implemented yet.")


def finish(world, log, req, session):
    raise Rejection("validation_failed", "machine_cycle_finish is not implemented yet.")


def force_close(world, log, req, session):
    raise Rejection("validation_failed", "machine_cycle_force_close is not implemented yet.")
