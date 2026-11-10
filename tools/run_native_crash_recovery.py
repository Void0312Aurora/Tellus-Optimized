#!/usr/bin/env python3
"""Real native two-chunk hard-stop -> failed recovery/quarantine -> recovery -> second restart.

All writes use the server's existing ChunkMap queue. Independent Python Anvil reads
prove the first chunk was changed and the second untouched at the hard-stop point.
A fresh disposable copy is mandatory; original world/cache/code are fingerprinted.
"""
from __future__ import annotations
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time
import uuid
import run_native_persistence_roundtrip as native


def disk_tag(world: Path, dimension: str, x: int, z: int) -> dict:
    path = native.dimension_root(world, dimension) / 'region' / f'r.{x//32}.{z//32}.mca'
    with path.open('rb') as stream:
        stream.seek(4*((x%32)+(z%32)*32))
        entry = int.from_bytes(stream.read(4), 'big')
        sector, count = entry >> 8, entry & 255
        if sector < 2 or count == 0:
            raise ValueError('existing native record required')
        stream.seek(sector*4096)
        size = int.from_bytes(stream.read(4), 'big')
        if not 1 < size <= count*4096-4:
            raise ValueError('native record bounds differ')
        compression = stream.read(1)[0]
        if compression & 128:
            raise ValueError('external record needs an explicit bounded reader')
        encoded = stream.read(size-1)
        if len(encoded) != size-1:
            raise ValueError('native record truncated')
    result = native.NBTReader(native.decompress(encoded, compression)).root()
    if (result.get('xPos'), result.get('zPos')) != (x,z):
        raise ValueError('native record coordinates differ')
    return result


def changed_tag(original: dict) -> dict:
    candidate = copy.deepcopy(original)
    for section in candidate.get('sections', []):
        for palette in section.get('block_states', {}).get('palette', []):
            if palette.get('Name') == 'minecraft:stone':
                palette['Name'] = 'minecraft:gold_block'
                return candidate
    raise ValueError('native stone palette required')


def journal_state(run: Path, expected: str) -> dict:
    paths = list((run / 'world/tellus/persistence-journal').glob('*.prepared'))
    if len(paths) != 1:
        raise ValueError(f'exactly one pending batch journal required: {paths}')
    path = paths[0]
    text = path.read_text(encoding='utf-8')
    lines = text.rstrip('\n').split('\n')
    checksum = lines[-1]
    if checksum != 'sha256=' + hashlib.sha256(('\n'.join(lines[:-1])+'\n').encode()).hexdigest():
        raise ValueError('pending native journal checksum differs')
    fields = dict(line.split('=',1) for line in lines[1:-1])
    if fields.get('state') != expected or fields.get('count') != '2':
        raise ValueError('pending native journal state/count differs')
    return {'path':str(path), 'sha256':native.sha256(path), 'state':expected,
            'count':2, 'bytes':path.stat().st_size}


