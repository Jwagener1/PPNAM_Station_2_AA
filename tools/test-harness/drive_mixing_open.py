import ui, time
ui.tap("Mixing")
ui.wait_for(lambda ls: any("Mixing" in x or "DOLCI" in x or "Main" in x or "Rajoo" in x or "Jandi" in x or "Mackie" in x for x in ls), timeout=20)
time.sleep(1.5)
print("AREA PICKER LABELS:")
for x in ui.labels(): print("  ", repr(x))
ui.screenshot("../../docs/test-runs/2026-07-27/shots/E2-area-picker.png")
