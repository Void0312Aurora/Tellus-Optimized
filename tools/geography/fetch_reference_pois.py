"""Fetch the reference high-value-point snapshot used by the optional study."""
from __future__ import annotations

import argparse
import hashlib
import urllib.request
from pathlib import Path


UNESCO_EXPORT = (
    "https://data.unesco.org/api/explore/v2.1/catalog/datasets/whc001/"
    "exports/csv?lang=en&timezone=UTC"
)


def fetch(url: str, output: Path) -> str:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Tellus-RFC-geography-research/1.0",
            "Accept": "text/csv",
        },
    )
    with urllib.request.urlopen(request, timeout=90) as response:
        payload = response.read()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(payload)
    return hashlib.sha256(payload).hexdigest()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--unesco-output", type=Path, default=Path("build/geography/unesco-whc001.csv"))
    args = parser.parse_args()
    digest = fetch(UNESCO_EXPORT, args.unesco_output)
    print(f"Fetched UNESCO World Heritage CSV: {args.unesco_output}")
    print(f"sha256={digest}")
