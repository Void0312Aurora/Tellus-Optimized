#!/usr/bin/env python3
"""Generate real GIS terrain with travel settings, then reload its native saved LevelStem.

Reuses immutable-copy admission and the owned native launcher. Only the disposable
datapack is configured; Minecraft writes and reloads both the LevelStem and chunk.
This is not a visual seam, vegetation-placement, or natural-structure acceptance.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
import uuid

import run_native_persistence_roundtrip as native
from run_native_generation_parity import code_manifest
from run_native_sea_offset_restart import generator_record


TRAVEL = {"custom_trees": False, "huge_red_mushrooms": True, "climate_based_built_up_terrain": True}


def configure_disposable(world: Path, dimension: str, module: str) -> dict:
    namespace, name = dimension.split(":", 1)
    definitions = list(world.glob(f"datapacks/*/data/{namespace}/dimension/{name}.json"))
    if len(definitions) != 1:
        raise ValueError("exactly one copied Cube datapack definition required")
    definition = definitions[0].resolve(strict=True)
    if not definition.is_relative_to(world.resolve(strict=True)):
        raise ValueError("definition escaped disposable world")
    before = definition.read_bytes()
    document = json.loads(before)
    generator = document["generator"]
    if generator.get("type") != "tellus:cube_net" or set(generator["biome_source"]) != {"biome"}:
        raise ValueError("original fixed-biome Cube definition required")
    source = {"world_scale": generator["world_scale"], "apron_chunks": generator.get("experimental_apron_chunks", 5),
              "fallback": generator["biome_source"], "settings": dict(TRAVEL)}
    for key in ("excluded_radius_meters", "transition_width_meters"):
        if key in generator:
            source[key] = generator[key]
    generator["biome_source"] = source
    definition.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    # The two supported releases use different metadata, even though generator IDs agree.
    pack = definition.parents[3] / "pack.mcmeta"
    if not pack.is_file() or not pack.resolve().is_relative_to(world.resolve(strict=True)):
        raise ValueError("copied datapack metadata required")
    metadata = json.loads(pack.read_text(encoding="utf-8"))
    metadata["pack"]["pack_format"] = {"mc1201": 15, "mc1211": 48}[module]
    pack.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    return {"definition": str(definition), "beforeSHA256": __import__("hashlib").sha256(before).hexdigest(),
            "configuredSHA256": native.sha256(definition), "configuredGenerator": generator,
            "travelSettings": dict(TRAVEL), "authority": "copied-datapack-for-first-process-only"}


def main(argv=None):
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--target-x", type=int, required=True)
    parser.add_argument("--target-z", type=int, required=True)
    extra, remaining = parser.parse_known_args(argv)
    args = native.parse_args(remaining)
    if not args.execute or args.populate_lifecycle:
        raise ValueError("explicit execution without lifecycle mutation required")
    root = args.project_root.resolve(strict=True)
    world, cache = args.world_template.resolve(strict=True), args.cache_template.resolve(strict=True)
    run = args.run_dir.resolve()
    if run.exists():
        raise ValueError("fresh disposable run directory required")
    for source in (root, world, cache):
        native.assert_separate(run, source)
    before_world, before_cache, before_code = native.file_manifest(world), native.file_manifest(cache), code_manifest(root)
    report = {"status": "RUNNING", "module": args.module, "syntheticSampler": False,
              "scope": "real GIS generation and native travel-settings save/reload; not visual or structure acceptance",
              "target": [extra.target_x, extra.target_z], "phases": [], "sourceCodeSHA256": before_code}
    try:
        prepare = list(remaining)
        prepare.remove("--execute")
        prepare.append("--prepare-only")
        if native.main(prepare):
            raise ValueError("immutable source admission/copy failed")
        report["configuration"] = configure_disposable(run / "world", args.dimension, args.module)
        args.chunk_x, args.chunk_z = extra.target_x, extra.target_z
        args.require_native_runtime = True
        for phase in ("generate", "runtime_reload"):
            probe_id = str(uuid.uuid4())
            environment = os.environ.copy()
            for name in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
                if "tellus." in environment.get(name, ""):
                    raise ValueError("clean ambient Tellus JVM options required")
            options = ["tellus.persistence.probe=true", f"tellus.persistence.probe.id={probe_id}",
                       f"tellus.persistence.probeRoot={run / 'world'}", "tellus.persistence.probe.awaitDetails=true",
                       "tellus.sphere.syntheticSampler=false", f"tellus.gameDir={run}",
                       f"tellus.configDir={run / 'config'}", "tellus.chunkgen.threads.max=1",
                       f"tellus.persistence.probe.allowUpgrade={str(args.allow_template_upgrade).lower()}"]
            environment["JAVA_TOOL_OPTIONS"] = " ".join(filter(None, [environment.get("JAVA_TOOL_OPTIONS", ""),
                                                          *(f'"-D{option}"' for option in options)]))
            command = [str(root / ("gradlew.bat" if os.name == "nt" else "gradlew")),
                       f"-PtellusTargets={args.module}", f"-PtellusRunDir={run}", f"-PtellusBenchmarkHeap={args.heap}",
                       f":{args.module}:runServer", "--args=--nogui", "--offline", "--no-daemon",
                       "--no-parallel", "--max-workers=1", "--console=plain"]
            evidence = run / "evidence" / phase
            evidence.mkdir()
            result = native.run_phase(args, run, evidence, command, environment, phase, probe_id)
            saved = generator_record(run / "world", args.dimension)
            actual = saved.get("biome_source", {}).get("settings", {})
            if any(bool(actual.get(key)) != value for key, value in TRAVEL.items()) or any(key not in actual for key in TRAVEL):
                raise ValueError("native saved travel settings differ or disappeared")
            result.update({"savedGenerator": saved, "savedLevelSHA256": native.sha256(run / "world/level.dat"),
                           "command": command, "jvmProperties": options})
            report["phases"].append(result)
            native.write_json(run / "travel-smoke.json", report)
            if phase == "generate":
                definition = Path(report["configuration"]["definition"]).resolve(strict=True)
                retained = (run / "evidence/disabled-bootstrap-dimension.json").resolve()
                if not definition.is_relative_to((run / "world").resolve(strict=True)) or retained.exists():
                    raise ValueError("owned copied definition move target differs")
                if native.sha256(definition) != report["configuration"]["configuredSHA256"]:
                    raise ValueError("copied bootstrap definition changed unexpectedly")
                definition.rename(retained)
                report["datapackOverrideRemovedForIndependentRestart"] = True
        first, restarted = [item["native"] for item in report["phases"]]
        if first["binding"] != restarted["binding"] or report["phases"][0]["pid"] == report["phases"][1]["pid"]:
            raise ValueError("independent native process/profile binding differs")
        for original, reloaded in (("generatedBlockDigest", "blockDigest"), ("generatedBiomeDigest", "biomeDigest"),
                                   ("generatedNativeNBTDigest", "nativeNBTDigest")):
            if first[original] != restarted[reloaded]:
                raise ValueError(f"native generated/restarted payload differs: {original}")
        if report["phases"][0]["savedGenerator"] != report["phases"][1]["savedGenerator"]:
            raise ValueError("native settings/profile changed after stored-LevelStem restart")
        report["status"] = "PASS"
        return 0
    except BaseException as error:
        report["status"], report["error"] = "FAILED", f"{type(error).__name__}: {error}"
        raise
    finally:
        report["sourceWorldAndCacheUnchanged"] = native.file_manifest(world) == before_world and native.file_manifest(cache) == before_cache
        report["sourceCodeUnchangedDuringRun"] = code_manifest(root) == before_code
        if not report["sourceWorldAndCacheUnchanged"] or not report["sourceCodeUnchangedDuringRun"]:
            report["status"] = "FAILED"
        if run.is_dir():
            native.write_json(run / "travel-smoke.json", report)
        if not report["sourceWorldAndCacheUnchanged"] or not report["sourceCodeUnchangedDuringRun"]:
            raise ValueError("source world/cache/code changed during isolated travel smoke")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"cube travel smoke: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(2)
