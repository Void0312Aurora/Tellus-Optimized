# Round 47 — Complete native fill capture and semantic replay

## Scope and result

This round drives the actual shared `EarthChunkGenerator.fillFromNoise(Blender, RandomState, StructureManager, ChunkAccess)` synchronously, rather than calling a private surface helper. It adds a test-only integration fixture; **no production source, Codec, save format, default hook or registration is changed relative to Round 46**.

Each version runs three new tests. The complete prior selection plus these tests passes serially: **mc1201 265/265; mc1211 258/258**, zero failures/errors/skips. The suite inventory, stdout, source hashes and new XMLs are recorded under [round47-full-native-fill](../validation/round47-full-native-fill/test-evidence.json).

## Controlled inputs and evidence boundary

- Real `ProtoChunk`, built-in `OVERWORLD` `RandomState`, `FixedBiomeSource` plains and the exact common/pre-26 Minecraft method signatures are used. The 1.20.1 and 1.21.1 mapped bytecode confirms the test `StructureManager` constructor only stores its fields. The test supplies deterministic `WorldOptions(0L, false, false)` and overrides the exact `startsForStructure(ChunkPos, Predicate<Structure>)` overload with empty starts. This is a bounded empty-structure seam, not a substitute server manager.
- A synthetic on-disk native preload package provides flat terrain height 80, cover 30 and known land over chunk bounds [-32,32] on both axes. The package carries the actual encoded settings fingerprint and uses the existing native writer, manifest, index and package reader. Single-thread index/load executor barriers have bounded `Future.get` waits; readiness is asserted before fill, not assumed from writing a file.
- Water stays enabled. A temporary, known-empty native vector-water cache supplies explicit complete empty inputs; it is **not** missing coverage interpreted as dry land. Original source fields are saved and restored. Derived resolver caches are cleared before/after this input substitution. There is no production GIS/source bypass mode.
- Generator construction and each synchronous fill execute inside `ManagedTerrainNetworkPolicy.cacheOnly()` on the calling thread. The policy and unavailable-elevation counter are thread-local. The test asserts no new unavailable elevation reads on that thread and no currently pending refinement; it does not infer an entire asynchronous scheduling history from `pending=false`.
- Cleanup deletes only the unique package beneath the verified test TempDir, drains registry queues, restores the water source fields/derived cache and finally restores system properties/classloader. This does not isolate all process-wide static source cache paths; selection execution uses fresh Gradle test JVMs. The whole prior selection was rerun together with this fixture to check observed suite interaction.

These inputs establish **complete native fill control-flow acceptance under controlled synthetic inputs**, not real GIS coverage, network availability, a running Minecraft server, persistent save/reload, or a production terrain-convergence/performance verdict.

## Assertions

1. Capture-off and capture-on fills of the same chunk coordinates/profile are compared across **all 135,168 block states** (16 × 16 × 528), including sky and later native writes. Zero differing blocks per version. The empty-structure override must actually be queried.
2. The actual complete-fill snapshot has terrain height 80, dry water, a real resolved dry context, exact semantic before/after availability and an AIR pre-surface top from the real thin-shell body. The captured before/context feed `semanticSurfaceColumnKernel`, then the normal `TellusColumnMaterializer`; **all 145 captured cells** match the native post-surface snapshot, zero differences. Snapshot strata equal the resulting decision strata.
3. Completion opens/closes a successor lease reentrantly, proving slot release before observer delivery. Another thread acquires and reads every native section palette during the observer, with a five-second bound, proving writer ownership was released before delivery. Observer failures are awaited through their returned stage.
4. An early null-random failure passes through the actual fill and fails the capture with the exact same original throwable; a new lease can reserve the slot.
5. A late injected structure-query failure occurs after body/surface writes. It propagates the same original throwable, fails the capture rather than publishing a premature success, leaves sections acquirable and permits replacement registration.

The semantic snapshot stage remains `POST_SURFACE_PRE_SNOW_AND_SHELL_SKIN`. The all-chunk off/on comparison checks that observing the stage does not change final native output; the 145-cell semantic replay checks only the recorded stage, not the final world after every later phase.

## Initial failed fixture and correction

The first isolated mc1201 run produced three failures: the elevation/cover package was ready, but native shoreline/hydrology independently required vector-water coverage. Strict source handling raised `OceanCoverageUnavailableException` wrapped by the hydrology cache before the intended early/late test points. The failed XML is archived under [fixture-attempts](../validation/round47-full-native-fill/fixture-attempts/initial-missing-water-coverage.xml).

The correction supplies explicit known-empty synthetic vector inputs and restores their original fields at cleanup. Water remains enabled (`enable_water=false` migrates to true in current settings), and no native exception handling or generation branch was changed to make the fixture pass. The corrected isolated run, the prior-selection compatibility run and the final dual-version run all completed successfully.

## Regression inventory

The final commands use `-PtellusTargets=<version> :<version>:test --offline --no-parallel --max-workers=1 --console=plain`, adding every Round 46 exact suite name plus `EarthNativeFillCaptureIntegrationTest`; mc1201 completes before mc1211 begins.

Fresh per-version observations retain:

- native capture/surface bridge: 4 columns / 96 cells / 0 differences;
- frozen native surface plans: 2,622 cases / 0 differences; semantic surface: 2,190 cases; 108 non-default-state native cases;
- native and semantic base columns: 1,981 each / 0 differences;
- Earth surface production: 204,000 / 0 plus 3,160 native-driver cases / 0;
- Cube cover: 81,900 / 0; spherical pipeline: 1,176 / 0; materializer: 128 / 0;
- original mc1201 probes: 13,824 columns / 0 differing blocks, canonical 256 / 0, seven biome cases / the unchanged bare `minecraft:desert` versus `minecraft:stony_peaks` difference.

The evidence generator checks exact test-name/count inventory, zero failure/error/skip, all matrix markers, equality of every prior source hash and the unchanged seven-case biome details before writing the report. This round does not rerun or claim a new standalone external-manifest CLI comparator campaign.

## Review, remaining gates and rollback

Read-only independent review confirmed the cached Minecraft API signatures, registry FIFO barriers, settings semantics and path-resolution behavior. Its thread-local counter, `pending=false` history and singleton-path caveats are retained above. The test cleanup was hardened with nested `finally` restoration.

The integration matrix is intentionally flat/dry/default-fast-writer. Capture-specific badlands, strata, top-only, known no-op and deliberate lease interleavings need additional cases; later cave/hollow/snow/decorate decisions, richer Cube/Sphere production context, real-world source coverage, persistent storage transactions and default lifecycle integration remain engineering work. None is marked complete by this result.

Rollback is test/docs-only relative to `recovery/tellus-round46-native-column-capture` (`34c0be5479360f4ef60d7a421dc4f04d6176222b`). Remove the new integration test and Round 47 evidence/documentation and restore the workstream status paragraph. No runtime flags, production bytecode, Codec or disk-save migration must be reverted. A separate alternate-index recovery commit records the exact Round 47 delta without staging the user's real index or moving the current branch.