def abnormal_phase(args, run: Path, command: list, env: dict, phase: str, probe_id: str) -> dict:
    native.port_free(args.port)
    path = run/'evidence'/f'{phase}.server.log'
    creation = {'creationflags':subprocess.CREATE_NEW_PROCESS_GROUP} if os.name=='nt' else {'start_new_session':True}
    job = native.OwnedWindowsJob()
    process = None
    reader = None
    lines = queue.Queue()
    try:
        process = subprocess.Popen(command, cwd=args.project_root, env=env, stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', bufsize=1, **creation)
        job.assign(process)
        def output():
            try:
                with path.open('w',encoding='utf-8') as stream:
                    for line in process.stdout:
                        stream.write(line);stream.flush();lines.put(line)
            finally: lines.put(None)
        reader = threading.Thread(target=output,daemon=True);reader.start()
        deadline=time.monotonic()+args.timeout
        submitted=False;ready=False;boundary=False;fault=False
        while True:
            if time.monotonic()>deadline:
                raise TimeoutError(f'{phase} exceeded deadline; see {path}')
            try: line=lines.get(timeout=.25)
            except queue.Empty:
                if process.poll() is not None:break
                continue
            if line is None:break
            print(line,end='',flush=True)
            if 'Done (' in line and 'For help, type' in line:
                ready=True
                if phase=='failed_recovery':raise ValueError('quarantined startup reached ordinary server readiness')
                if not submitted:
                    process.stdin.write(f'execute in {args.dimension} run tellus persistence crash_batch {args.chunk_x} {args.chunk_z}\n')
                    process.stdin.flush();submitted=True
            boundary |= 'TELLUS_NATIVE_BATCH_CRASH_BOUNDARY' in line
            fault |= 'explicit copied-world startup recovery fault' in line
        code=process.wait(timeout=args.stop_timeout)
        reader.join(timeout=5)
        result={'phase':phase,'launcherPid':process.pid,'exitCode':code,'log':str(path),'logSha256':native.sha256(path)}
        if phase=='crash_batch':
            if code==0:raise ValueError('hard-stop process unexpectedly exited normally')
            report=run/'tellus/persistence'/f'{probe_id}-crash_batch.json'
            data=json.loads(report.read_text(encoding='utf-8'))
            if not submitted or not boundary or data.get('status')!='CRASH_BOUNDARY':raise ValueError('real hard-stop boundary missing')
            for field in ('firstNativeChunkWritten','secondNativeChunkUntouched','nativeSaveFenceActive','nativeTargetsUnloaded','diskReadBeforeDemandLoad'):
                if data.get(field) is not True:raise ValueError('hard-stop native proof missing: '+field)
            result.update(native=data, report=str(report), reportSha256=native.sha256(report))
        elif ready or not fault:raise ValueError('explicit startup-recovery failure proof missing')
        else:
            # Minecraft catches initServer failure and may shut down with exit 0.
            # Require the actual recovery fault and absence of readiness, not a
            # launcher exit status that the native server does not guarantee.
            result['startupAbortedBeforeReadiness']=True
        return result
    finally:
        if process is not None:
            native.stop_owned(process,args.stop_timeout)
        job.close()
        if reader is not None:reader.join(timeout=5)
        if process is not None:
            if process.stdin:process.stdin.close()
            if process.stdout:process.stdout.close()


def main(argv=None) -> int:
    original_args=list(sys.argv[1:] if argv is None else argv)
    args=native.parse_args(original_args)
    if not args.execute or args.populate_lifecycle:raise ValueError('crash acceptance requires --execute without unrelated lifecycle population')
    run=args.run_dir.resolve();project=args.project_root.resolve(strict=True)
    world=args.world_template.resolve(strict=True);cache=args.cache_template.resolve(strict=True)
    if run.exists():raise ValueError('fresh disposable run directory required; no reset or deletion')
    originals=[disk_tag(world,args.dimension,args.chunk_x+i,args.chunk_z) for i in range(2)]
    for i in range(2):native.inspect_world(world,args.dimension,args.chunk_x+i,args.chunk_z)
    source_world=native.file_manifest(world);source_cache=native.file_manifest(cache)
    java_files=[p for base in ('src','shared',f'{args.module}/src') for p in (project/base).rglob('*.java')]
    code={str(p.relative_to(project)):native.sha256(p) for p in java_files}
    probe=str(uuid.UUID(args.probe_id)) if args.probe_id else str(uuid.uuid4())
    prepare=original_args.copy();prepare.remove('--execute');prepare+=['--prepare-only','--probe-id',probe]
    native.main(prepare)
    result={'status':'RUNNING','module':args.module,'probeId':probe,'target': [args.chunk_x,args.chunk_z],
            'sourceOrigin':args.source_origin,'sourceCodeSHA256':code,'phases':[],
            'scope':'real process-termination recovery through native ChunkMap queue; not power-loss atomicity'}
    path=run/'evidence/crash-recovery.json'
    env=os.environ.copy()
    for name in ('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS'):
        if 'tellus.' in env.get(name,''):raise ValueError('ambient Tellus JVM options require clean environment')
    options=['tellus.persistence.probe=true',f'tellus.persistence.probe.id={probe}',
             f'tellus.persistence.probeRoot={run/"world"}','tellus.persistence.probe.crash=true',
             'tellus.sphere.syntheticSampler=false',f'tellus.gameDir={run}',f'tellus.configDir={run/"config"}',
             'tellus.chunkgen.threads.max=1']
    env['JAVA_TOOL_OPTIONS']=' '.join(filter(None,[env.get('JAVA_TOOL_OPTIONS',''),*(f'"-D{p}"' for p in options)]))
    command=[str(project/('gradlew.bat' if os.name=='nt' else 'gradlew')),f'-PtellusTargets={args.module}',
             f'-PtellusRunDir={run}',f'-PtellusBenchmarkHeap={args.heap}',f':{args.module}:runServer','--args=--nogui',
             '--offline','--no-daemon','--no-parallel','--max-workers=1','--console=plain']
    result.update(command=command,jvmProperties=options)
    try:
        result['phases'].append(abnormal_phase(args,run,command,env,'crash_batch',probe))
        if disk_tag(run/'world',args.dimension,args.chunk_x,args.chunk_z)!=changed_tag(originals[0]):raise ValueError('independent Anvil read did not find first partial publication')
        if disk_tag(run/'world',args.dimension,args.chunk_x+1,args.chunk_z)!=originals[1]:raise ValueError('second chunk changed before hard stop')
        result['independentPartialDiskStateVerified']=True
        result['preparedJournal']=journal_state(run,'PREPARED')
        native.write_json(path,result)
        fault_env=env.copy();fault_env['JAVA_TOOL_OPTIONS']+=' "-Dtellus.persistence.probe.recoveryFailure=true"'
        result['phases'].append(abnormal_phase(args,run,command,fault_env,'failed_recovery',probe))
        result['quarantinedJournal']=journal_state(run,'QUARANTINED')
        result['failedRecoveryNeverReachedNormalReadiness']=True
        native.write_json(path,result)
        for phase in ('recover_batch','verify_batch'):
            result['phases'].append(native.run_phase(args,run,run/'evidence',command,env,phase,probe))
            for i in range(2):
                if disk_tag(run/'world',args.dimension,args.chunk_x+i,args.chunk_z)!=originals[i]:raise ValueError('independent Anvil recovery read differs')
            if list((run/'world/tellus/persistence-journal').glob('*.prepared')):raise ValueError('journal not finalized after verified recovery')
            native.write_json(path,result)
        result['completeNativeDiskNBTEqualsOriginalAcrossTwoRestarts']=True
        result['status']='PASS';return 0
    except BaseException as error:
        result.update(status='FAILED',error=f'{type(error).__name__}: {error}');raise
    finally:
        intact=native.file_manifest(world)==source_world and native.file_manifest(cache)==source_cache
        source_intact=all(p.is_file() and native.sha256(p)==digest for name,digest in code.items() for p in [project/name])
        result.update(sourceWorldAndCacheUnchanged=intact,sourceCodeUnchangedDuringRun=source_intact)
        if not intact or not source_intact:result['status']='FAILED'
        native.write_json(path,result)
        if not intact or not source_intact:raise ValueError('source world/cache/code changed during actual crash acceptance')


if __name__=='__main__':
    try:raise SystemExit(main())
    except Exception as error:
        print(f'native crash recovery: {type(error).__name__}: {error}',file=sys.stderr);raise SystemExit(2)
