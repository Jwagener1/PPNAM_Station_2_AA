import ui, time
ui.tap("Finish cycle")
time.sleep(2.5)
print("AFTER FINISH labels:", ui.labels())
ui.screenshot("../../docs/test-runs/2026-07-27/shots/E25-after-finish.png")
