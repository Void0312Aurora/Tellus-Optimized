#!/usr/bin/env python3
"""Exercise monument mixins in a real isolated server; explicitly not natural-start acceptance.

Uses the existing real-world/cache copy admission and owned native launcher. The
controlled monument objects never write world blocks. The surrounding existing
generate probe does generate/save one absent FULL chunk in the disposable copy.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys
import uuid
import run_native_persistence_roundtrip as native
from run_native_generation_parity import code_manifest


def main(argv=None):
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument('--target-x', type=int, required=True)
    parser.add_argument('--target-z', type=int, required=True)
    extra, remaining = parser.parse_known_args(argv)
    args = native.parse_args(remaining)
    if not args.execute or args.populate_lifecycle:
        raise ValueError('explicit --execute without lifecycle mutation required')
    world, cache = args.world_template.resolve(strict=True), args.cache_template.resolve(strict=True)
    root, run = args.project_root.resolve(strict=True), args.run_dir.resolve()
    if run.exists():
        raise ValueError('fresh disposable run directory required')
    for source in (root, world, cache):
        native.assert_separate(run, source)
    before_world, before_cache, before_code = native.file_manifest(world), native.file_manifest(cache), code_manifest(root)
    probe_id = str(uuid.UUID(args.probe_id)) if args.probe_id else str(uuid.uuid4())
    report = {'status': 'RUNNING', 'module': args.module, 'probeId': probe_id,
              'scope': 'actual-native-mixin-controlled-objects-not-natural-starts',
              'naturalStructureAcceptance': False, 'sourceSHA256': before_code,
              'target': [extra.target_x, extra.target_z]}
    try:
        preparation = list(remaining)
        preparation.remove('--execute')
        preparation += ['--prepare-only', '--probe-id', probe_id]
        if native.main(preparation):
            raise ValueError('immutable source copy preparation failed')
        args.chunk_x, args.chunk_z = extra.target_x, extra.target_z
        args.require_native_runtime = True
        options = ['tellus.persistence.probe=true', f'tellus.persistence.probe.id={probe_id}',
                   f'tellus.persistence.probeRoot={run / "world"}', 'tellus.persistence.probe.structureMixins=true',
                   'tellus.persistence.probe.awaitDetails=true', 'tellus.sphere.syntheticSampler=false',
                   f'tellus.gameDir={run}', f'tellus.configDir={run / "config"}', 'tellus.chunkgen.threads.max=1']
        environment = os.environ.copy()
        for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
            if 'tellus.' in environment.get(name, ''):
                raise ValueError('ambient Tellus JVM options require a clean environment')
        environment['JAVA_TOOL_OPTIONS'] = ' '.join(filter(None, [environment.get('JAVA_TOOL_OPTIONS', ''),
            *(f'"-D{option}"' for option in options)]))
        command = [str(root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')),
                   f'-PtellusTargets={args.module}', f'-PtellusRunDir={run}', f'-PtellusBenchmarkHeap={args.heap}',
                   f':{args.module}:runServer', '--args=--nogui', '--offline', '--no-daemon',
                   '--no-parallel', '--max-workers=1', '--console=plain']
        report['command'], report['jvmProperties'] = command, options
        phase = native.run_phase(args, run, run / 'evidence', command, environment, 'generate', probe_id)
        report['phase'] = phase
        observed = phase['native'].get('monumentNativeMixin', {})
        if observed.get('passed') is not True or observed.get('childCount', 0) < 1:
            raise ValueError('nonempty actual transformed monument mixin evidence required')
        if observed.get('reload', {}).get('loadedRootYPreserved') is not True or observed.get('seaBranch', {}).get('actualResult') != 37:
            raise ValueError('actual native room reload/shifted sea branch differs')
        if phase['native'].get('naturalStructureAcceptance') is not False:
            raise ValueError('controlled native-object smoke must not claim natural-start acceptance')
        report['status'] = 'PASS'
        return 0
    except BaseException as error:
        report['status'], report['error'] = 'FAILED', f'{type(error).__name__}: {error}'
        raise
    finally:
        report['sourceWorldAndCacheUnchanged'] = native.file_manifest(world) == before_world and native.file_manifest(cache) == before_cache
        report['sourceCodeUnchangedDuringRun'] = code_manifest(root) == before_code
        if not report['sourceWorldAndCacheUnchanged'] or not report['sourceCodeUnchangedDuringRun']:
            report['status'] = 'FAILED'
        if run.is_dir():
            native.write_json(run / 'structure-smoke.json', report)
        if not report['sourceWorldAndCacheUnchanged'] or not report['sourceCodeUnchangedDuringRun']:
            raise ValueError('source world/cache/code changed during native structure smoke')


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f'native structure smoke: {type(error).__name__}: {error}', file=sys.stderr)
        raise SystemExit(2)
