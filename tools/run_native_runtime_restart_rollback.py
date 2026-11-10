#!/usr/bin/env python3
"""Reload an accepted default-generated native world, then verify explicit legacy startup rollback.

Does not copy/reset chunks or populate lifecycle payloads; the accepted A/B candidate is reused.
"""
import argparse
import json
import os
from pathlib import Path
import sys
import uuid
import run_native_persistence_roundtrip as native
from run_native_generation_parity import code_manifest


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--pair-dir', type=Path, required=True)
    parser.add_argument('--project-root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--timeout', type=int, default=1200)
    parser.add_argument('--execute', action='store_true')
    extra = parser.parse_args(argv)
    if not extra.execute:
        raise ValueError('explicit actual native execution required')
    root, pair = extra.project_root.resolve(strict=True), extra.pair_dir.resolve(strict=True)
    source = json.loads((pair / 'generation-parity.json').read_text(encoding='utf-8'))
    if source['status'] != 'PASS' or source['lanes'][1]['label'] != 'candidate':
        raise ValueError('accepted complete native A/B generation required')
    generated = source['lanes'][1]['native']
    if generated.get('runtimeLifecycle', {}).get('configuredMode') != 'UNIFIED' or any(
        option.startswith('tellus.worldgen.pipeline=') for option in source['lanes'][1]['jvmProperties']):
        raise ValueError('candidate must have used the actual no-flag native default')
    if Path(source['lanes'][1]['project']).resolve(strict=True) != root:
        raise ValueError('candidate code identity differs')
    before = code_manifest(root)
    if before != source['code']['candidate']:
        raise ValueError('native default source changed since accepted generation')
    run = pair / 'candidate'
    world, cache = Path(source['world']).resolve(strict=True), Path(source['cache']).resolve(strict=True)
    native.assert_separate(run, world); native.assert_separate(run, cache)
    world_before, cache_before = native.file_manifest(world), native.file_manifest(cache)
    if world_before != json.loads((pair / 'source-world-manifest.json').read_text(encoding='utf-8')) or cache_before != json.loads((pair / 'source-cache-manifest.json').read_text(encoding='utf-8')):
        raise ValueError('original real world/cache changed since generation')
    report_path = pair / 'runtime-restart-rollback.json'
    if report_path.exists() or any((pair / f'restart-{mode}').exists() for mode in ('default', 'legacy')):
        raise ValueError('fresh restart evidence directories required; no reset or evidence reuse')
    args = native.parse_args(['--module', source['module'], '--world-template', str(world), '--cache-template', str(cache),
        '--run-dir', str(run), '--project-root', str(root), '--chunk-x', str(source['target'][0]), '--chunk-z', str(source['target'][1]),
        '--dimension', generated['binding']['dimension'], '--port', '25706', '--source-origin', source['sourceOrigin'],
        '--provenance-file', str(pair / 'generation-parity.json'),
        '--timeout', str(extra.timeout), '--execute'])
    properties = dict(line.split('=', 1) for line in (run / 'server.properties').read_text(encoding='utf-8').splitlines()
                      if '=' in line and not line.lstrip().startswith('#'))
    args.port = int(properties['server-port'])
    report = {'status': 'RUNNING', 'module': args.module, 'generation': generated, 'phases': [], 'code': before,
              'scope': 'same native default-generated world reload then explicit legacy startup rollback; no chunk reset or lifecycle repopulation'}
    try:
        for mode in ('default', 'legacy'):
            args.require_native_runtime = mode == 'default'
            probe = str(uuid.uuid4()); env = os.environ.copy()
            for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
                if 'tellus.' in env.get(key, ''):
                    raise ValueError('ambient Tellus JVM options require a clean environment')
            options = ['tellus.persistence.probe=true', f'tellus.persistence.probe.id={probe}', f'tellus.persistence.probeRoot={run / "world"}',
                f'tellus.gameDir={run}', f'tellus.configDir={run / "config"}', 'tellus.sphere.syntheticSampler=false', 'tellus.chunkgen.threads.max=1']
            options += ['tellus.worldgen.pipeline=legacy'] if mode == 'legacy' else ['tellus.persistence.probe.runtimeFamilies=true']
            env['JAVA_TOOL_OPTIONS'] = ' '.join(filter(None, [env.get('JAVA_TOOL_OPTIONS', ''), *(f'"-D{option}"' for option in options)]))
            directory = pair / f'restart-{mode}'; directory.mkdir()
            phase = native.run_phase(args, run, directory, source['lanes'][1]['command'], env, 'runtime_reload', probe)
            data = phase['native']; runtime = data.get('runtimeLifecycle', {})
            if any(data.get(current) != generated.get(original) for current, original in (
                ('blockDigest', 'generatedBlockDigest'), ('biomeDigest', 'generatedBiomeDigest'), ('nativeNBTDigest', 'generatedNativeNBTDigest'))):
                raise ValueError('complete native generated terrain/biomes/NBT changed on reload or legacy rollback')
            if data.get('nativeSavedPayloadEqual') is not True or data['binding']['generatorProfile'] != generated['binding']['generatorProfile']:
                raise ValueError('native engine save or generator profile changed')
            if runtime.get('configuredMode') != ('UNIFIED' if mode == 'default' else 'LEGACY'):
                raise ValueError('native default/rollback selection differs')
            if mode == 'default':
                if runtime.get('registrations') != 3 or runtime.get('attached') != 3 or not runtime.get('targetAttached') or runtime.get('tickCallbacks', 0) <= 0:
                    raise ValueError('three native family runtime adapters did not reattach/tick')
                earth = next(a for a in runtime['adapters'] if a['adapter'] == 'EarthGeneratorRuntimeAdapter')
                if earth['nativeDeferredTickCallbacks'] != runtime['tickCallbacks'] or earth['nativeUnloadCallbacks'] != 1:
                    raise ValueError('native Earth callback duplicated or missing after restart')
            else:
                if any(runtime.get(key) != 0 for key in ('registrations', 'attached', 'tickCallbacks')) or runtime.get('targetAttached') or runtime.get('adapters') != []:
                    raise ValueError('legacy rollback retained active unified adapters')
                log = Path(phase['log']).read_text(encoding='utf-8')
                if 'TELLUS_NATIVE_RUNTIME_LEGACY' not in log or 'TELLUS_NATIVE_RUNTIME_ATTACHED_BEFORE_LEVELS' in log:
                    raise ValueError('legacy native startup did not bypass unified lifecycle')
            phase.update({'jvmProperties': options, 'mode': mode}); report['phases'].append(phase)
            native.write_json(report_path, report)
        report['status'] = 'PASS'
        return 0
    except BaseException as error:
        report['status'] = 'FAILED'; report['error'] = f'{type(error).__name__}: {error}'
        raise
    finally:
        report['sourceCodeUnchanged'] = code_manifest(root) == before
        report['sourceWorldAndCacheUnchanged'] = native.file_manifest(world) == world_before and native.file_manifest(cache) == cache_before
        if not report['sourceCodeUnchanged'] or not report['sourceWorldAndCacheUnchanged']:
            report['status'] = 'FAILED'
        native.write_json(report_path, report)
        if not report['sourceCodeUnchanged'] or not report['sourceWorldAndCacheUnchanged']:
            raise ValueError('immutable source code/world/cache changed')
        if report['status'] == 'PASS':
            print('ACTUAL_NATIVE_DEFAULT_RESTART_AND_LEGACY_ROLLBACK_PASS', args.module, flush=True)


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f'native runtime restart: {type(error).__name__}: {error}', file=sys.stderr)
        raise SystemExit(2)
