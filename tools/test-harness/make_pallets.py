"""Regenerate pallets.json from the barcode generator's embedded pallet table.

The generator (C:\\Dev\\Barcode_Generator\\barcodes.html) holds the pallet inventory as a
JavaScript object-literal array with unquoted keys, which json.loads cannot read directly.
This lifts that block out, quotes the keys, and writes plain JSON for collect.py and the
RFID sweep.

    python make_pallets.py [path-to-barcodes.html]

Also reports data anomalies it finds on the way through. They are not fatal — the file is
still written — but they matter: a pallet whose remaining quantity exceeds what it started
with, or whose kg-per-bag disagrees with the BOM, is exactly the class of problem the
D-INT bag-math test exists to catch.
"""
import json
import os
import re
import sys

DEFAULT_SRC = r"C:\Dev\Barcode_Generator\barcodes.html"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "pallets.json")

# Fields collect.py and the sweep actually read. Anything missing is a hard error —
# silently writing a file that fails later at the pallet is worse than failing here.
REQUIRED = ("rfid_tag", "pallet_id", "item_code", "item_name", "original_quantity",
            "remaining_quantity", "bags", "location", "is_blocked")


def extract(html):
    """Pull `var PALLETS = [ ... ];` out of the page and parse it as JSON."""
    m = re.search(r"var\s+PALLETS\s*=\s*(\[.*?\]);", html, re.S)
    if not m:
        raise SystemExit("Could not find `var PALLETS = [...]` — has the generator changed?")
    body = m.group(1)
    # Unquoted JS keys -> quoted JSON keys. Values are all double-quoted strings already.
    body = re.sub(r"([{,])\s*([A-Za-z_][A-Za-z0-9_]*)\s*:", r'\1"\2":', body)
    return json.loads(body)


def anomalies(pallets):
    out = []
    for p in pallets:
        pid = p["pallet_id"]
        try:
            orig = float(p["original_quantity"])
            rem = float(p["remaining_quantity"])
            bags = float(p["bags"])
        except (TypeError, ValueError):
            out.append((pid, "unparseable quantity/bags"))
            continue
        if bags <= 0:
            out.append((pid, f"bags = {p['bags']} — kg/bag cannot be derived"))
            continue
        if rem > orig:
            out.append((pid, f"remaining {rem} exceeds original {orig}"))
        if orig <= 0:
            out.append((pid, f"original_quantity = {orig}"))
    return out


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC
    if not os.path.exists(src):
        raise SystemExit(f"Source not found: {src}")

    with open(src, encoding="utf-8") as fh:
        pallets = extract(fh.read())

    missing = {f for p in pallets for f in REQUIRED if f not in p}
    if missing:
        raise SystemExit(f"Records are missing required field(s): {sorted(missing)}")

    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(pallets, fh, indent=1)

    locs = {}
    for p in pallets:
        locs[p["location"]] = locs.get(p["location"], 0) + 1
    usable = sum(1 for p in pallets
                 if p["is_blocked"] == "0" and p["location"] in ("Holding", "Mixing"))
    blocked = sum(1 for p in pallets if p["is_blocked"] != "0")

    print(f"wrote {OUT}")
    print(f"  {len(pallets)} pallets, {len(set(p['item_code'] for p in pallets))} distinct materials")
    print(f"  collectable now (unblocked, in Holding/Mixing): {usable}")
    print(f"  blocked: {blocked}")
    print("  by location: " + ", ".join(f"{k}={v}" for k, v in sorted(locs.items())))

    bad = anomalies(pallets)
    if bad:
        # Plain ASCII: the Windows console is cp1252 and mangles an em dash here.
        print(f"\n  {len(bad)} data anomal{'y' if len(bad) == 1 else 'ies'} "
              f"(file still written; these are for the D-INT test):")
        for pid, why in bad:
            print(f"    {pid}: {why}")


if __name__ == "__main__":
    main()
