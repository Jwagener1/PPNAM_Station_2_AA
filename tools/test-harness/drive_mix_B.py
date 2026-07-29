import ui, time, subprocess
ADB = r"C:/Users/Jonathan/AppData/Local/Android/Sdk/platform-tools/adb.exe"
def scan(code):
    subprocess.run([ADB,"shell","am","broadcast","-a","com.scanner.broadcast","--es","data",code], capture_output=True)
# confirm start
ui.tap("Start")
time.sleep(2.5)
print("AFTER START labels:", [x for x in ui.labels() if any(k in x for k in ("MXR-01","cycle","Cycle","InUse","Active","Mixing","mix"))][:12])
ui.screenshot("../../docs/test-runs/2026-07-27/shots/E-mixer-started.png")
# scan same mixer again -> cycle sheet
scan("MXR-01")
ui.wait_for(lambda ls: any("cycle" in x.lower() or x.strip()=="Finish cycle" or "Active cycle" in x for x in ls), timeout=12)
time.sleep(1)
print("CYCLE SHEET labels:", ui.labels())
ui.screenshot("../../docs/test-runs/2026-07-27/shots/E25-cycle-sheet.png")
