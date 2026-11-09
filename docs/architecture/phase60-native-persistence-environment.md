# Phase60 — Native persistence environment and guarded runner

## Verified real GIS save (read-only discovery, 2026-11-09)

Selected immutable source world:

`D:\workshop\Game\mc-pilot-deployment\run\tellus-boundary-20261025\world-terrain-20261026`

Selected source cache:

`D:\workshop\Game\mc-pilot-deployment\run\tellus-boundary-20261025\tellus\cache`

This is **not** the historical synthetic seam fixture. Provenance is recorded in
`D:\workshop\Game\Tellus-Optimized-rfc\docs\cube-net-client-acceptance.md`
lines1489–1523 and its `docs/validation/terrain-stability-20261026/summary.json`.
Those records explicitly identify real Mapterhorn elevation/land-cover readers,
`syntheticSampler:false`, world scale1, two25-chunk Swiss/Chengdu areas, saved
reload and warmed-cache regeneration. The runner hashes the selected source files,
cache files and supplied provenance records, and requires an explicit
`--source-origin` declaration. The origin string and historic documents are
caller-supplied provenance assertions; the runner does not independently derive
Swiss coordinates from raster bytes or prove global live-GIS coverage. Full
original first/reload/repeat logs and saved-region inspection remain under
`D:\workshop\Game\mc-pilot-deployment\terrain-stability-20261026`.
The historical installed server used Tellus0.8.3/Fabric1.20.1; current persistence
acceptance must execute the current build on a fresh copied world, not reuse that
old result as a current-build PASS.

Read-only compressed native NBT confirmed:

| Item | Observed value |
|---|---|
| Minecraft/DataVersion | 1.20.1 /3465 |
| World seed |2025402140 |
| Overworld spawn |0,87,0 |
| Target dimension |`tellus:earth_cube_net` |
| Generator |`tellus:cube_net`, geometry3, `rancic-ccam30-double-v1` |
| Scale / minY / height |1.0 /-2032 /4064 |
| Primary GIS location |Swiss46.02176N,7.74811E |
| Primary stored chunk |616246,-351597; `minecraft:full`;254sections |
| Primary block palette |air,bedrock,stone |
| Alternative GIS location |Chengdu30.67N,104.06E; chunk226404,-724012 |

Primary fingerprints:

- Native `level.dat` SHA-256:
  `896bab856a28b19a0e4ed5c3e80247793a6807712ae9445e5a4ef7f801ba9eed`
- Swiss region `r.19257.-10988.mca` SHA-256:
  `ab575be30328ca6108c1df1081b93e25ad27ac7267d2467d8d7cb8e17cc2012a`
- Swiss616246,-351597 decompressed complete native NBT SHA-256:
  `12d55d10b57afb21fce6dd38dc158096744859a0810f96ec96820dfb0e3b2f8d`
- Original real-GIS summary SHA-256:
  `cbb0d1b92013d4a154e71849def633e7901ca58731292efa7d6522aea509d3e8`

Cache inventory under the exact selected cache root:

| Source | Files | Bytes | Observed samples |
|---|---:|---:|---|
| elevation-mapterhorn |3|348458|`12/3231/1681.webp`, `16/34178/23308.webp` |
| koppen |1|12590712|`koppen_geiger_0p00833333.tif` |
| map |2|738|land-mask PNGs for tile10/534/364 and10/807/420 |
| worldcover-2021-v200-range |4|202394|N30E102 index and f1/27-24 deflate record |

These are actual local raster/cache files, not invented source fixtures. They
establish available bounded provenance, not complete global offline cache
coverage. The target is pre-existing FULL and far from spawn; native demand
regeneration is expressly excluded. Ordinary server startup may still access
services or unrelated chunks. There is no general global cache-only server
property in the inspected source; the runner does not claim to prevent every
network request. It records the existing-world/copied-cache source mode.

## Candidates deliberately not accepted

`D:\workshop\Game\tellus-live-20261023\world` contains a coarse scale1000
Cube-Net dimension and actual cache files, but historical seam setup documentation
explicitly enables `tellus.sphere.syntheticSampler=true`. Cache presence alone
does not establish original chunk provenance. This world and the
`world-synthetic*` saves remain excluded from real GIS persistence evidence.
The old current-checkout `build/spherical-cave-live` fixture is likewise excluded.
No original world, cache, datapack, server config or installed mod was changed.
No build/server/client was launched during this discovery.

## Runner contract

`tools/run_native_persistence_roundtrip.py` provides three mutually exclusive,
explicit modes:

- `--inspect-only`: native level/Anvil admission only; no copying/build/server.
- `--prepare-only`: create a fresh copied world/cache/config and fingerprint
  manifests; no build/server. This does not establish persistence acceptance.
- `--execute`: launch the current version's Loom server, submit capture after the
  real readiness marker, require its UUID-bound native JSON/PASS, send `stop`,
  wait for owned process exit, then launch the same copied world again and submit
  verify. The second phase never resets the copy or reuses pre-existing reports.

Agreed console commands:

```text
execute in tellus:earth_cube_net run tellus persistence capture 616246 -351597
# Runner sends stop and observes owned process exit before restart.
execute in tellus:earth_cube_net run tellus persistence verify 616246 -351597
```

Required properties include:

