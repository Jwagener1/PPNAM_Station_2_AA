import ui, time
ui.tap("Assign")
ui.wait_for(lambda ls: any("Assigned" in x or "RUN_" in x for x in ls) or not any("Assign to" in x for x in ls), timeout=15)
time.sleep(2)
print("AFTER ASSIGN board:", [x for x in ui.labels() if any(k in x for k in ("MIX_000001","RUN_","Assigned","Active run","Ready mixes","EXT-03"))][:10])
ui.screenshot("../../docs/test-runs/2026-07-27/shots/E-after-assign.png")
