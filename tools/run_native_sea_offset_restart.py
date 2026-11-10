#!/usr/bin/env python3
"""Verify native Cube offset Codec semantics across three actual server processes.

Only an explicitly disposable world copy's two existing generator fields are
configured before startup. Native Minecraft owns every subsequent save/encode.
The original historical world/cache and production code are fingerprinted.
"""
from __future__ import annotations
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import struct
import sys
import uuid
import run_native_persistence_roundtrip as native
from run_native_generation_parity import code_manifest
from run_native_crash_recovery import disk_tag


def field_ranges(raw: bytes) -> dict:
    reader = native.NBTReader(raw)
    if reader.number('B') != 10:
        raise ValueError('native level root must be a compound')
    reader.string()
    ranges = {}
    def compound(path, depth):
        if depth > 128:
            raise ValueError('bounded level compound depth exceeded')
        names = set()
        while True:
            start = reader.offset
            kind = reader.number('B')
            if kind == 0:
                return
            name = reader.string()
            if name in names:
                raise ValueError('duplicate level compound key')
            names.add(name)
            key = path + (name,)
            if kind == 10:
                compound(key, depth + 1)
            else:
                reader.value(kind)
            ranges[key] = (start, reader.offset, kind)
    compound((), 0)
    if reader.offset != len(raw):
        raise ValueError('trailing level data')
    return ranges


def text(value: str) -> bytes:
    encoded = value.encode('ascii')
    return struct.pack('>H', len(encoded)) + encoded


def tag(kind: int, name: str, value: bytes) -> bytes:
    return bytes([kind]) + text(name) + value


def generator_record(world: Path, dimension: str) -> dict:
    raw = native.decompress((world / 'level.dat').read_bytes(), 1)
    return native.NBTReader(raw).root()['Data']['WorldGenSettings']['dimensions'][dimension]['generator']


def configure_copy(world: Path, dimension: str, height_offset: int, encoded_sea: int) -> dict:
    """Replace only typed biome_source and sea_level fields; all other bytes stay exact."""
    file = world / 'level.dat'
    original = file.read_bytes()
    raw = native.decompress(original, 1)
    decoded = native.NBTReader(raw).root()
    generator = decoded['Data']['WorldGenSettings']['dimensions'][dimension]['generator']
    if generator.get('type') != 'tellus:cube_net' or set(generator['biome_source']) != {'biome'}:
        raise ValueError('explicit original fixed-biome Cube save required')
    if height_offset == 64:
        raise ValueError('real non-default offset required')
    source = b''.join((
        tag(6, 'world_scale', struct.pack('>d', generator['world_scale'])),
        tag(3, 'apron_chunks', struct.pack('>i', generator.get('experimental_apron_chunks', 5))),
        *(tag(6, key, struct.pack('>d', generator[key])) for key in
          ('excluded_radius_meters', 'transition_width_meters') if key in generator),
        tag(10, 'fallback', tag(8, 'biome', text(generator['biome_source']['biome'])) + b'\0'),
        tag(10, 'settings', tag(3, 'height_offset', struct.pack('>i', height_offset)) + b'\0'), b'\0'))
    path = ('Data', 'WorldGenSettings', 'dimensions', dimension, 'generator')
    ranges = field_ranges(raw)
    replacements = []
    for name, kind, payload in [('biome_source', 10, source), ('sea_level', 3, struct.pack('>i', encoded_sea))]:
        start, end, actual_kind = ranges[path + (name,)]
        if actual_kind != kind:
            raise ValueError('existing native generator field type differs')
        replacements.append((start, end, tag(kind, name, payload)))
    for start, end, payload in sorted(replacements, reverse=True):
        raw = raw[:start] + payload + raw[end:]
    after = native.NBTReader(raw).root()
    configured = after['Data']['WorldGenSettings']['dimensions'][dimension]['generator']
    normalized = json.loads(json.dumps(configured))
    normalized['biome_source'], normalized['sea_level'] = generator['biome_source'], generator['sea_level']
    if normalized != generator:
        raise ValueError('unrelated generator fields changed')
    # Exact byte restoration independently validates that only these fields changed.
    new_ranges = field_ranges(raw)
    restored = raw
    originals = []
    for name in ('biome_source', 'sea_level'):
        old_start, old_end, _ = ranges[path + (name,)]
        start, end, _ = new_ranges[path + (name,)]
        originals.append((start, end, native.decompress(original, 1)[old_start:old_end]))
    for start, end, payload in sorted(originals, reverse=True):
        restored = restored[:start] + payload + restored[end:]
    if restored != native.decompress(original, 1):
        raise ValueError('configuration touched unrelated level bytes')
    file.write_bytes(gzip.compress(raw, mtime=0))
    if generator_record(world, dimension) != configured:
        raise ValueError('prepared copied native level fields do not reread')
    return {'before': generator, 'configured': configured, 'onlyTwoExistingFieldsChanged': True,
            'originalLevelSHA256': __import__('hashlib').sha256(original).hexdigest(),
            'configuredLevelSHA256': native.sha256(file)}


