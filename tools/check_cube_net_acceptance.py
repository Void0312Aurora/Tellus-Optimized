#!/usr/bin/env python3
"""Static acceptance checks for the Cube-Net seam path.

The script only proves that the version gates, payload receivers and transition
state machine hooks still exist in the checked-out sources. It does not prove
rendering, movement or multiplayer behaviour; those rows stay manual in
docs/validation/cube-net-acceptance-matrix-20261106/README.md.

Usage:
    python tools/check_cube_net_acceptance.py
    python tools/check_cube_net_acceptance.py --with-gradle
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Check:
    ident: str
    path: str
    pattern: str
    detail: str


CHECKS: tuple[Check, ...] = (
    Check(
        "A2",
        "mc1201/src/main/java/com/yucareux/tellus/compat/TellusRenderCompatibility.java",
        r'SUPPORTED_SODIUM_VERSION\s*=\s*"0\.5\.13\+mc1\.20\.1"',
        "Sodium staging is pinned to 0.5.13+mc1.20.1",
    ),
    Check(
        "A3",
        "src/main/java/com/yucareux/tellus/integration/distant_horizons/DistantHorizonsIntegration.java",
        r'tellus\.dhCubeNetSeamLod',
        "DH direct seam LOD is opt-in through a system property",
    ),
    Check(
        "A4",
        "mc1201/src/client/java/com/yucareux/tellus/client/CubeNetClientTransition.java",
        r"public static void prepare\(CubeNetTransitionPreparePayload",
        "client transition state machine keeps its PREPARE entry point",
    ),
    Check(
        "A5",
        "mc1201/src/client/java/com/yucareux/tellus/client/CubeNetClientTransition.java",
        r"private static void markReady\(",
        "client transition keeps the READY/commit handshake",
    ),
    Check(
        "A6",
        "mc1201/src/main/java/com/yucareux/tellus/Tellus.java",
        r"CubeNetTransitionReadyPayload\.TYPE",
        "server registers the READY receiver",
    ),
    Check(
        "A7",
        "mc1201/src/main/java/com/yucareux/tellus/Tellus.java",
        r"CubeNetTransitionChunksReceivedPayload\.TYPE",
        "server receives the chunk-arrival acknowledgement",
    ),
    Check(
        "A8",
        "mc1201/src/main/java/com/yucareux/tellus/Tellus.java",
        r"CubeNetTransitionMotionPayload\.TYPE",
        "server receives client motion during a transition",
    ),
    Check(
        "A9",
        "mc1201/src/main/java/com/yucareux/tellus/Tellus.java",
        r"CubeNetTransitionUnsupportedPayload\.TYPE",
        "server accepts the fail-closed report",
    ),
    Check(
        "A10",
        "mc1201/src/client/java/com/yucareux/tellus/TellusClient.java",
        r"CubeNetLayoutPayload\.TYPE",
        "client registers the layout payload",
    ),
    Check(
        "A11",
        "mc1201/src/client/java/com/yucareux/tellus/TellusClient.java",
        r"CubeNetBoundaryHintPayload\.TYPE",
        "client registers the boundary hint payload",
    ),
    Check(
        "A12",
        "mc1201/src/client/java/com/yucareux/tellus/TellusClient.java",
        r"Cube-net transitions will fail closed",
        "unsupported Sodium builds fail closed instead of half-working",
    ),
)


TEST_FILES = (
    "mc1201/src/test/java/com/yucareux/tellus/worldgen/CubeNetChunkGeneratorTest.java",
    "mc1201/src/test/java/com/yucareux/tellus/worldgen/CubeNetTransitionPayloadTest.java",
    "mc1201/src/test/java/com/yucareux/tellus/worldgen/CubeNetSeamApproachTest.java",
    "src/test/java/com/yucareux/tellus/worldgen/CubeNetBoundaryContractTest.java",
    "src/test/java/com/yucareux/tellus/worldgen/CubeNetCrossFaceConsistencyTest.java",
)


def evaluate(check: Check) -> tuple[bool, str]:
    target = ROOT / check.path
    if not target.is_file():
        return False, f"missing file {check.path}"
    text = target.read_text(encoding="utf-8", errors="replace")
    if re.search(check.pattern, text):
        return True, "ok"
    return False, "pattern not found"


def check_tests() -> tuple[bool, str]:
    missing = [path for path in TEST_FILES if not (ROOT / path).is_file()]
    if missing:
        return False, "missing " + ", ".join(missing)
    return True, f"{len(TEST_FILES)} contract test files present"


def run_gradle() -> tuple[bool, str]:
    command = [
        str(ROOT / "gradlew.bat"),
        ":mc1201:test",
        "--tests",
        "com.yucareux.tellus.worldgen.CubeNet*",
        "--offline",
        "--console=plain",
    ]
    completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, shell=False)
    tail = [line for line in completed.stdout.splitlines() if line.strip()][-3:]
    return completed.returncode == 0, " | ".join(tail) or "no output"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--with-gradle",
        action="store_true",
        help="also run :mc1201:test --tests com.yucareux.tellus.worldgen.CubeNet* --offline",
    )
    args = parser.parse_args()

    rows: list[tuple[str, str, bool, str]] = []
    for check in CHECKS:
        ok, detail = evaluate(check)
        rows.append((check.ident, check.detail, ok, detail))

    ok, detail = check_tests()
    rows.append(("A1", "Cube-Net contract test files exist", ok, detail))

    if args.with_gradle:
        ok, detail = run_gradle()
        rows.append(("G1", "Cube-Net contract tests pass", ok, detail))

    width = max(len(description) for _, description, _, _ in rows)
    failures = 0
    for ident, description, ok, detail in rows:
        status = "PASS" if ok else "FAIL"
        failures += 0 if ok else 1
        print(f"{ident:<3} {status:<5} {description:<{width}}  {detail}")

    print(f"\n{len(rows) - failures}/{len(rows)} static checks passed")
    print("Manual rows (rendering, movement, multiplayer) are listed in")
    print("docs/validation/cube-net-acceptance-matrix-20261106/README.md")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
