# Rounds 31–32: spherical production decisions over complete snapshots

## Outcome

The explicit spherical decision path now runs the native radial height, surface and geological rules from a complete immutable sample snapshot. It is exercised through the existing provider → DecisionEngine → CanonicalPipeline → real ColumnWriter path in both Minecraft versions.

The native mc1201 and mc1211 SphereTerrainMaterializer production methods now delegate height, default surface and geological column selection to the same shared rule entrypoints. Their engine-local fill/state/heightmap boundary and generator-specific surface-resolver callbacks remain in place.

This advances workstream 3 beyond material-name adapters. It does not migrate Earth generation, spherical biome classification, Cube-Net's Earth-equivalent specialized surface callback, feature placements or persistent storage into the generic kernel.

## Complete sample boundary

`TellusSphericalSurfaceSnapshot` preserves the original SurfaceData object, including slope neighborhood, water kind and geological MapUnit/lithology. It optionally carries the already resolved production CubeNetSurfacePolicy.Decision.

`TellusSample` has an optional spherical snapshot; its existing six- and seven-argument constructors remain valid. A present snapshot must accompany AVAILABLE spherical input with matching elevation, raw/visual cover and water scalars. Contradictory payloads are rejected rather than silently combined.

The existing spherical provider factories preserve their sampling behavior and attach raw metadata. New explicit `forCubeNetProduction` / `forSphereFaceProduction` factories additionally resolve the existing surface policy at the provider boundary using the request's GeoPoint, scale and seed. This retains the policy's existing nonblocking sand-source probe where applicable. Nonblocking requests return QUEUED before either sampling or surface-policy resolution.

The kernel never invokes that source-aware surface resolver. It requires the prepared snapshot and consumes the resolved immutable decision. A raw-only, missing or incomplete snapshot produces UNKNOWN; it does not fabricate stone/default surface output or perform a second sample.

## Actual rule integration

`TellusSphericalColumnRules` retains the production calculations:

- Exact radial scaling and rounding: independent terrestrial/oceanic scale, ceil for nonnegative scaled elevation, floor for negative elevation, sea level added afterward. There is no Mercator correction or clamping.
- Geological MapUnit takes priority over lithology. Without a map unit, cover 60 retains the native arid-rock selection. The same GeoPoint/ECEF field, world scale, sea level and seed are forwarded.
- The existing native surface policy remains authoritative. Caller-supplied Cube-Net specialized resolvers still override the default in the native materializer.
- All 22 native rock indices map to explicit semantic materials. Both version mappers now include granite, calcite, tuff, basalt variants, blackstone, sandstone variants and colored terracotta.

`TellusSphericalDecisionKernel` validates sample/coordinate/geometry/seed identity, requires canonical Cube-Net or Sphere ownership, reserves minY for bedrock, checks native vertical bounds and the materializer's allocation budget, then emits geological run-length strata, surface and water. Bedrock is explicit in the decision. BiomeDecision remains UNKNOWN because terrain-rule parity does not establish biome classification.

`TellusColumnDecisionEngine.withSphericalProductionRules(...)` enables this path explicitly. Existing default and Earth classifier constructors keep their behavior. The existing version pipeline constructor accepts this engine directly; no extra wrapper factory or default registration is required.

## Earth depth convention correction

EarthSurfacePalette retains native total thickness including the top. SurfaceDecision uses filler count excluding the top. The adapter now converts semantic depth + 1 to Earth total depth using Math.addExact, and Earth depth - 1 back to semantic filler count. Top-only, zero-filler, overflow, flags, underwater top and full round-trip cases are tested.

## Serial verification

All final selections passed:

| Target | Tests | Result |
|---|---:|---|
| mc1201 | 110 | zero failures/errors/skips; BUILD SUCCESSFUL |
| mc1211 | 102 | zero failures/errors/skips; BUILD SUCCESSFUL |

The new end-to-end comparison spans one Sphere frame and all six Cube-Net orientations, seven cover classes, four geological inputs (including a two-rock MapUnit overriding lithology), two elevation signs and three dry/water modes. For each combination it writes a real ProtoChunk through the semantic pipeline, then compares every Y from -64 through 319 against the native materializer ColumnPlan.stateAt:

```text
SPHERICAL_KERNEL_PARITY columns=1176 mismatchBlocks=0
```

Both versions passed that comparison and a separate exhaustive 22-index semantic-to-native strata material check. Fixed expected-value tests independently cover rounding on either side of zero, distinct positive/negative scales, overflow, native vertical rejection instead of clamping, preserved neighborhood/geology references, deterministic repeat decisions without resampling, raw-only UNKNOWN, nonblocking QUEUED and seed mismatch rejection.

The native and semantic paths share the extracted production helpers; comparison alone would not independently prove the copied formula. The literal height assertions, exhaustive native StrataMaterials mapping check, source diff review against Round 30, existing SphereTerrainMaterializer tests and original generator baselines supplement that comparison.

The same final mc1201 invocation freshly reran:

```text
CONSISTENCY_PROBE faces=6 locations=9 columns=13824 mismatchColumns=0 mismatchBlocks=0
BIOME_PROBE cases=7 mismatches=1
BIOME_PROBE case=bare earth=minecraft:desert cube=minecraft:stony_peaks equal=false
```

Round 30's 128-column materializer comparison also continues to pass on both versions. These deterministic fixtures do not claim real-world GIS coverage, live server lifecycle acceptance or a complete external-manifest CLI replay. The earlier Overture-404 climate test was outside this selection and is not claimed fixed.

Machine-readable suite selection, observations and implementation hashes are saved in `docs/validation/round32-spherical-kernel/test-evidence.json`.

## Review, compatibility and rollback

The source diff against the verified Round 30 checkpoint was reviewed: native numerical expressions and MapUnit/lithology precedence were moved without changing branch order, arguments or version-specific BlockState writes. Kernel/source access was separated before verification, and Earth depth conversion received a disjoint implementation review plus serial tests.

No Codec, serialized world schema, generator construction, save/reload hook or lifecycle registration changed. Semantic enum additions are internal and are not a replacement for a durable storage encoding.

Rollback baseline is `recovery/tellus-round30-column-expansion`, commit `30526fde5b6278309b986a0e7660c50acf505fd1`. Revert both native helper delegations with the new rule classes, snapshot/engine changes and their tests together; ceasing explicit production-provider/kernel calls leaves the legacy producer entrypoints active.

Remaining engineering work is recorded in `deferred-workstreams-status.md`: Earth production rules/callsites, Cube-Net specialized surface and biome paths, FeaturePlan pipeline placement integration, real persistent transactions, and default lifecycle rollout after those gates.