def configure_bootstrap_copy(world: Path, dimension: str, height_offset: int, encoded_sea: int) -> dict:
    namespace, name = dimension.split(':', 1)
    definitions = list(world.glob(f'datapacks/*/data/{namespace}/dimension/{name}.json'))
    if not definitions:
        return {'authority': 'saved-level', **configure_copy(world, dimension, height_offset, encoded_sea)}
    if len(definitions) != 1:
        raise ValueError('one explicit copied dimension definition required')
    file = definitions[0].resolve(strict=True)
    if not file.is_relative_to(world.resolve(strict=True)):
        raise ValueError('copied dimension definition escaped world root')
    definition = json.loads(file.read_text(encoding='utf-8'))
    generator = definition['generator']
    if generator.get('type') != 'tellus:cube_net' or set(generator['biome_source']) != {'biome'} or height_offset == 64:
        raise ValueError('explicit fixed-biome native Cube definition and non-default offset required')
    before = json.loads(json.dumps(generator))
    source = {'world_scale': generator['world_scale'], 'apron_chunks': generator.get('experimental_apron_chunks', 5),
              'fallback': generator['biome_source'], 'settings': {'height_offset': height_offset}}
    for key in ('excluded_radius_meters', 'transition_width_meters'):
        if key in generator:
            source[key] = generator[key]
    original_sha = native.sha256(file)
    generator['biome_source'] = source
    generator['sea_level'] = encoded_sea
    normalized = {**generator, 'biome_source': before['biome_source'], 'sea_level': before['sea_level']}
    if normalized != before:
        raise ValueError('active native geometry/cap/profile changed during bootstrap configuration')
    file.write_text(json.dumps(definition, indent=2)+'\n', encoding='utf-8')
    return {'authority': 'copied-datapack-first-process-only', 'definition': str(file), 'before': before,
            'configured': generator, 'onlyTwoExistingFieldsChanged': True, 'originalDefinitionSHA256': original_sha,
            'configuredDefinitionSHA256': native.sha256(file), 'savedLevelUnmodifiedBeforeNativeBoot': True}


