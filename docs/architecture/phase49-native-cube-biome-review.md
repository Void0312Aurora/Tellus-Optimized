# Round 49 — Explicit native Cube-Net column-top biome bridge

## Production change

Two shared production files change. `TellusSphericalDecisionKernel` gains an explicit `BiomeResolver` constructor; the original constructor still returns UNKNOWN biome identity and retains its prior diagnostics/terrain behavior. The callback receives the original generation, coordinate, sample and computed terrain Y, once and only after identity/completeness/canonical-owner/surface-policy/vertical-budget checks and strata construction. Null dependencies/results and classifier exceptions fail fast; incomplete/queued inputs never trigger native queries.

`CubeNetBiomeSource.semanticColumnKernel()` creates that opt-in kernel with the source's actual water and terrestrial/oceanic height scales. Its callback:

1. Requires canonical Cube-Net ownership and matching source world scale.
2. Rejects the structure-search ThreadLocal mode, preventing search-only biome semantics from leaking into a column decision.
3. Floors the engine planar X/Z with finite/int-range validation; verifies the source resolves those coordinates to the same canonical block and admits its terrain chunk.
4. Calls the unchanged real `getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(terrainY), QuartPos.fromBlock(z), Climate.empty())`.
5. Converts the exact holder registry key into `BiomeDecision`; an unkeyed holder remains unknown rather than receiving invented plains.

The source-scale/planar/policy guards are **not** a full generator profile or exact generator-identity binding. The source alone does not establish the caller's effective sea level, full vertical limits, seed, geometry revision, generator settings binding or apron configuration. Runtime integration must still own and validate those boundaries.

No default callsite, source classifier extraction, Codec setting, generator lifecycle, chunk biome-container write or save encoding changes. Static source guards compare the entire original Cube source prefix (settings/Codec/constructors) and the original `getNoiseBiome` plus following helpers against the Round 48 recovery tree; both are byte-for-byte unchanged apart from the inserted explicit bridge/helper block.

## Why native resampling is intentional

The existing Cube biome source samples `TellusWorldgenSources.sphericalSampler(model)`. The generator's richer terrain sampler uses `cubeNetSurfaceSampler(model, seaLevel)` with hydrology/bathymetry/neighbourhood facts. They are not equivalent inputs. Reusing the latter SurfaceData inside an extracted biome classifier would silently change native branch behavior.

The new bridge instead retains engine quart anchoring, the source's own read, climate/OISST, mapped-water, visual-cover, random-variant and cave-aware native logic. A biome describes the real native query at **the proposed terrain-top quart Y**, not all vertical cave biomes and not necessarily a surface-family id if the newly observed native terrain differs from the provider snapshot.

This path is therefore fresh native semantics, **not atomic deterministic replay of one terrain snapshot**. Native caches, climate or vector data can change between provider sampling and native classification or between repeated decisions. A returned keyed native fallback is an observed registry identity, not evidence that GIS/climate/water coverage is complete. No default rollout gate is satisfied by having a known id alone.

## Verification

Final strictly serial selection: **mc1201 284/284; mc1211 277/277**, zero failures/errors/skips. Every Round 48 exact suite/count and stdout observation is retained. [Evidence](../validation/round49-native-cube-biome/test-evidence.json) archives the new XMLs, test names, counters, source hashes and static guards.

### Actual native source bridge: five tests

`CubeNetNativeBiomeKernelBridgeTest` constructs real native CubeNetBiomeSource instances with deterministic input feeds, not fake biome-classifier callbacks. Its matrix spans:

- eleven forest/Cfa/Cfb, grass, mangrove, direct inland water, mapped inland water, warm/deep/cold/ice ocean and snow inputs;
- two settings profiles, including random biomes, water disabled and independent height scales;
- six canonical faces and two non-quart continuous offsets, including negative engine planar coordinates.

