# Accepted reversible native runtime default

## Result

The requested production migration gates are complete for **mc1201 / mc1211**. Their real loader bootstrap now selects `UNIFIED` without a JVM mode flag. Explicit `-Dtellus.worldgen.pipeline=legacy` restores the legacy generation/lifecycle route at the next startup, with no stored-format migration. Standalone construction stays explicit/legacy until the supported native bootstrap runs; other Minecraft release profiles are not promoted.

This is an actual production connection, not a replacement hierarchy: [the host](../../../src/main/java/com/yucareux/tellus/worldgen/runtime/TellusNativeRuntimeHost.java) registers the existing Earth/Cube/Sphere typed adapters in the existing identity registry and lifecycle manager. The existing generator owns the four native generation stages and existing writer/provider/kernel path. Minecraft still owns chunk statuses, neighbourhood access and saves.

| Final acceptance | mc1201 | mc1211 |
|---|---|---|
| Explicit candidate: actual native three-family attach/tick/generation/save/stop | PASS | PASS |
| No-flag native default: same complete Cube FULL output as old baseline | PASS | PASS |
| Same candidate world, separate-process no-flag reload and engine save | PASS | PASS |
| Same world, separate-process explicit legacy rollback and engine save | PASS | PASS |
| Default registers/attaches existing Earth + Cube + Sphere adapters | 3 / 3 | 3 / 3 |
| Actual Earth native deferred callback | exactly once per host/platform tick | exactly once per host/platform tick |
| Explicit owned Earth unload dispatch through the existing discard method | once | once |
| Default normal stop | all 3 detached; registry empty | all 3 detached; registry empty |
| Legacy rollback registry / attached / unified ticks | 0 / 0 / 0 | 0 / 0 / 0 |
| Final retained serial migration JUnit cohort | **554/554** | **545/545** |
| Failures / errors / skipped | **0 / 0 / 0** | **0 / 0 / 0** |

All 14 Python controller/preparation tests pass. Strict original parity retains **13,824 columns / 42,246,144 states**, zero column differences, all seven original biome cases and only the known `bare/BWh` mismatch. Raw original JUnit XML is retained, rather than just aggregate counts.

## Real native call chain and ownership

1. The existing `prepareLevels` HEAD hook performs durable journal recovery first, and then attaches each exact native generator/level identity before initial chunk loading. A pending failed/quarantined level prevents attach. No-journal startup records the actual early-return decision without creating/opening storage just to emit evidence.
2. Each existing generator invokes its existing version-specific runtime factory; the host does not construct another generator or adapter hierarchy. Duplicate generator identities across levels stop admission. Per-server registration state is detached and removed on stop; new/removed levels synchronize through the same registry.
3. At the original Earth deferred-tick position in the shared platform callback, the host calls the existing lifecycle manager/registry. The bound Earth adapter invokes the unchanged `processDeferredChunkDetailTick(level)`. The direct legacy fallback runs only for an unowned level, preventing double execution. Realtime, downloads, Cube seam managers and other original callbacks keep their original order and native authority.
4. Chunk-unload dispatch invokes the unchanged Earth `discardPreparedChunkState(position)` through the bound adapter, with the original direct fallback for legacy. The real acceptance command explicitly dispatches this exact owned callback without demand-loading an Earth chunk; it is not evidence of a naturally evicted populated Earth chunk.
5. Normal stop checks both an empty registry and all previously registered adapter attachment flags cleared. This releases the Earth level reference. Cube/Sphere do not acquire duplicate manager ownership; their empty adapter tick remains empty by design.

The family acceptance fixture adds real Earth and Sphere dimension definitions only in the disposable candidate world. Their generators are genuinely registry-decoded/attached/ticked, but no Earth/Sphere terrain is generated for this lifecycle fixture. Actual whole-chunk Earth/Sphere production-entrypoint comparisons remain accepted in [the prior production gate](../unified-native-production/README.md). Cube canonical-only decoration/apron derivation and Sphere empty post-stages are unchanged.

## Actual generation, restart and rollback

Both lanes use the same historical real GIS world and byte-identical native-populated ready cache. The baseline is the retained `80dd37ba` checkout with observation-only detail-readiness warmup. Both wait for identical native detail inputs (115 buildings, 79 roads, 8 water features) before generating the same previously absent canonical Cube chunk at `616262,-351597`. No synthetic sampler or region writer is used.