def main(argv=None):
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument('--height-offset', type=int, required=True)
    parser.add_argument('--encoded-sea', type=int, required=True)
    extra, remaining = parser.parse_known_args(argv)
    args = native.parse_args(remaining)
    if not args.execute or args.populate_lifecycle:
        raise ValueError('offset restart requires explicit execution, not lifecycle repopulation')
    if not -2032 <= extra.height_offset <= 2016 or not -2032 <= extra.encoded_sea <= 2016:
        raise ValueError('configured copied-world fields exceed native bounds')
    source_world, source_cache = args.world_template.resolve(strict=True), args.cache_template.resolve(strict=True)
    root, run = args.project_root.resolve(strict=True), args.run_dir.resolve()
    if run.exists():
        raise ValueError('fresh disposable offset run required')
    world_before, cache_before, code_before = native.file_manifest(source_world), native.file_manifest(source_cache), code_manifest(root)
    prepare = list(remaining); prepare.remove('--execute'); prepare.append('--prepare-only')
    if native.main(prepare):
        raise ValueError('native source admission/copy failed')
    evidence = run / 'evidence'
    report = {'status': 'RUNNING', 'module': args.module, 'heightOffset': extra.height_offset,
              'originalEncodedSea': extra.encoded_sea, 'expectedEffectiveSea': extra.encoded_sea + extra.height_offset - 64,
              'scope': 'real settings-carrying Cube old-field decode and native level save across three separate processes; no region writer or new schema',
              'syntheticSampler': False, 'phases': [], 'code': code_before}
    try:
        report['configuration'] = configure_bootstrap_copy(run / 'world', args.dimension, extra.height_offset, extra.encoded_sea)
        for number in range(3):
            probe_id = str(uuid.uuid4())
            env = os.environ.copy()
            for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
                if 'tellus.' in env.get(key, ''):
                    raise ValueError('ambient Tellus JVM configuration requires a clean environment')
            options = ['tellus.persistence.probe=true', f'tellus.persistence.probe.id={probe_id}',
                       f'tellus.persistence.probeRoot={run / "world"}', 'tellus.sphere.syntheticSampler=false',
                       f'tellus.gameDir={run}', f'tellus.configDir={run / "config"}', 'tellus.chunkgen.threads.max=1',
                       f'tellus.persistence.probe.expectedHeightOffset={extra.height_offset}',
                       f'tellus.persistence.probe.expectedSeaLevel={report["expectedEffectiveSea"]}',
                       f'tellus.persistence.probe.allowUpgrade={str(args.allow_template_upgrade).lower()}', 'tellus.worldgen.pipeline=unified']
            env['JAVA_TOOL_OPTIONS'] = ' '.join(filter(None, [env.get('JAVA_TOOL_OPTIONS', ''), *(f'"-D{option}"' for option in options)]))
            phase_dir = evidence / f'process-{number}'; phase_dir.mkdir()
            command = [str(root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')),
                       f'-PtellusTargets={args.module}', f'-PtellusRunDir={run}', f'-PtellusBenchmarkHeap={args.heap}',
                       f':{args.module}:runServer', '--args=--nogui', '--offline', '--no-daemon', '--no-parallel', '--max-workers=1', '--console=plain']
            phase = native.run_phase(args, run, phase_dir, command, env, 'sea_codec', probe_id)
            data = phase['native']
            if data.get('nativeHeightOffset') != extra.height_offset or data.get('nativeSeaLevel') != report['expectedEffectiveSea'] or not data.get('nativeSavedPayloadEqual') or not data.get('settingsCarryingBiomeSource'):
                raise ValueError('native non-default runtime/save report differs')
            serialized = generator_record(run / 'world', args.dimension)
            if serialized.get('sea_level') != extra.encoded_sea or serialized.get('biome_source', {}).get('settings', {}).get('height_offset') != extra.height_offset:
                raise ValueError('native level save changed original encoded sea or height offset')
            phase.update({'command': command, 'jvmProperties': options, 'savedGenerator': serialized,
                          'savedLevelSHA256': native.sha256(run / 'world/level.dat')})
            independent = disk_tag(run / 'world', args.dimension, args.chunk_x, args.chunk_z)
            for clock in ('LastUpdate', 'InhabitedTime'):
                independent.pop(clock, None)
            phase['independentDiskNBTDigest'] = hashlib.sha256(json.dumps(independent, sort_keys=True,
                separators=(',', ':'), default=lambda value: {'nativeArrayBytes': value.hex()}).encode()).hexdigest()
            report['phases'].append(phase)
            if number == 0 and report['configuration']['authority'] == 'copied-datapack-first-process-only':
                # Native registry dimensions override saved LevelStems while present.
                # Retain only the dimension-type resource after the first native save,
                # so the two later processes must decode the saved generator fields.
                definition = Path(report['configuration']['definition']).resolve(strict=True)
                destination = (evidence / 'dimension-definition-disabled-after-first-native-save.json').resolve()
                if not definition.is_relative_to((run / 'world').resolve(strict=True)) or destination.exists():
                    raise ValueError('owned copied definition move target differs')
                if native.sha256(definition) != report['configuration']['configuredDefinitionSHA256']:
                    raise ValueError('configured dimension definition changed before stored-codec admission')
                definition.rename(destination)
                report['datapackDimensionOverrideRemovedAfterFirstNativeSave'] = True
                report['retainedFirstProcessDimensionDefinition'] = str(destination)
            native.write_json(evidence / 'sea-offset-restart.json', report)
        for key in ('blockDigest', 'biomeDigest', 'nativeNBTDigest', 'binding'):
            if any(p['native'].get(key) != report['phases'][0]['native'].get(key) for p in report['phases'][1:]):
                raise ValueError(f'native restart payload/profile changed: {key}')
        if len({p['pid'] for p in report['phases']}) != 3:
            raise ValueError('three separate native processes required')
        if any(p['savedGenerator'] != report['phases'][0]['savedGenerator'] for p in report['phases'][1:]):
            raise ValueError('serialized generator changed between native process restarts')
        if any(p['independentDiskNBTDigest'] != report['phases'][0]['independentDiskNBTDigest'] for p in report['phases'][1:]):
            raise ValueError('independent complete Anvil payload changed between restarts')
        report['status'] = 'PASS'
        return 0
    except BaseException as error:
        report['status'] = 'FAILED'; report['error'] = f'{type(error).__name__}: {error}'
        raise
    finally:
        report['sourceWorldAndCacheUnchanged'] = native.file_manifest(source_world) == world_before and native.file_manifest(source_cache) == cache_before
        report['sourceCodeUnchangedDuringRun'] = code_manifest(root) == code_before
        if not report['sourceWorldAndCacheUnchanged'] or not report['sourceCodeUnchangedDuringRun']:
            report['status'] = 'FAILED'
        native.write_json(evidence / 'sea-offset-restart.json', report)
        if not report['sourceWorldAndCacheUnchanged'] or not report['sourceCodeUnchangedDuringRun']:
            raise ValueError('immutable source world/cache/code changed')
        if report['status'] == 'PASS':
            print('REAL_NATIVE_SEA_OFFSET_RESTART_PASS', args.module, report['expectedEffectiveSea'], flush=True)


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f'native sea offset restart: {type(error).__name__}: {error}', file=sys.stderr)
        raise SystemExit(2)
