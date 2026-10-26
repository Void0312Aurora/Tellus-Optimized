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

The full Gradle `build` completed successfully across all ten configured
Minecraft/loader targets. The XML test reports contain 9,460 test cases, with
zero failures and errors; 160 cases are skipped. Per target:

| Target | Tests | Skipped | Failures/errors |
| --- | ---: | ---: | ---: |
| `mc1201` Fabric | 962 | 12 | 0 |
| `mc1201-forge` | 861 | 58 | 0 |
| `mc1211` Fabric | 942 | 11 | 0 |
| `mc1211-neoforge` | 942 | 11 | 0 |
| `mc12111` Fabric | 942 | 11 | 0 |
| `mc12111-neoforge` | 942 | 11 | 0 |
| `mc262` Fabric | 977 | 11 | 0 |
| `mc262-neoforge` | 942 | 11 | 0 |
| `mc263` Fabric | 975 | 12 | 0 |
| `mc263-neoforge` | 975 | 12 | 0 |

The Forge 1.20.1 JUnit task excludes four bootstrap-dependent shared tests
(`TellusGeodeticElevationTest`, `TellusWorldgenSourcesTest`,
`GeodeticOsmQueryTest` and `OceanCoastFieldConcurrencyTest`) because invoking
vanilla bootstrap without Forge's ModLauncher fails before their assertions.
Those tests run in the Fabric suite; the Forge test report still completes with
no failures. The target also retains the project's pre-existing exclusions.

After the full matrix, a focused rerun of `mc1201:test`,
`mc1201:testParallelChunkgen` and `mc1201:remapJar` passed following a fix to the
runtime smoke fixture's deferred-transfer handoff. The parallel suite covers 7
cases (6 passed, 1 skipped). The shared-source duplication check passed in the
full matrix.

The pinned Python geography and reference-POI suites pass all 13 tests. A
headless installed Fabric server then passed the sphere crossing, sphere save
reopen, and cube-net runtime checks. Sphere crossing preserved the geographic
position to a residual below `1e-9 m`, and collision rollback passed. The
cube-net check exercised all 24 directed edges, all 14 exact cut-edge starts,
and collision rollback against real FULL chunks. These server fixtures use a
synthetic terrain sampler and packet sink without a connected client; they do
not establish terrain-source quality, rendering, or multiplayer behavior.
Evidence and local logs are preserved under `build/upstream-integration/`.

Reproduction command, using a Java 25 Gradle runtime and installed Java
toolchains:

```sh
./gradlew build --no-daemon --max-workers=2 --console=plain
```

## Remaining acceptance scope

- No connected client/rendering or multiplayer acceptance was performed.
- Upstream water, land-cover and landmark changes intentionally alter generated
  terrain; the 0.8.3 hash and timing records are historical baselines, not results
  for this integration. The old saves and deployment jars were not replaced.
- The upstream DH override still recognizes `EarthChunkGenerator`; this merge
  does not implement DH generation or seam rendering for `CubeNetChunkGenerator`.

The conflicts are resolvable with explicit compatibility choices. The local
all-target build, tests and headless runtime fixtures pass; connected-client,
renderer and data-quality acceptance remain separate work before a release.
