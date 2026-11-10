#!/usr/bin/env python3
"""Inspect/copy a real Tellus save, then capture -> STOP -> restart -> verify.

No build or server starts without --execute. The immutable source world/cache are
fingerprinted before copying and after execution. Targets must be fresh; nothing
is deleted. This is not a synthetic sampler or a replacement Minecraft serializer.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
import uuid
import zlib

VERSIONS = {"mc1201": ("1.20.1", 3465), "mc1211": ("1.21.1", 3955)}
DIMENSION = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+\Z")
MAX_NBT = 64 * 1024 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


class NBTReader:
    """Bounded read-only NBT admission; publication stays in Minecraft native APIs."""
    def __init__(self, raw: bytes):
        if len(raw) > MAX_NBT:
            raise ValueError("NBT exceeds read-only admission budget")
        self.raw, self.offset = memoryview(raw), 0

    def take(self, size: int) -> memoryview:
        if size < 0 or self.offset + size > len(self.raw):
            raise ValueError("truncated NBT")
        result = self.raw[self.offset:self.offset + size]
        self.offset += size
        return result

    def number(self, code: str):
        return struct.unpack(">" + code, self.take(struct.calcsize(">" + code)))[0]

    def string(self) -> str:
        # Minecraft uses modified UTF-8. All admission keys/registry identifiers
        # are ASCII; replacement only affects unrelated human display strings.
        return bytes(self.take(self.number("H"))).decode("utf-8", errors="replace")

    def value(self, tag: int, depth: int = 0):
        if depth > 512:
            raise ValueError("NBT depth budget")
        if tag in (1, 2, 3, 4, 5, 6):
            return self.number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[tag])
        if tag == 8:
            return self.string()
        if tag == 10:
            result = {}
            while (kind := self.number("B")) != 0:
                name = self.string()
                if name in result:
                    raise ValueError("duplicate NBT key")
                result[name] = self.value(kind, depth + 1)
            return result
        if tag == 9:
            kind, count = self.number("B"), self.number("i")
            if not 0 <= count <= 1_048_576:
                raise ValueError("NBT list budget")
            return [self.value(kind, depth + 1) for _ in range(count)]
        if tag in (7, 11, 12):
            count = self.number("i")
            if count < 0 or count > MAX_NBT // {7: 1, 11: 4, 12: 8}[tag]:
                raise ValueError("NBT array budget")
            return bytes(self.take(count * {7: 1, 11: 4, 12: 8}[tag]))
        raise ValueError(f"unsupported NBT tag {tag}")

    def root(self) -> dict:
        if self.number("B") != 10:
            raise ValueError("NBT root is not a compound")
        self.string()
        root = self.value(10)
        if self.offset != len(self.raw):
            raise ValueError("trailing NBT bytes")
        return root


def decompress(raw: bytes, compression: int) -> bytes:
    if compression == 3:
        if len(raw) > MAX_NBT:
            raise ValueError("raw NBT budget")
        return raw
    if compression not in (1, 2):
        raise ValueError(f"unsupported Anvil compression {compression}; no conversion performed")
    decoder = zlib.decompressobj(31 if compression == 1 else 15)
    result = decoder.decompress(raw, MAX_NBT + 1)
    if len(result) > MAX_NBT or not decoder.eof or decoder.unused_data:
        raise ValueError("compressed NBT budget/truncation/trailing bytes")
    return result


def dimension_root(world: Path, dimension: str) -> Path:
    if dimension == "minecraft:overworld":
        return world
    if dimension == "minecraft:the_nether":
        return world / "DIM-1"
    if dimension == "minecraft:the_end":
        return world / "DIM1"
    namespace, name = dimension.split(":", 1)
    return world / "dimensions" / namespace / name


def inspect_world(world: Path, dimension: str, x: int, z: int) -> dict:
    level_file = world / "level.dat"
    if not level_file.is_file():
        raise ValueError("existing native level.dat required")
    data = NBTReader(decompress(level_file.read_bytes(), 1)).root()["Data"]
    settings = data.get("WorldGenSettings", {})
    generator = settings.get("dimensions", {}).get(dimension, {}).get("generator", {})
    if not str(generator.get("type", "")).startswith("tellus:"):
        raise ValueError("target dimension is not an existing Tellus generator")
    region = dimension_root(world, dimension) / "region" / f"r.{x // 32}.{z // 32}.mca"
    index = (x % 32) + (z % 32) * 32
    with region.open("rb") as stream:
        stream.seek(4 * index)
        entry = int.from_bytes(stream.read(4), "big")
        sector, sectors = entry >> 8, entry & 255
        if sector < 2 or sectors == 0:
            raise ValueError("target chunk has no existing Anvil record")
        stream.seek(sector * 4096)
        length = int.from_bytes(stream.read(4), "big")
        if not 1 < length <= sectors * 4096 - 4:
            raise ValueError("invalid Anvil record bounds")
        compression = stream.read(1)[0]
        if compression & 128:
            raise ValueError("external Anvil chunk requires an explicit extended reader")
        compressed = stream.read(length - 1)
        if len(compressed) != length - 1:
            raise ValueError("truncated Anvil record")
    raw = decompress(compressed, compression)
    nbt = NBTReader(raw).root()
    chunk = nbt.get("Level", nbt)
    if chunk.get("Status") not in ("minecraft:full", "full"):
        raise ValueError("pre-existing disk FULL required; regeneration is excluded")
    if (chunk.get("xPos"), chunk.get("zPos")) != (x, z):
        raise ValueError("stored chunk coordinates differ")
    palettes = sorted({p["Name"] for section in chunk.get("sections", [])
                       for p in section.get("block_states", {}).get("palette", []) if "Name" in p})
    if "minecraft:stone" not in palettes:
        raise ValueError("STONE sentinel source required")
    return {"world": str(world), "levelSha256": sha256(level_file),
            "dataVersion": data.get("DataVersion"), "version": data.get("Version"),
            "spawn": [data.get("SpawnX"), data.get("SpawnY"), data.get("SpawnZ")],
            "seed": settings.get("seed"), "dimension": dimension, "generator": generator,
            "region": str(region), "regionSha256": sha256(region),
            "chunkX": x, "chunkZ": z, "chunkDataVersion": nbt.get("DataVersion"),
            "status": chunk.get("Status"), "sectionCount": len(chunk.get("sections", [])),
            "nativeChunkSha256": hashlib.sha256(raw).hexdigest(), "palette": palettes}


def file_manifest(root: Path) -> dict:
    result = {}
    for directory, folders, files in os.walk(root, followlinks=False):
        for name in folders + files:
            path = Path(directory) / name
            if path.is_symlink() or bool(getattr(path.lstat(), "st_file_attributes", 0) & 0x400):
                raise ValueError(f"source links are excluded from immutable copy: {path}")
        for name in files:
            path = Path(directory) / name
            result[path.relative_to(root).as_posix()] = {"bytes": path.stat().st_size, "sha256": sha256(path)}
    return result


def assert_separate(run: Path, source: Path):
    if run == source or run in source.parents or source in run.parents:
        raise ValueError(f"disposable run/source trees overlap: {run} / {source}")


def port_free(port: int):
    with socket.socket() as check:
        check.bind(("127.0.0.1", port))


def write_json(path: Path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


class OwnedWindowsJob:
    """Kill-on-close job contains this launch tree, including orphaned children."""
    def __init__(self):
        self.handle = None
        if os.name != "nt":
            return
        import ctypes
        from ctypes import wintypes
        class Basic(ctypes.Structure):
            _fields_ = [("user", ctypes.c_int64), ("job", ctypes.c_int64), ("flags", wintypes.DWORD),
                        ("min_ws", ctypes.c_size_t), ("max_ws", ctypes.c_size_t),
                        ("active", wintypes.DWORD), ("affinity", ctypes.c_size_t),
                        ("priority", wintypes.DWORD), ("scheduling", wintypes.DWORD)]
        class IO(ctypes.Structure):
            _fields_ = [(name, ctypes.c_uint64) for name in ("read_ops", "write_ops", "other_ops", "read_bytes", "write_bytes", "other_bytes")]
        class Extended(ctypes.Structure):
            _fields_ = [("basic", Basic), ("io", IO), ("process_memory", ctypes.c_size_t),
                        ("job_memory", ctypes.c_size_t), ("peak_process", ctypes.c_size_t), ("peak_job", ctypes.c_size_t)]
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.kernel.CreateJobObjectW.argtypes = (ctypes.c_void_p, wintypes.LPCWSTR)
        self.kernel.CreateJobObjectW.restype = wintypes.HANDLE
        self.kernel.SetInformationJobObject.argtypes = (wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD)
        self.kernel.AssignProcessToJobObject.argtypes = (wintypes.HANDLE, wintypes.HANDLE)
        self.kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
        handle = self.kernel.CreateJobObjectW(None, None)
        if not handle:
            raise ctypes.WinError(ctypes.get_last_error())
        limits = Extended(); limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not self.kernel.SetInformationJobObject(handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            self.kernel.CloseHandle(handle)
            raise ctypes.WinError(ctypes.get_last_error())
        self.handle = handle

    def assign(self, process):
        if self.handle and not self.kernel.AssignProcessToJobObject(self.handle, int(process._handle)):
            import ctypes
            raise ctypes.WinError(ctypes.get_last_error())

    def close(self):
        if self.handle:
            self.kernel.CloseHandle(self.handle)
            self.handle = None


def stop_owned(process: subprocess.Popen, grace: float):
    if process.poll() is None:
        try:
            process.stdin.write("stop\n")
            process.stdin.flush()
        except (OSError, AttributeError):
            pass
        try:
            process.wait(timeout=grace)
        except subprocess.TimeoutExpired:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, check=False, timeout=30)
            else:
                os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=30)


def check_report(path: Path, phase: str, args, probe_id: str) -> dict:
    if not path.is_file() or path.stat().st_size > 4 * 1024 * 1024:
        raise ValueError(f"bounded {phase} report required: {path}")
    report = json.loads(path.read_text(encoding="utf-8"))
    expected = {"status": "PASS", "phase": phase, "probeId": probe_id,
                "syntheticSampler": False, "chunkX": args.chunk_x, "chunkZ": args.chunk_z,
                "diskReadBeforeDemandLoad": True}
    for key, value in expected.items():
        if report.get(key) != value:
            raise ValueError(f"native report {key} differs: {report.get(key)!r} != {value!r}")
    if phase == "generate":
        if not (report.get("targetAbsentBeforeGeneration") is True and report.get("generatedDiskMatchesMaterialized") is True):
            raise ValueError("native new-generation/save evidence missing")
        if not all(re.fullmatch(r"[0-9a-f]{64}", str(report.get(field, "")))
                   for field in ("generatedBlockDigest", "generatedBiomeDigest", "generatedNativeNBTDigest")):
            raise ValueError("native generated full block/biome/NBT digests missing")
        if report.get("generatedNativeStatus") not in ("full", "minecraft:full"):
            raise ValueError("native generated status is not FULL")
    if getattr(args, "populate_lifecycle", False):
        counts_key = "expectedLifecycleCounts" if phase == "capture" else "restartedDiskLifecycleCounts"
        counts = report.get(counts_key, {})
        if any(counts.get(field, 0) < 1 for field in ("block_entities", "block_ticks", "fluid_ticks")) or counts.get("postProcessingOffsets", 0) < 2:
            raise ValueError("live nonempty entity/tick/marker evidence missing")
        if phase == "verify" and not (report.get("materializedNativeLifecycleEqual") and report.get("liveChestInventoryAndWaterVerified")):
            raise ValueError("live materialization lifecycle verification missing")
    return report


def run_phase(args, run: Path, evidence: Path, command: list[str], env: dict, phase: str, probe_id: str) -> dict:
    marker = f"TELLUS_PERSISTENCE_{phase.upper()}_PASS"
    report_path = run / "tellus" / "persistence" / f"{probe_id}-{phase}.json"
    if report_path.exists():
        raise ValueError("phase report preexists; stale evidence is excluded")
    port_free(args.port)
    creation = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
    owned_job = OwnedWindowsJob()
    try:
        process = subprocess.Popen(command, cwd=args.project_root, env=env, stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                   encoding="utf-8", errors="replace", bufsize=1, **creation)
    except BaseException:
        owned_job.close()
        raise
    try:
        owned_job.assign(process)
    except BaseException:
        try:
            stop_owned(process, 5)
        finally:
            owned_job.close()
        raise
    messages = queue.Queue()
    log_path = evidence / f"{phase}.server.log"
    def read_output():
        try:
            with log_path.open("w", encoding="utf-8") as log:
                for line in process.stdout:
                    log.write(line); log.flush(); messages.put(line)
        finally:
            messages.put(None)
    reader = threading.Thread(target=read_output, daemon=True)
    reader.start()
    deadline = time.monotonic() + args.timeout
    sent = passed = False
    native = None
    try:
        while True:
            if time.monotonic() > deadline:
                raise TimeoutError(f"{phase} exceeded timeout; see {log_path}")
            try:
                line = messages.get(timeout=0.25)
            except queue.Empty:
                if process.poll() is not None:
                    break
                continue
            if line is None:
                break
            print(line, end="", flush=True)
            if not sent and 'Done (' in line and 'For help, type' in line:
                console = f"execute in {args.dimension} run tellus persistence {phase} {args.chunk_x} {args.chunk_z}"
                process.stdin.write(console + "\n"); process.stdin.flush()
                sent = True
            if f"TELLUS_PERSISTENCE_{phase.upper()}_FAIL" in line:
                raise ValueError(f"native {phase} failed; see report/log")
            if marker in line and not passed:
                if not sent:
                    raise ValueError("pass marker preceded command submission")
                native = check_report(report_path, phase, args, probe_id)
                passed = True
                process.stdin.write("stop\n"); process.stdin.flush()
                deadline = time.monotonic() + args.stop_timeout
        exit_code = process.wait(timeout=args.stop_timeout)
        reader.join(timeout=5)
        if not passed or exit_code != 0:
            raise ValueError(f"{phase} missing PASS or nonzero owned-process exit {exit_code}")
        return {"phase": phase, "pid": process.pid, "exitCode": exit_code, "passMarker": marker,
                "report": str(report_path), "reportSha256": sha256(report_path),
                "log": str(log_path), "logSha256": sha256(log_path), "native": native}
    finally:
        try:
            stop_owned(process, args.stop_timeout)
        finally:
            owned_job.close()
            if os.name != "nt":
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
        reader.join(timeout=5)
        if process.stdin:
            process.stdin.close()
        if process.stdout:
            process.stdout.close()


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world-template", type=Path, required=True)
    parser.add_argument("--cache-template", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True, help="Fresh disposable root; never reused or deleted")
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--module", choices=tuple(VERSIONS), required=True)
    parser.add_argument("--dimension", default="tellus:earth_cube_net")
    parser.add_argument("--chunk-x", type=int, required=True)
    parser.add_argument("--chunk-z", type=int, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--source-origin", required=True, help="Exact real GIS dataset/test-save provenance, not a synthetic fixture")
    parser.add_argument("--provenance-file", type=Path, action="append", required=True)
    parser.add_argument("--allow-template-upgrade", action="store_true", help="Allow native DataFixer upgrade on the disposable copy only")
    parser.add_argument("--populate-lifecycle", action="store_true", help="Populate live block-entity and tick payloads in the isolated copy")
    parser.add_argument("--heap", default="6g")
    parser.add_argument("--timeout", type=float, default=900)
    parser.add_argument("--stop-timeout", type=float, default=60)
    parser.add_argument("--probe-id", default=None)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--inspect-only", action="store_true", help="Read-only environment admission; no copy/build/server")
    mode.add_argument("--prepare-only", action="store_true", help="Copy only; no build/server")
    mode.add_argument("--execute", action="store_true", help="Explicitly start serial Loom capture/restart/verify processes")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    if not DIMENSION.fullmatch(args.dimension) or any(p in (".", "..", "") for p in args.dimension.split(":", 1)[1].split("/")):
        raise ValueError("dimension identifier/path is invalid")
    if not 1024 <= args.port <= 65535 or args.timeout <= 0 or args.stop_timeout <= 0:
        raise ValueError("port/timeouts outside admitted range")
    if not re.fullmatch(r"[1-9][0-9]*[mMgG]", args.heap):
        raise ValueError("heap must be positive Minecraft JVM memory size")
    args.project_root = args.project_root.expanduser().resolve(strict=True)
    world = args.world_template.expanduser().resolve(strict=True)
    cache = args.cache_template.expanduser().resolve(strict=True)
    run = args.run_dir.expanduser().resolve()
    if os.name == "nt" and run.drive.lower() != args.project_root.drive.lower():
        raise ValueError("Loom run directory must share the checkout drive; use a fresh same-drive temporary root")
    for source in (world, cache, args.project_root):
        assert_separate(run, source)
    if run.exists():
        raise ValueError("run directory already exists; choose a fresh disposable path (no deletion/reuse)")
    if not world.is_dir() or not cache.is_dir():
        raise ValueError("source world/cache directories required")
    inspection = inspect_world(world, args.dimension, args.chunk_x, args.chunk_z)
    version, target_data_version = VERSIONS[args.module]
    source_version = inspection["dataVersion"]
    if not isinstance(source_version, int) or source_version > target_data_version:
        raise ValueError("template newer than target, or unknown native DataVersion")
    upgrade = source_version != target_data_version
    if upgrade and not args.allow_template_upgrade:
        raise ValueError("native template version differs; explicit disposable-copy upgrade admission required")
    provenance = []
    for item in args.provenance_file:
        path = item.expanduser().resolve(strict=True)
        if not path.is_file():
            raise ValueError("provenance must be an existing file")
        provenance.append({"path": str(path), "sha256": sha256(path), "bytes": path.stat().st_size})
    probe_id = str(uuid.UUID(args.probe_id)) if args.probe_id else str(uuid.uuid4())
    summary = {"schemaVersion": 1, "status": "INSPECTED", "module": args.module, "targetVersion": version,
               "probeId": probe_id, "sourceOrigin": args.source_origin, "sourceMode": "existing-real-gis-world-and-copied-cache",
               "syntheticSampler": False, "templateUpgradeOnCopy": upgrade, "provenance": provenance,
               "runDir": str(run), "template": inspection, "phases": []}
    if args.inspect_only:
        print(json.dumps(summary, indent=2, ensure_ascii=False)); return 0
    world_manifest, cache_manifest = file_manifest(world), file_manifest(cache)
    run.mkdir(parents=True, exist_ok=False)
    evidence = run / "evidence"; evidence.mkdir()
    summary_path = evidence / "roundtrip.json"
    write_json(evidence / "source-world-manifest.json", world_manifest)
    write_json(evidence / "source-cache-manifest.json", cache_manifest)
    try:
        shutil.copytree(world, run / "world", symlinks=False)
        shutil.copytree(cache, run / "tellus" / "cache", symlinks=False)
        if file_manifest(run / "world") != world_manifest or file_manifest(run / "tellus" / "cache") != cache_manifest:
            raise ValueError("isolated copy is not byte-identical to source manifests")
        (run / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        (run / "server.properties").write_text("\n".join(("level-name=world", "online-mode=false", "server-ip=127.0.0.1",
            f"server-port={args.port}", "enable-rcon=false", "enable-query=false", "spawn-protection=0",
            "view-distance=2", "simulation-distance=2", "max-tick-time=-1", "pause-when-empty-seconds=-1",
            "sync-chunk-writes=true", "enable-command-block=false")) + "\n", encoding="utf-8")
        summary["sourceWorldFiles"] = len(world_manifest); summary["sourceCacheFiles"] = len(cache_manifest)
        if args.prepare_only:
            summary["status"] = "PREPARED_NOT_EXECUTED"; return 0
        env = os.environ.copy()
        for name in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
            if "tellus." in env.get(name, ""):
                raise ValueError(f"ambient Tellus JVM options require explicit clean environment: {name}")
        properties = ["tellus.persistence.probe=true", f"tellus.persistence.probe.id={probe_id}",
                      f"tellus.persistence.probe.allowUpgrade={'true' if args.allow_template_upgrade else 'false'}",
                       f"tellus.persistence.probeRoot={run / 'world'}", "tellus.sphere.syntheticSampler=false",
                       f"tellus.persistence.probe.populateLifecycle={'true' if args.populate_lifecycle else 'false'}",
                      f"tellus.gameDir={run}", f"tellus.configDir={run / 'config'}", "tellus.chunkgen.threads.max=1"]
        # Quote each -D argument for JVM paths containing spaces.
        env["JAVA_TOOL_OPTIONS"] = " ".join(filter(None, [env.get("JAVA_TOOL_OPTIONS", ""),
                                                   *(f'"-D{p}"' for p in properties)]))
        gradle = str(args.project_root / ("gradlew.bat" if os.name == "nt" else "gradlew"))
        command = [gradle, f"-PtellusTargets={args.module}", f"-PtellusRunDir={run}", f"-PtellusBenchmarkHeap={args.heap}",
                   f":{args.module}:runServer", "--args=--nogui", "--offline", "--no-daemon", "--no-parallel", "--max-workers=1", "--console=plain"]
        summary["command"] = command; summary["jvmProperties"] = properties
        for phase in ("capture", "verify"):
            # No copied-state reset or evidence reuse occurs between phases.
            summary["phases"].append(run_phase(args, run, evidence, command, env, phase, probe_id))
            write_json(summary_path, summary)
        capture, verify = [p["native"] for p in summary["phases"]]
        if capture.get("binding") != verify.get("binding"):
            raise ValueError("capture/restart binding differs")
        materialized_ok = verify.get("materializedNativeLifecycleEqual") if args.populate_lifecycle else verify.get("materializedFullNBTEqual")
        if not verify.get("restartedDiskMatchesExpected") or not materialized_ok:
            raise ValueError("native restart/full materialization acceptance missing")
        summary["status"] = "PASS"
        return 0
    except BaseException as error:
        summary["status"] = "FAILED"; summary["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        unchanged = file_manifest(world) == world_manifest and file_manifest(cache) == cache_manifest
        summary["sourceWorldAndCacheUnchanged"] = unchanged
        if not unchanged:
            summary["status"] = "FAILED"; summary["sourceIntegrityError"] = "source world/cache changed"
        write_json(summary_path, summary)
        if not unchanged:
            raise ValueError("immutable source world/cache changed during run")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"persistence roundtrip: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(2)
