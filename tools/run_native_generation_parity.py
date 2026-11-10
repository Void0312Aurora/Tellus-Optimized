#!/usr/bin/env python3
"""Generate the same absent chunk through old/new real native servers and compare full payloads.

Requires two explicit source checkouts with the observation-only `generate` probe.
The source world/cache are read-only; each lane gets an independent fresh copy.
Uses the existing bounded process owner and report admission, never region writes.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import sys
import uuid
import run_native_persistence_roundtrip as native


def code_manifest(root: Path) -> dict:
    return {str(path.relative_to(root)).replace('\\', '/'): native.sha256(path)
            for base in ('src', 'shared', 'mc1201/src', 'mc1211/src')
            for path in sorted((root / base).rglob('*.java')) if path.is_file()}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument('--baseline-root', type=Path, required=True)
    parser.add_argument('--baseline-ref', required=True)
    parser.add_argument('--target-x', type=int, required=True)
    parser.add_argument('--target-z', type=int, required=True)
    extra, remaining = parser.parse_known_args(argv)
    args = native.parse_args(remaining)
    if not args.execute or args.populate_lifecycle:
        raise ValueError('generation A/B requires --execute without lifecycle mutation')
    baseline = extra.baseline_root.resolve(strict=True)
    candidate = args.project_root.resolve(strict=True)
    pair = args.run_dir.resolve()
    if pair.exists():
        raise ValueError('fresh pair directory required; no reset or deletion')
    world = args.world_template.resolve(strict=True)
    cache = args.cache_template.resolve(strict=True)
    for source in (baseline, candidate, world, cache):
        native.assert_separate(pair, source)
    native.assert_separate(baseline, candidate)
    # Existing anchor proves admitted historical world/native version provenance.
    native.inspect_world(world, args.dimension, args.chunk_x, args.chunk_z)
    for port in (args.port, args.port + 1):
        if not 1024 <= port <= 65535:
            raise ValueError('port outside admitted range')
        native.port_free(port)
    original_world, original_cache = native.file_manifest(world), native.file_manifest(cache)
    code = {label: code_manifest(root) for label, root in [('baseline', baseline), ('candidate', candidate)]}
    pair.mkdir(parents=True, exist_ok=False)
    report = {'status': 'RUNNING', 'module': args.module, 'baselineRef': extra.baseline_ref,
              'world': str(world), 'cache': str(cache), 'sourceOrigin': args.source_origin,
              'target': [extra.target_x, extra.target_z], 'syntheticSampler': False,
              'code': code, 'lanes': [], 'scope': 'new native FULL generation and save, not a world transaction/restart'}
    native.write_json(pair / 'source-world-manifest.json', original_world)
    native.write_json(pair / 'source-cache-manifest.json', original_cache)
    probe_id = str(uuid.UUID(args.probe_id)) if args.probe_id else str(uuid.uuid4())
    try:
        for ordinal, (label, project) in enumerate([('baseline', baseline), ('candidate', candidate)]):
            run = pair / label
            prepare = list(remaining)
            prepare.remove('--execute')
            prepare += ['--prepare-only', '--project-root', str(project), '--run-dir', str(run), '--probe-id', probe_id]
            if native.main(prepare) != 0:
                raise ValueError('copied world/cache preparation failed')
            lane_args = native.parse_args(prepare)
            lane_args.project_root = project
            lane_args.chunk_x, lane_args.chunk_z = extra.target_x, extra.target_z
            lane_args.port = args.port + ordinal
            properties = run / 'server.properties'
            properties.write_text(properties.read_text(encoding='utf-8').replace(
                f'server-port={args.port}', f'server-port={lane_args.port}'), encoding='utf-8')
            env = os.environ.copy()
            for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
                if 'tellus.' in env.get(name, ''):
                    raise ValueError('ambient Tellus JVM options require a clean environment')
            options = ['tellus.persistence.probe=true', f'tellus.persistence.probe.id={probe_id}',
                       f'tellus.persistence.probeRoot={run / "world"}', 'tellus.sphere.syntheticSampler=false',
                       f'tellus.gameDir={run}', f'tellus.configDir={run / "config"}', 'tellus.chunkgen.threads.max=1']
            env['JAVA_TOOL_OPTIONS'] = ' '.join(filter(None, [env.get('JAVA_TOOL_OPTIONS', ''),
                *(f'"-D{option}"' for option in options)]))
            command = [str(project / ('gradlew.bat' if os.name == 'nt' else 'gradlew')),
                       f'-PtellusTargets={args.module}', f'-PtellusRunDir={run}', f'-PtellusBenchmarkHeap={args.heap}',
                       f':{args.module}:runServer', '--args=--nogui', '--offline', '--no-daemon',
                       '--no-parallel', '--max-workers=1', '--console=plain']
            phase = native.run_phase(lane_args, run, run / 'evidence', command, env, 'generate', probe_id)
            report['lanes'].append({'label': label, 'project': str(project), 'command': command,
                                    'jvmProperties': options, **phase})
            native.write_json(pair / 'generation-parity.json', report)
        old, new = [lane['native'] for lane in report['lanes']]
        fields = ('generatedBlockDigest', 'generatedBiomeDigest', 'generatedNativeNBTDigest', 'generatedLifecycleCounts',
                  'generatedNativeStatus', 'generatedDataVersion')
        report['differences'] = {field: {'baseline': old.get(field), 'candidate': new.get(field)}
                                 for field in fields if old.get(field) != new.get(field)}
        if report['differences']:
            raise ValueError('new native FULL generation parity differs; retain raw SNBT and logs')
        report['status'] = 'PASS'
        return 0
    except BaseException as error:
        report['status'] = 'FAILED'
        report['error'] = f'{type(error).__name__}: {error}'
        raise
    finally:
        intact = native.file_manifest(world) == original_world and native.file_manifest(cache) == original_cache
        source_intact = code['baseline'] == code_manifest(baseline) and code['candidate'] == code_manifest(candidate)
        report['sourceWorldAndCacheUnchanged'] = intact
        report['sourceCodeUnchangedDuringRun'] = source_intact
        if not intact or not source_intact:
            report['status'] = 'FAILED'
        native.write_json(pair / 'generation-parity.json', report)
        if not intact or not source_intact:
            raise ValueError('source world/cache/code changed during native A/B run')


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f'native generation parity: {type(error).__name__}: {error}', file=sys.stderr)
        raise SystemExit(2)