```text
-Dtellus.persistence.probe=true
-Dtellus.persistence.probe.id=<same UUID across both processes>
-Dtellus.persistence.probeRoot=<absolute copied run/world>
-Dtellus.sphere.syntheticSampler=false
-Dtellus.gameDir=<absolute disposable run>
-Dtellus.configDir=<absolute disposable run/config>
```

The native command publishes `<run>/tellus/persistence/<uuid>-capture.json`,
`<uuid>-verify.json` and expected NBT evidence; console markers are
`TELLUS_PERSISTENCE_CAPTURE_PASS` and `TELLUS_PERSISTENCE_VERIFY_PASS`.
The runner validates phase/UUID/status/coordinates/false synthetic flag and
pre-demand-load disk-read evidence. Final acceptance also requires matching
capture/verify identity, restarted native disk equality and materialized complete
NBT equality as defined by the native probe. It does not substitute cached tags,
plain-file transactions or synthetic JUnit results for actual server acceptance.
Native NBT comparison may exclude only the declared save clocks, not block,
biome, section, tick, light, marker, heightmap or structure data.

Each run target must be a fresh resolved directory outside source world/cache and
project checkout. No runner path is recursively deleted or reused. Source links
are rejected; all original world/cache files are hashed before copying and checked
again after preparation/execution. The copied source is initially byte-identical,
including native save format and datapacks. On failure, copied evidence remains
for inspection; graceful stop is attempted, followed by owned-process-tree
termination. Windows kill-on-close Job Objects also contain orphaned descendants.
Logs have distinct phase files and bounded condition waits, not fixed sleeps.
Ports are explicit and must be free before each start. Build tasks use offline,
no-daemon, no-parallel, one worker. Parent orchestration must keep version runs and
all other Gradle commands strictly serial because project outputs are shared.

## Version distinction and invocation

The source is1.20.1. mc1201 can use it directly. There is no separately verified
original1.21.1 GIS world in this discovery. mc1211 must receive
`--allow-template-upgrade`: only its new disposable copy is upgraded through
Minecraft's native DataFixer, then capture/stop/restart/verify all run on1.21.1.
Record this as **upgraded-template native persistence**, not cross-version
bitwise/save-compatibility parity. An upgrade failure is a failed gate; the
runner never patches native NBT or source datapacks to force acceptance.

Inspection example (the only runner mode executed during discovery):

```powershell
python tools/run_native_persistence_roundtrip.py --inspect-only `
  --module mc1201 `
  --world-template 'D:\workshop\Game\mc-pilot-deployment\run\tellus-boundary-20261025\world-terrain-20261026' `
  --cache-template 'D:\workshop\Game\mc-pilot-deployment\run\tellus-boundary-20261025\tellus\cache' `
  --run-dir 'D:\Temp\tellus-phase60-roundtrip-mc1201-NEW-UNIQUE-ID' `
  --dimension tellus:earth_cube_net --chunk-x 616246 --chunk-z -351597 `
  --port 25661 `
  --source-origin 'Real Mapterhorn/worldcover Swiss GIS saved 2026-10-26' `
  --provenance-file 'D:\workshop\Game\Tellus-Optimized-rfc\docs\validation\terrain-stability-20261026\summary.json'
```

After the parent explicitly approves build/server execution, replace
`--inspect-only` with `--execute` and choose a fresh unique run path. Use a second
fresh path, modulemc1211, port25662, and `--allow-template-upgrade` for1.21.1.
Both profiles require the parent's `tellusRunDir` Gradle wiring and the opt-in
native persistence command. Existing MC Pilot wrapper is
`D:\workshop\Game\mc-pilot-deployment\mct.cmd` (CLI0.20.0); this runner directly
owns the console process instead of changing the historical MC Pilot profile.

## Validation boundary

Read-only primary Swiss native admission succeeded: correct existing FULL record,
correct DataVersion/coordinates,254sections and native stone palette. Source and
runner contract tests do not establish actual persistence until both real server
process phases have executed and their archived report/log contracts pass.

## Executed result and environment corrections

The mc1201 run in `D:/Temp/tellus-phase60-roundtrip-mc1201-20261109-c`
executed capture, three native fault restorations, full loaded-metadata recovery,
normal stop/exit and a second real server start. Verify passed full persisted and
materialized native NBT (only the two declared save clocks excluded), all1,040,384
block cells,16,256 biome quart cells and the non-regenerable GOLD sentinel. The
original46-file world and10-file cache remained byte-identical by file manifests.

The mc1211 run in `D:/Temp/tellus-phase60-roundtrip-mc1211-20261109-a`
failed before readiness during native `level.dat` DataFixer migration. DFU8's
unknown `tellus:cube_net` tagged-choice fallback returned a plain `Codec` where
`MapCodec` was expected. The actual failure log and source-immutability result
are retained; this run is **not** a persistence PASS. A version-local, narrow
mod-generator DFU compatibility layer needs separate review and real rerun. No
source NBT/DataVersion was patched and no vanilla-generator substitution was used.

Two earlier C-drive copied runs failed before game startup: Gradle relativePath
rejects different drive roots, while assigning an absolute string to Loom runDir
causes the project path to be prepended. The original mc1201 Gradle file was
restored exactly. Runner admission now rejects a cross-drive target before copy;
use a same-drive temporary root outside the checkout such as `D:/Temp`. No
original world/cache was changed or any source directory deleted. These failed
attempts are not counted as native execution evidence.
