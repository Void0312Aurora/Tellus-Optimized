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
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Iterable


READY_MARKERS = ("Done (", "For help, type \"help\"")


def parse_args() -> argparse.Namespace:
    project_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world-template", type=Path, required=True,
                        help="Existing Tellus save used as the immutable template.")
    parser.add_argument("--chunk-x", type=int, help="Center chunk X coordinate (required for chunk benchmarks).")
    parser.add_argument("--chunk-z", type=int, help="Center chunk Z coordinate (required for chunk benchmarks).")
    parser.add_argument("--sphere-check-planet", help="Run the opt-in two-way spherical ServerPlayer routing check instead of a chunk benchmark.")
    parser.add_argument("--cube-net-check", action="store_true", help="Check all cube-net directed edges through the installed movement hook.")
    parser.add_argument("--radius", type=int, default=0, choices=range(0, 33),
                        help="Square radius in chunks around the center (default: 0).")
    parser.add_argument("--workers", type=int, default=1,
                        help="tellus.chunkgen.threads.max value (default: 1).")
    parser.add_argument("--parallel", action="store_true",
                        help="Enable Tellus parallel solid-section filling.")
    parser.add_argument("--parallel-water", action="store_true",
                        help="Enable the opt-in parallel water-region build path.")
    parser.add_argument("--concurrent-requests", action="store_true",
                        help="Submit all benchmark chunk requests before waiting for completion.")
    parser.add_argument("--area-prefetch", action="store_true",
                        help="Prefetch source data for the whole benchmark area before requesting chunks.")
    parser.add_argument("--state-hash", action="store_true",
                        help="Hash every generated block state after timing completes.")
    parser.add_argument("--chart-probe", action="store_true",
                        help="Record chart assignment and truncation-order metadata for benchmark chunks.")
    parser.add_argument("--jvm-property", action="append", default=[], metavar="KEY=VALUE",
                        help="Additional JVM system property; may be repeated.")
    parser.add_argument("--module", default="mc1201", help="Gradle module (default: mc1201).")
    parser.add_argument("--project-root", type=Path, default=project_root)
    parser.add_argument("--run-dir", type=Path, help="Isolated run directory (mc1201 only).")
    parser.add_argument("--dimension", default="minecraft:overworld", help="Dimension containing the benchmark generator.")
    parser.add_argument("--world-type", default="minecraft:normal", choices=("minecraft:normal", "minecraft:flat"),
                        help="Type used when the template has no level.dat.")
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


