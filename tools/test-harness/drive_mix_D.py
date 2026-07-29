import ui, time, subprocess
ADB = r"C:/Users/Jonathan/AppData/Local/Android/Sdk/platform-tools/adb.exe"
def scan(code):
    subprocess.run([ADB,"shell","am","broadcast","-a","com.scanner.broadcast","--es","data",code], capture_output=True)
# select the ready mix
ui.tap("MIX_000001", exact=False)
time.sleep(1.2)
# scan a production extruder
scan("EXT-03")
ok = ui.wait_for(lambda ls: any("Extruder 3" in x for x in ls) and any(x.strip() in ("Assign","Start") for x in ls), timeout=12)
time.sleep(1)
print("SHEET opened:", ok)
print("SHEET LABELS:", ui.labels())
ui.screenshot("../../docs/test-runs/2026-07-27/shots/E-assign-sheet.png")
