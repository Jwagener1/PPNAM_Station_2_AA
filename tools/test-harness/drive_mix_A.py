import ui, time, subprocess
ADB = r"C:/Users/Jonathan/AppData/Local/Android/Sdk/platform-tools/adb.exe"
def scan(code):
    subprocess.run([ADB,"shell","am","broadcast","-a","com.scanner.broadcast","--es","data",code], capture_output=True)
ui.tap("COL_000002", exact=False)
time.sleep(1.2)
scan("MXR-01")
ok = ui.wait_for(lambda ls: any("Mixer 1" in x for x in ls) and any(x.strip() in ("Start","Assign") for x in ls), timeout=12)
time.sleep(1)
print("start-sheet opened:", ok)
print("LABELS:", ui.labels())
ui.screenshot("../../docs/test-runs/2026-07-27/shots/E14-mixer-startconfirm.png")
