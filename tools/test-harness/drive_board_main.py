import ui, time
ui.tap("Main Mixing Room")
time.sleep(2)
print("MAIN BOARD LABELS:")
for x in ui.labels(): print("  ", repr(x))
ui.screenshot("../../docs/test-runs/2026-07-27/shots/E-main-board.png")
