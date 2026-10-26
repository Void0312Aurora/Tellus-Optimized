# Upstream 0.8.4 integration assessment

Date: 2026-10-26. This is a local integration branch, not a published release.

## Inputs and isolation

- Fork main: `1499c6d3e0c16d20f1f66c82c978b34297dcb633`.
- Upstream main: `39a04291bf7ebf2b731d2b2589111a8577a9c9b2`.
- Shared base: `8c19ed8e8a06f7c0d34bf0dbb661cd22ed5b8339`.
- Branch: `integration/upstream-0.8.4`.
- Worktree: `/home/void0312/Workshop/.worktrees/tellus-upstream-084`.

The original checkout remains on fork main. No remote branch is updated.

## Conflict decisions

Git reported 12 conflicting paths; the two generator paths represent a shared
implementation migration, rather than two independent subsystems.

| Paths | Conflict | Resolution |
| --- | --- | --- |
| `THIRD_PARTY_NOTICES.md` | Both sides appended different third-party notices. | Retain both CCAM and landmark model notices. The existing release-license issue is not resolved by combining the notices. |
| `mc1201/src/main/resources/tellus.mixins.json` | New-world height initialization and spherical movement hooks occupy the same registration area. | Register both sets of hooks. |
| `src/main/java/com/yucareux/tellus/TellusCommon.java` | Benchmark state overlaps new asynchronous geographic teleport state and imports. | Keep both. Use `MinecraftVersionCompat.keyId` for the benchmark dimension name so it also compiles on 26.2. |
| `src/main/java/com/yucareux/tellus/world/data/cover/TellusLandCoverSource.java`, `WorldCoverCogSource.java` | Upstream adds warped visual sampling; the fork uses a chart-independent spherical threshold. | Retain the warped base class and the spherical threshold; planar calls retain upstream block-coordinate thresholds. |
| `src/main/java/com/yucareux/tellus/world/data/osm/TellusOsmBuildingSource.java`, `TellusOsmRoadSource.java` | Shared geographic helpers overlap upstream replacement-landmark filtering. | Preserve date-line-aware geographic queries. Apply replacement filtering only to planar queries, because the spherical path does not materialize the replacement landmarks. |
| `src/main/java/com/yucareux/tellus/world/data/osm/TellusOsmWaterSource.java` | Extracted geographic query helper overlaps new LOD tile-width and parallel-download parameters. | Pass the tile-width policy through the shared helper, preserve geographic coverage accounting, and retain parallel tile downloading. |
| `shared/pre-26/src/main/java/com/yucareux/tellus/worldgen/EarthChunkGenerator.java`, `src/main/java/com/yucareux/tellus/worldgen/EarthChunkGenerator.java` | Upstream removes the older implementation and consolidates generation behind version bridges. | Remove the obsolete source copy. Keep the fork's global map model, projection entry points, opt-in parallel section fills and concurrent writer accounting in the common implementation, alongside upstream landmarks and the version bridge. |
| `src/main/java/com/yucareux/tellus/worldgen/OceanCoastField.java` | Fork build coalescing and generation-aware invalidation overlap upstream coarse fields, recent-tile cache and bounded scratch. | Retain the upstream sampling model and memory bounds; preserve generation-keyed build coalescing and adapt warmup to coarse coordinates. Serialize cache publication with clearing so old builds cannot refill cleared caches. |
| `src/main/java/com/yucareux/tellus/worldgen/WaterSurfaceResolver.java` | Fork batch/parallel sampling overlaps a new inland planner and bounded shared surface-input caches. | Use upstream planner, scratch pools and input tiles. Retain the separate waterfall query halo, optional legacy row scans and checksum diagnostic. The old batch-grid switch is superseded rather than applied over already prepared upstream surfaces. |

## Focused regression evidence

Six new tests cover the combinations introduced by the conflict resolutions:

- One coast tile is built for concurrent requests; the in-flight entry is released.
- Clearing while a build is pending prevents old data from refilling either cache.
- A failed coast build can be retried.
- Coarse warmup handles negative coordinates and populates the tiles sampled later.
- Geographic building queries retain source buildings while planar queries filter replacement landmarks.
- Geographic road queries retain source bridge geometry while planar queries filter replacement landmarks.

The pre-existing geographic tests still cover both date-line windows and explicit
failure for unsupported polar coverage. These use synthetic cached tiles and do
not establish new real-world source coverage.

## Verification

Fabric 1.20.1 main, client and test sources compile. Its full suite reports
955 tests: 944 passed, 11 skipped, no failures or errors. The opt-in parallel
chunk-generation suite reports 7 tests: 6 passed, 1 skipped, no failures or
errors. The installable jar and the shared-source duplication check pass.

Fabric 26.2 main, client and test sources also compile. The first compile exposed
the benchmark's direct use of the removed `ResourceKey.location` method; using
the existing version adapter fixes it. Its full suite reports 977 tests:
966 passed, 11 skipped, no failures or errors. Its installable jar also builds.

The final combined run completes successfully in 1 minute 16 seconds, with both
full test suites, the parallel-generation suite, both installable artifacts and
the shared-source duplication check. The six new regression tests pass on both
Fabric versions. Local logs are preserved under `build/upstream-integration/`.

Reproduction command, using a Java 25 Gradle runtime and installed Java toolchains:

```sh
./gradlew -PtellusTargets=mc1201,mc262 \
  :mc1201:test :mc1201:testParallelChunkgen :mc1201:remapJar \
  :mc262:test :mc262:assemble checkVersionSourceDuplication \
  --no-daemon --console=plain
```

## Remaining acceptance scope

- Other Minecraft versions and Forge/NeoForge have not been rerun locally.
- No real client/server traversal, edited-save reopen, source-cache performance
  comparison or multiplayer acceptance was performed for this integration.
- Upstream water, land-cover and landmark changes intentionally alter generated
  terrain; the 0.8.3 hash and timing records are historical baselines, not results
  for this integration. The old saves and deployment jars were not replaced.
- The upstream DH override still recognizes `EarthChunkGenerator`; this merge
  does not implement DH generation or seam rendering for `CubeNetChunkGenerator`.

The conflicts are resolvable with explicit compatibility choices. Passing the
local build/tests is a source-level integration result; gameplay and renderer
acceptance remain separate work before promoting the branch.
