"""One-off driver for the readyCollections area-scoping fix: log in, open Job Cards
sanity, then hop the mixing board between Main and JANDI to eyeball whether a
Main-planned collection leaks into JANDI's readyCollections. Paired with a wire sniff.
"""
import time
import ui

ui.type_into("Username", "operator1")
ui.type_into("Password", "pass")
ui.tap("Log In")
ui.wait_for(lambda ls: "Mixing" in ls or "Job Cards" in ls, timeout=15)
print("post-login labels:", ui.labels())
ui.screenshot("shots/area-scope-01-post-login.png")
