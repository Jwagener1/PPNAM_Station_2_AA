"""Reliable UI driving for the Station 2 handheld.

sweep2.find() locates a node by text anywhere on screen, which is fine for unique labels
but wrong for two things this app does constantly:

  * The top bar title often repeats a control's label ("Log In", "Mixing", "Settings").
    Tapping the title silently does nothing, and the test then reports a false failure.
  * Content sits in a verticalScroll under an IME-aware inset. With the keyboard open the
    button below the form is clipped to a few pixels and its Text child drops out of the
    accessibility tree entirely, so a plain find() returns nothing at all.

Everything here works on the raw uiautomator XML rather than sweep2's parsed rows, because
the clickable node is frequently an unlabelled Compose `View` whose label lives in a child.
"""
import html
import re
import time

import sweep2 as s

TOP_BAR_MAX_Y = 300   # anything above this is the app bar, never a content control


def dump_xml(retries=3):
    for _ in range(retries):
        s.sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        x = s.sh("shell", "cat", "/sdcard/ui.xml")
        if "<node" in x:
            return x
        time.sleep(0.6)
    raise RuntimeError("uiautomator produced no usable dump")


def _attr(tag, key):
    m = re.search(key + r'="([^"]*)"', tag)
    # uiautomator emits XML-escaped attributes, so a button reading "Test & Apply" arrives
    # as "Test &amp; Apply". Matching the on-screen string without unescaping silently fails
    # to find it and the test reports a control that is plainly there as missing.
    return html.unescape(m.group(1)) if m else ""


def _bounds(tag):
    m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return {"x1": x1, "y1": y1, "x2": x2, "y2": y2,
            "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
            "w": x2 - x1, "h": y2 - y1}


def elements(xml=None):
    """Every node with a label or a clickable box, with geometry."""
    xml = xml or dump_xml()
    out = []
    for m in re.finditer(r"<node[^>]*>", xml):
        tag = m.group(0)
        b = _bounds(tag)
        if not b:
            continue
        label = _attr(tag, "text") or _attr(tag, "content-desc")
        clickable = _attr(tag, "clickable") == "true"
        if label or clickable:
            out.append({"label": label, "clickable": clickable,
                        "cls": _attr(tag, "class").split(".")[-1], **b})
    return out


def labels(xml=None):
    return [e["label"] for e in elements(xml) if e["label"]]


def ime_open():
    """True when an input method window is showing."""
    out = s.sh("shell", "dumpsys", "input_method")
    m = re.search(r"mInputShown=(true|false)", out)
    if m:
        return m.group(1) == "true"
    return "mIsInputViewShown=true" in out


def close_ime():
    """Back closes the keyboard; on this app that is the first of Back's two stages."""
    if ime_open():
        s.sh("shell", "input", "keyevent", "4")
        time.sleep(1.2)
        return True
    return False


def find(label, exact=True, content_only=True, xml=None):
    """The element carrying `label`, preferring a content control over a top-bar title."""
    els = elements(xml)
    hits = [e for e in els
            if (e["label"] == label if exact else label.lower() in e["label"].lower())]
    if content_only:
        below = [e for e in hits if e["cy"] > TOP_BAR_MAX_Y]
        if below:
            hits = below
    return hits[0] if hits else None


def tappable_for(label, xml=None, exact=True):
    """The clickable box owning `label` — the label itself, or the smallest clickable
    ancestor box containing it. Compose buttons are unlabelled Views wrapping a Text."""
    xml = xml or dump_xml()
    el = find(label, exact=exact, xml=xml)
    if el is None:
        return None
    if el["clickable"]:
        return el
    holders = [e for e in elements(xml)
               if e["clickable"]
               and e["x1"] <= el["cx"] <= e["x2"]
               and e["y1"] <= el["cy"] <= e["y2"]]
    if holders:
        return min(holders, key=lambda e: e["w"] * e["h"])
    return el   # tap the label itself and let Compose's touch target sort it out


def tap(label, scroll=True, tries=4, settle=0.9, exact=True):
    """Close the IME, scroll the control into view if needed, tap it. True if tapped.

    `exact=False` matches a substring — needed for rows that compose their label from
    several fields ("COL_000001 · 0%") where only the stable part is known up front.
    """
    close_ime()
    for attempt in range(tries):
        t = tappable_for(label, exact=exact)
        if t and t["h"] > 40:           # a clipped control is not safely tappable
            s.tap(t["cx"], t["cy"])
            time.sleep(settle)
            return True
        if not scroll:
            break
        # Swipe from a neutral column to avoid starting the gesture inside a text field.
        s.sh("shell", "input", "swipe", "80", "1300", "80", "800", "260")
        time.sleep(0.7)
    return False


def type_into(label, text, clear=True):
    """Focus the field carrying `label` and type. Clears first by default."""
    t = tappable_for(label)
    if not t:
        return False
    s.tap(t["cx"], t["cy"])
    time.sleep(0.6)
    if clear:
        for _ in range(40):
            s.sh("shell", "input", "keyevent", "67")
    s.sh("shell", "input", "text", text.replace(" ", "%s"))
    time.sleep(0.4)
    return True


def wait_for(predicate, timeout=30, interval=2.0):
    """Poll until predicate(labels) is truthy. Returns the labels, or None on timeout."""
    end = time.time() + timeout
    while time.time() < end:
        ls = labels()
        if predicate(ls):
            return ls
        time.sleep(interval)
    return None


def pill():
    """The connection pill's current reading, or None."""
    known = ("Offline", "Reconnecting", "Connected", "Station 2 offline", "Clock out of sync")
    for l in labels():
        if l in known:
            return l
    return None


def screenshot(path):
    s.sh("shell", "screencap", "-p", "/sdcard/_shot.png")
    s.sh("pull", "/sdcard/_shot.png", path)