For **264 columns per version**, the semantic id matches a direct real native query at the expected engine quart X/Y/Z. Captured source-read points match the direct native reference and differ from the provider's continuous point. This prevents silently substituting Earth/noise/face-local or provider-point coordinates. The terrain provider deliberately returns dry grass while the native source receives the separate richer cases, proving that the source's own inputs remain authoritative.

All prior terrain/surface/water/strata fields and **34,320 semantic materializer cells** match the old unknown-biome kernel exactly. These are semantic write-list comparisons, not 34,320 new chunk biome-container writes. The existing 1,176 native materialization columns remain in the full regression selection.

Other tests verify observable positive/negative height scaling and disabled terrain water, queued and rejected owner/scale/mapping/int-bound/structure-mode paths with zero native reads, unkeyed native river identity staying unknown and an unchanged full source Codec encode/parse/re-encode roundtrip.

### Pure resolver contracts: six tests

The second suite verifies exact callback arguments/once-only invocation and engine composition; old constructor and explicit unknown behavior; queued/unknown/partial/missing/raw/apron terminal short-circuits; vertical limits/overflow/column budget; coordinate/geometry/seed identity; null contracts and exact exception propagation. These are caller-fixture contracts, not native source/dataset acceptance.

### Initial fixture failures and correction

The first focused run had two failed tests out of eleven, while the 264-column native matrix already passed. One pure fixture created QUEUED with a blocking request, correctly rejected by the existing sample consistency validator. Another attempted to construct NaN planar coordinates before its rejection assertion, correctly rejected by the existing PlanarPoint constructor. The corrected fixtures use a non-blocking queued request and separate construction rejection from finite overflow/wrong-mapping bridge checks. No production validation was weakened. Both initial XMLs are retained under [fixture attempts](../validation/round49-native-cube-biome/fixture-attempts/initial-native-guard-fixture.xml).

A later full mc1211 run exposed a separate serialization fixture error before the factory call: plain `RegistryOps.create(JsonOps, lookup)` discarded the RegistrySetBuilder provider's UniversalOwner serialization metadata. Cached 1.21 bytecode confirms its polymorphic `HolderLookup.Provider.createSerializationContext` override preserves that owner, while 1.20 has no such API. The shared test now invokes that provider method when present, falling back to the original RegistryOps factory only when the method is absent. No registry copying, holder relabeling or production Codec edit is used; full encode/parse/re-encode remains required. The original and detailed owner-error XMLs are archived alongside the earlier fixture failures.

Independent read-only review found no high-confidence production defect in this bounded delta and highlighted the narrow-profile, independent-resampling and single-Y limitations recorded above.

## Retained gates, usage and rollback

An explicit caller can compose a production terrain provider with `TellusColumnDecisionEngine.withSphericalProductionRules(nativeBiomeSource.semanticColumnKernel())`. It must still provide validated coordinates/settings and decide when blocking native source work is appropriate. Non-blocking requests remain QUEUED before callbacks. Default pipeline construction never selects this path.

All earlier native capture, full-fill, surface/base/Cube/spherical/materializer matrices and original mc1201 13,824-column/0, canonical256/0 and seven-case single known bare biome difference are freshly checked. This bridge does not repair or redefine that historical difference.

Remaining implementation includes the actual Sphere-Face **fixed** biome source bridge (not an Earth climate classifier), biome-container/vertical palette concerns, richer/later cave/hollow/snow/decorate producers, real source/world acceptance and real persistent save/reload/default lifecycle. The six-workstream goal remains active; default rollout stays opt-in.

Rollback parent is `recovery/tellus-round48-capture-surface-matrix` (`b7259c5c044784550afb2fc6fcbba7ab3bf5bb63`). Restore the two production files from that recovery tree and remove the new tests/docs/evidence, restoring the status paragraph. An alternate-index recovery commit verifies the exact change set and preserves the real branch, HEAD and index. No world/save migration is needed.
