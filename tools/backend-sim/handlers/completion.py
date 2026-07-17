"""station2_work_complete_requested -> station2_work_complete_result"""

from envelope import Rejection, build_response
from handlers.cycles import _job_has_active_run
from state import iso, utc_now


def work_complete(world, log, req, session):
    po = str(req.get("productionOrderDocumentNumber") or "")
    if not po:
        raise Rejection("validation_failed", "productionOrderDocumentNumber is required.")
    if po not in world.sap_orders:
        raise Rejection("not_found", f"Production order '{po}' was not found.")
    if _job_has_active_run(world, po):
        active = [r["machineCode"] for r in world.runs.values()
                  if r["active"] and str(r["productionOrderDocumentNumber"]) == po]
        raise Rejection("state_conflict",
                        f"Job {po} still has active run(s) on {active}; finish them first.")
    existing = world.local_jobs.get(po)
    if existing and existing["status"] == "Station2Completed":
        log.warn(f"work complete on {po}: already Station2Completed -> idempotent success")
        completed_at = existing["completedAtUtc"]
    else:
        completed_at = iso(utc_now())
        world.local_jobs[po] = {"status": "Station2Completed", "completedAtUtc": completed_at}
        log.transition(f"local job {po}: -> Station2Completed "
                       f"(local only; SAP production order NOT closed, no SAP issue queued)")
    return build_response(world, req, correlation=po, response_extras={
        "productionOrderDocumentNumber": po,
        "localJobStatus": "Station2Completed",
        "completedAtUtc": completed_at,
        "sapProductionOrderChanged": False,
        "sapIssueQueued": False,
    })
