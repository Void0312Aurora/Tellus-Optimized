#!/usr/bin/env python3
"""Submit one bounded test request and retain its verified camera/render state."""
import argparse
import json
from pathlib import Path
import re
import shutil
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("request", help='JSON object, including a unique "id"')
parser.add_argument("--instance", default="tellus-cube-net-unfold-20261031")
parser.add_argument("--evidence", type=Path)
parser.add_argument("--timeout", type=float, default=40)
args = parser.parse_args()
request = json.loads(args.request)
identifier = request["id"]
if not re.fullmatch(r"[A-Za-z0-9_-]{1,90}", identifier):
    raise SystemExit("Invalid request ID")
root = Path.home() / ".local/share/PrismLauncher/instances" / args.instance / ".minecraft/tellus-render-probe"
root.mkdir(exist_ok=True)
result = root / f"{identifier}.json"
if result.exists():
    raise SystemExit("Request ID already has a result; choose a new ID")
temporary = root / "request.tmp"
temporary.write_text(json.dumps(request))
temporary.replace(root / "request.json")
deadline = time.monotonic() + min(55, max(1, args.timeout))
while time.monotonic() < deadline:
    picture = result.with_suffix(".png")
    if result.exists() and picture.exists() and picture.stat().st_size > 0:
        data = json.loads(result.read_text())
        if args.evidence:
            args.evidence.mkdir(parents=True, exist_ok=True)
            for path in (result, picture, root / f"{identifier}-accepted.json"):
                shutil.copy2(path, args.evidence / path.name)
        summary = {key: value for key, value in data.items() if key not in ("sections", "drawnSections")}
        summary["capturedDrawnSections"] = len(data.get("drawnSections", []))
        summary["picture"] = str(args.evidence / picture.name if args.evidence else picture)
        print(json.dumps(summary, indent=2))
        break
    time.sleep(0.25)
else:
    raise SystemExit(f"No complete capture for {identifier} within timeout; inspect client log before retrying")