The explicit candidate gate passes first while the release default remains legacy. After that and the retained dual-version regression pass, the bootstrap release default is enabled. A fresh A/B on each version deliberately passes **no** `tellus.worldgen.pipeline` option to the candidate; raw JVM options and native observations prove `UNIFIED`. Its actual BIOMES → TERRAIN → CARVERS → DECORATION observations include the existing shared writer, without failures. The materialized FULL chunk matches its native save and the baseline complete payload.

Each accepted no-flag candidate is then restarted twice **without resetting/copying any chunks**: first no-flag default, then explicit legacy. Complete block/biome/native NBT digests and generator profile remain equal to the original generation, and each native save again matches materialization. Legacy reports zero unified registrations/attachments/ticks. Only `LastUpdate` and `InhabitedTime` are excluded from the full native NBT comparison; no terrain, biome, light, heightmap, structure, entity, tick or marker fields are dropped.

Native full-NBT digests remain `34343012d0c50c24cf819d1a2537e7f4cedf54beac6df6110607d080dd1548b2` on mc1201 and `a3d663c1ac22821593d2f31be10748de229695bc886ec292389e77c25a95a578` on mc1211. Each lane covers 1,040,384 block cells and 16,256 biome cells. Original world/cache and all Java sources are unchanged during every accepted run.

The reusable [restart/rollback runner](../../../tools/run_native_runtime_restart_rollback.py) requires the previously accepted no-flag native generation record, verifies current code/world/cache identity and uses the same candidate command/world. The [generation runner](../../../tools/run_native_generation_parity.py) exposes `--candidate-pipeline default --require-runtime --runtime-family-levels --settle-native-details`. All Gradle execution is serial/offline/one-worker. These correctness runs are not controlled performance benchmarks or client rendering/seam acceptance.

## Defects found by native acceptance

- The first candidate generated/saved/stopped correctly, but strict startup-order evidence was absent for the existing no-journal early return. It failed admission. A truthful ready log was added to that branch, without changing its storage behaviour.
- mc1211 then failed to decode the real Sphere dimension: its existing `SphereFaceChunkGenerator.CODEC` was never registered by the Fabric entrypoint. Registration of that existing MapCodec fixes the production entry. The same narrow existing DFU opaque-generator fallback now includes this registered ID, keeping schema guards and serialized payload unchanged; actual restart of the saved Sphere LevelStem passes.
- The restart controller initially omitted the reused argument parser's required provenance path and stopped before launching a restart. It now explicitly references the accepted A/B record. No native source/world was changed to get past that controller admission.

The real initial logs/reports are retained, not replaced with passing claims.

## Existing gates retained; precise boundary

This closes the requested blockers and reversible native default rollout, retaining:

- [Earth underground/geology/ore, shared realtime snow and surface production fixes](../production-stage-unblock/validation.json).
- [Native four-stage provider/kernel/writer composition and whole-chunk entrypoint parity](../unified-native-production/README.md).
- [Real two-chunk hard termination, durable QUARANTINED journal, failed-startup admission, recovery before prepareLevels, save-queue resumption and second restart](../native-crash-recovery/validation.json).
- [Non-default heightOffset, unchanged legacy sea decoding and two stored-LevelStem reloads without sea drift](../native-sea-offset-restart/README.md).
- [Round68 real nonempty block entity/inventory, block/fluid ticks and duplicate markers across native save/restart](../round68-live-lifecycle/validation.json), retained without expanding/repopulating its fixture.

This does not claim active loaded-chunk disk-batch replacement, uncooperative external writer coordination, general cross-neighbour atomic decoration or power-loss durability. Those are unchanged boundaries, not additional conditions substituted for this objective.

## Rollback

Operational rollback is a clean restart with:

```text
-Dtellus.worldgen.pipeline=legacy
```

No saved generator/schema conversion is needed. Live mode hot-switching is intentionally absent: a generator keeps its constructor-selected mode for its lifetime.

Source rollback parent is `4f2b81ac444c1ba6e008299b9346dec576266271`, tree `c74e0e890979559c3844e77b90c425d1a3429603`. The final recovery checkpoint uses an isolated Git index, verifies restoring every changed path produces exactly that parent tree, and leaves the real branch, HEAD and index untouched. Complete evidence/source hashes are in [validation.json](validation.json).
