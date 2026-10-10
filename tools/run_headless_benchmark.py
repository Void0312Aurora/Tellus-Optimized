#!/usr/bin/env python3
"""Run Tellus' server-side chunk benchmark without starting a Minecraft client.

The script uses Fabric Loom's development server task. It copies a world template
into the module run directory, starts a fresh server process, waits for the server
ready marker, sends `/tellus benchmark ...`, and collects the JSON result written by
Tellus. The template is never modified.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Iterable


READY_MARKERS = ("Done (", "For help, type \"help\"")


def parse_args() -> argparse.Namespace:
    project_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world-template", type=Path, required=True,
                        help="Existing Tellus save used as the immutable template.")
    parser.add_argument("--chunk-x", type=int, required=True, help="Center chunk X coordinate.")
    parser.add_argument("--chunk-z", type=int, required=True, help="Center chunk Z coordinate.")
    parser.add_argument("--radius", type=int, default=0, choices=range(0, 33),
                        help="Square radius in chunks around the center (default: 0).")
    parser.add_argument("--workers", type=int, default=1,
                        help="tellus.chunkgen.threads.max value (default: 1).")
    parser.add_argument("--parallel", action="store_true",
                        help="Enable Tellus parallel solid-section filling.")
    parser.add_argument("--module", default="mc1201", help="Gradle module (default: mc1201).")
    parser.add_argument("--project-root", type=Path, default=project_root)
    parser.add_argument("--output-dir", type=Path,
                        help="Result directory (default: tools/benchmark-results).")
    parser.add_argument("--heap", default="8g", help="Both Xms and Xmx for the server JVM.")
    parser.add_argument("--timeout", type=float, default=900.0,
                        help="Maximum server runtime in seconds (default: 900).")
    parser.add_argument("--cache-template", type=Path,
                        help="Prewarmed Tellus cache directory copied before the run.")
    parser.add_argument("--reuse-cache", action="store_true",
                        help="Keep the existing run/tellus cache instead of clearing it.")
    parser.add_argument("--keep-world", action="store_true",
                        help="Keep the copied run/world after the benchmark.")
    parser.add_argument("--label", default=None,
                        help="Result label; defaults to serial or parallel-<workers>.")
    return parser.parse_args()


def copy_world_template(template: Path, target: Path) -> None:
    template = template.expanduser().resolve()
    if not template.is_dir():
        raise SystemExit(f"world template is not a directory: {template}")
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(template, target)
    # A save copied while Minecraft is running may contain a stale lock.
    lock = target / "session.lock"
    if lock.exists():
        lock.unlink()


def write_server_config(run_dir: Path) -> None:
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (run_dir / "server.properties").write_text(
        "\n".join(
            [
                "level-name=world",
                "online-mode=false",
                "enable-command-block=true",
                "spawn-protection=0",
                "enable-rcon=false",
                "view-distance=2",
                "simulation-distance=2",
                "max-tick-time=-1",
                "pause-when-empty-seconds=-1",
            ]
        )
        + "\n",
        encoding="utf-8",
    )


def git_revision(project_root: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=project_root, text=True
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def env_with_benchmark_properties(run_dir: Path, args: argparse.Namespace) -> dict[str, str]:
    env = os.environ.copy()
    properties = [
        "-Dtellus.benchmark.enabled=true",
        "-Dtellus.benchmark.exit=true",
        f"-Dtellus.chunkgen.parallelSolidSections={'true' if args.parallel else 'false'}",
        f"-Dtellus.chunkgen.threads.max={max(1, args.workers)}",
        f"-Dtellus.gameDir={run_dir.resolve()}",
        f"-Dtellus.configDir={(run_dir / 'config').resolve()}",
    ]
    existing = env.get("JAVA_TOOL_OPTIONS", "").strip()
    env["JAVA_TOOL_OPTIONS"] = " ".join(filter(None, [existing, *properties]))
    return env


def iter_output(process: subprocess.Popen[str], log_path: Path) -> Iterable[str]:
    with log_path.open("w", encoding="utf-8", errors="replace") as log:
        assert process.stdout is not None
        for line in process.stdout:
            log.write(line)
            log.flush()
            yield line


def latest_result(result_dir: Path, before: set[Path]) -> Path | None:
    candidates = [p for p in result_dir.glob("chunk-benchmark-*.json") if p not in before]
    return max(candidates, key=lambda p: p.stat().st_mtime_ns, default=None)


def main() -> int:
    args = parse_args()
    if args.workers < 1:
        raise SystemExit("--workers must be at least 1")
    project_root = args.project_root.expanduser().resolve()
    run_dir = (project_root / args.module / "run").resolve()
    output_dir = (args.output_dir or project_root / "tools" / "benchmark-results").expanduser().resolve()
    label = args.label or (f"parallel-{args.workers}" if args.parallel else "serial")
    log_path = output_dir / f"{label}.server.log"
    output_dir.mkdir(parents=True, exist_ok=True)

    run_dir.mkdir(parents=True, exist_ok=True)
    copy_world_template(args.world_template, run_dir / "world")
    if not args.reuse_cache:
        tellus_dir = run_dir / "tellus"
        if tellus_dir.exists():
            shutil.rmtree(tellus_dir)
        if args.cache_template is not None:
            cache_template = args.cache_template.expanduser().resolve()
            if not cache_template.is_dir():
                raise SystemExit(f"cache template is not a directory: {cache_template}")
            (tellus_dir / "cache").parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(cache_template, tellus_dir / "cache")
    write_server_config(run_dir)
    result_dir = run_dir / "tellus" / "benchmarks"
    before = set(result_dir.glob("chunk-benchmark-*.json")) if result_dir.exists() else set()

    command = [
        "./gradlew",
        f"-PtellusTargets={args.module}",
        f"-PtellusBenchmarkHeap={args.heap}",
        f":{args.module}:runServer",
        "--args=--nogui",
        "--no-daemon",
        "--max-workers=2",
    ]
    env = env_with_benchmark_properties(run_dir, args)
    print(f"[benchmark] starting {label}: {' '.join(command)}", flush=True)
    process = subprocess.Popen(
        command,
        cwd=project_root,
        env=env,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )

    started = time.monotonic()
    sent = False
    try:
        for line in iter_output(process, log_path):
            if not sent and any(marker in line for marker in READY_MARKERS):
                assert process.stdin is not None
                command_line = f"tellus benchmark {args.chunk_x} {args.chunk_z} {args.radius}\n"
                process.stdin.write(command_line)
                process.stdin.flush()
                sent = True
                print(f"[benchmark] sent: {command_line.strip()}", flush=True)
            if time.monotonic() - started > args.timeout:
                raise TimeoutError(f"benchmark exceeded {args.timeout:.1f}s")
    except (TimeoutError, BrokenPipeError) as error:
        print(f"[benchmark] {error}", file=sys.stderr, flush=True)
        if process.poll() is None and process.stdin is not None:
            try:
                process.stdin.write("stop\n")
                process.stdin.flush()
            except BrokenPipeError:
                pass
    finally:
        if process.poll() is None:
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=30)

    result_path = latest_result(result_dir, before)
    if result_path is None:
        print(f"[benchmark] no result JSON found; see {log_path}", file=sys.stderr)
        return 2 if process.returncode == 0 else process.returncode or 2

    result = json.loads(result_path.read_text(encoding="utf-8"))
    result.update(
        {
            "runnerLabel": label,
            "runnerRevision": git_revision(project_root),
            "runnerWorkers": args.workers,
            "runnerParallel": args.parallel,
            "runnerCacheMode": ("reuse" if args.reuse_cache else ("template" if args.cache_template else "cold")),
            "runnerLog": str(log_path),
            "runnerCommand": command,
        }
    )
    final_path = output_dir / f"{label}.json"
    final_path.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False), flush=True)
    print(f"[benchmark] result: {final_path}", flush=True)

    if not args.keep_world:
        world = run_dir / "world"
        if world.exists():
            shutil.rmtree(world)
    return 0 if result.get("error") is None and process.returncode == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