def write_server_config(run_dir: Path, world_type: str = "minecraft:normal") -> None:
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (run_dir / "server.properties").write_text(
        "\n".join(
            [
                "level-name=world",
                f"level-type={world_type}",
                *(["generator-settings=" + json.dumps({
                    "layers": [{"block": "minecraft:bedrock", "height": 1},
                               {"block": "minecraft:dirt", "height": 2},
                               {"block": "minecraft:grass_block", "height": 1}],
                    "biome": "minecraft:plains", "lakes": False, "features": False,
                    "structure_overrides": [],
                }, separators=(",", ":"))] if world_type == "minecraft:flat" else []),
                "online-mode=false",
                "server-ip=127.0.0.1",
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


def git_worktree_dirty(project_root: Path) -> bool | None:
    try:
        return bool(subprocess.check_output(
            ["git", "status", "--porcelain"], cwd=project_root, text=True
        ).strip())
    except (OSError, subprocess.CalledProcessError):
        return None


def env_with_benchmark_properties(run_dir: Path, args: argparse.Namespace) -> dict[str, str]:
    env = os.environ.copy()
    properties = [
        "-Dtellus.benchmark.enabled=true",
        "-Dtellus.benchmark.exit=true",
        f"-Dtellus.chunkgen.parallelSolidSections={'true' if args.parallel else 'false'}",
        f"-Dtellus.water.parallelRegionBuild={'true' if args.parallel_water else 'false'}",
        f"-Dtellus.benchmark.concurrentRequests={'true' if args.concurrent_requests else 'false'}",
        f"-Dtellus.benchmark.areaPrefetch={'true' if args.area_prefetch else 'false'}",
        f"-Dtellus.benchmark.stateHash={'true' if args.state_hash else 'false'}",
        f"-Dtellus.benchmark.chartProbe={'true' if args.chart_probe else 'false'}",
        f"-Dtellus.chunkgen.threads.max={max(1, args.workers)}",
        f"-Dtellus.gameDir={run_dir.resolve()}",
        f"-Dtellus.configDir={(run_dir / 'config').resolve()}",
    ]
    for property_value in args.jvm_property:
        if "=" not in property_value:
            raise SystemExit(f"--jvm-property requires KEY=VALUE: {property_value}")
        properties.append("-D" + property_value)
    existing = env.get("JAVA_TOOL_OPTIONS", "").strip()
    env["JAVA_TOOL_OPTIONS"] = " ".join(filter(None, [existing, *properties]))
    return env


def iter_output(process: subprocess.Popen[str], log_path: Path) -> Iterable[str]:
    # A quiet server must still reach the timeout check. Reading directly from
    # stdout would block the controller until another log line appeared.
    lines: queue.Queue[str | None] = queue.Queue()

    def read() -> None:
        try:
            with log_path.open("w", encoding="utf-8", errors="replace") as log:
                assert process.stdout is not None
                for line in process.stdout:
                    log.write(line)
                    log.flush()
                    lines.put(line)
        finally:
            lines.put(None)

    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    while True:
        try:
            line = lines.get(timeout=1.0)
        except queue.Empty:
            yield ""
            continue
        if line is None:
            return
        yield line


def latest_result(result_dir: Path, before: set[Path], pattern: str = "chunk-benchmark-*.json") -> Path | None:
    candidates = [p for p in result_dir.glob(pattern) if p not in before]
    return max(candidates, key=lambda p: p.stat().st_mtime_ns, default=None)


def main() -> int:
    args = parse_args()
    if args.workers < 1:
        raise SystemExit("--workers must be at least 1")
    if args.sphere_check_planet is not None and args.cube_net_check:
        raise SystemExit("choose only one runtime check")
    if args.sphere_check_planet is None and not args.cube_net_check and (args.chunk_x is None or args.chunk_z is None):
        raise SystemExit("--chunk-x and --chunk-z are required for chunk benchmarks")
    if args.sphere_check_planet is not None and not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", args.sphere_check_planet):
        raise SystemExit("--sphere-check-planet must be a namespace:path identifier")
    project_root = args.project_root.expanduser().resolve()
    run_dir = (args.run_dir or project_root / args.module / "run").expanduser().resolve()
    if args.run_dir is not None and args.module != "mc1201":
        raise SystemExit("--run-dir is supported by mc1201 only")
    if not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", args.dimension):
        raise SystemExit("--dimension must be a namespace:path identifier")
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
    write_server_config(run_dir, args.world_type)
    result_dir = run_dir / "tellus" / "benchmarks"
    pattern = ("cube-net-runtime-check-*.json" if args.cube_net_check else
               "sphere-runtime-check-*.json" if args.sphere_check_planet else "chunk-benchmark-*.json")
    before = set(result_dir.glob(pattern)) if result_dir.exists() else set()

    gradle_launcher = "gradlew.bat" if os.name == "nt" else "./gradlew"
    command = [
        gradle_launcher,
        f"-PtellusTargets={args.module}",
        f"-PtellusBenchmarkHeap={args.heap}",
        f":{args.module}:runServer",
        "--args=--nogui",
        "--no-daemon",
        "--max-workers=2",
    ]
    if args.run_dir is not None:
        command.insert(1, f"-PtellusRunDir={run_dir}")
    env = env_with_benchmark_properties(run_dir, args)
    start_revision = git_revision(project_root)
    start_dirty = git_worktree_dirty(project_root)
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
                command_line = ("tellus cube_net verify\n" if args.cube_net_check else
                                f"tellus sphere verify {args.sphere_check_planet}\n" if args.sphere_check_planet
                                else f"execute in {args.dimension} run tellus benchmark {args.chunk_x} {args.chunk_z} {args.radius}\n")
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

    result_path = latest_result(result_dir, before, pattern)
    if result_path is None:
        print(f"[benchmark] no result JSON found; see {log_path}", file=sys.stderr)
        return 2 if process.returncode == 0 else process.returncode or 2

    result = json.loads(result_path.read_text(encoding="utf-8"))
    result.update(
        {
            "runnerLabel": label,
            "runnerDimension": args.dimension,
            "runnerSphereCheckPlanet": args.sphere_check_planet,
            "runnerCubeNetCheck": args.cube_net_check,
            "runnerRunDir": str(run_dir),
            "runnerRevision": start_revision,
            "runnerWorktreeDirtyAtStart": start_dirty,
            "runnerWorktreeDirtyAtEnd": git_worktree_dirty(project_root),
            "runnerWorkers": args.workers,
            "runnerParallel": args.parallel,
            "runnerParallelWater": args.parallel_water,
            "runnerConcurrentRequests": args.concurrent_requests,
            "runnerAreaPrefetch": args.area_prefetch,
            "runnerStateHash": args.state_hash,
            "runnerChartProbe": args.chart_probe,
            "runnerJvmProperties": args.jvm_property,
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
    return 0 if result.get("error") is None and result.get("passed", True) and process.returncode == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
