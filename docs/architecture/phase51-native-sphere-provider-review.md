# Round 51 — Generator-owned Sphere-Face exact provider and pipeline replay

## Concrete integration gap

The earlier spherical provider is an explicit independent sampler: it reads the request's exact-map coordinate at its quantized SamplingResolution metres. Native Sphere-Face generation instead samples each native terrain-grid column at the chart's truncated-map source point and the actual model worldScale. At scale1000 the existing exact request bucket is1024. Passing only native SurfaceData into a kernel that still uses request.geo for geological strata would also lose the actual source point.

This round adds a new opt-in native-owned path without changing those established independent-provider semantics:

- Both SphereFaceChunkGenerator versions expose identical lazy `semanticSampleProvider()` factories, bound to the actual generator's face, scale, current materializer seed, chart, geographic ownership and terrain-admission policy.
- The factory obtains the exact immutable `terrainGrid(owner.chunk()).column(localX,localZ)` object used by native generation. It does not construct a second sampler or resample a selected column at a rounded resolution.
- The shared `forSphereFaceNativeColumns` route returns QUEUED before invoking its column callback for NON_BLOCKING requests. PREVIEW/LOD or a different exact bucket return UNKNOWN without native source reads. Valid EXACT metadata still uses the existing quantized native-scale bucket. Thus exact(1000) and exact(1024) are intentionally indistinguishable metadata; both have bucket1024. The actual native sampler still receives1000. This is not a new raw-resolution distinction or a cache-key migration.
- A cold accepted request may load all256 native grid points. Subsequent exact requests and native fill use that same native cache; no new asynchronous scheduling is introduced. CacheHit provenance remains conservatively false with an explicit note that residency was not measured; this is not a cache-hit counter.

No default producer, lifecycle hook, native sampler, cache key, Codec or world-save encoding is changed.

## Retained native column context

TellusSphericalSurfaceSnapshot gains an optional immutable native Column, preserving its address, actual source GeoPoint and SurfaceData. Existing two-argument construction and raw/resolved factories remain source-compatible and leave this optional empty. The new resolvedNativeColumn factory requires matching data and a finite/ranged source point.

The common spherical kernel rejects a native column whose address differs from the canonical owner. When it is present, geology/strata use its original SphereColumnContext; otherwise the prior request-coordinate path remains unchanged. Surface policy is resolved at the same retained native source point. The source record is an in-process semantic contract, not a newly persisted Minecraft record. Retaining the old constructor is source compatibility, not a promise of binary record-component ABI compatibility with previously compiled consumers.

## Actual native provider -> versioned pipeline -> real chunk

SphereFaceNativeProviderPipelineTest composes each actual version's existing CanonicalPipeline with the generator-owned provider, its native fixed-biome kernel, the existing semantic mapper/materializer and empty FeaturePlanner. No fake provider is inserted between the generator and this pipeline. The native generator PointSampler itself is deterministic test input, not real GIS.

The matrix includes six faces × two runtime seeds × three dry/inland/ocean-geology scenarios, four selected columns in each of36 native chunks, and both positive/negative centered engine frames. It records native sampled points and the actual1000-metre resolution, while checking the request bucket remains1024. All144 selected native source points differ from their exact-map request points; the snapshot retains the actual native object by identity. A first provider sample loads256 native grid points; all later explicit pipeline calls and actual native fill reuse that grid. Total native PointSampler calls are9,216, not one independent resample per pipeline request. Existing surface-policy auxiliary probes are outside that counter. Each accepted sample still resolves the existing native surface policy at the retained source point; it does not capture a frozen policy decision from a previous fill or guarantee unchanged external data between sample and fill.

The real canonical writer's144 complete vertical columns match55,296 actual native block states with zero differences per version. The pipeline also carries the real desert fixed-biome decision, but continues to write block states, not biome-container metadata. Round50's separate actual native biome-palette comparison remains retained.

Three more integration cases cover:

1. Factories remain source-cold; disabled writes, QUEUED requests, unsupported resolution kinds/buckets, wrong face/scale/seed and excluded caps avoid native source calls and leave the chunk untouched.
2. A native source exception after18 attempted grid points propagates through the native cache/provider/pipeline and leaves the target all-air. A subsequent ready-source retry loads a complete256-point grid, writes successfully and is reused (274 total calls). The source error is not replaced with invented terrain.
3. The established independently injected provider still samples exactly request.geo at its declared bucket1024 and creates no native-column context; this characterizes rather than changes its old semantics.

The companion seven-test pure suite checks optional native-column/legacy snapshot contracts, terminal routing, resolver failure propagation, ownership and source-context rules. Its strata fixture uses a fine10-metre scale and searches a bounded fixed list for a source point that actually changes the expected layers, then compares the kernel result with both the retained-source and legacy-request contexts. The first1000-metre pure fixture failed because the native rule intentionally suppresses layered formations at that coarse scale; its failed XML is archived. Only the fixture scale changed, not the production policy or either equality/inequality assertion. This is a controlled context-selection contract, not a GIS oracle. The final selected totals are mc1201 **301/301** and mc1211 **294/294**, zero failures/errors/skips; exact suite inventories and case names are recorded in the validation evidence.

## Verification, source guards and review

Run the exact previous Round50 selections plus both new suites strictly serially, first mc1201 then mc1211. Evidence must retain every previous suite/count, stdout marker and original seven-case detail, in addition to the new144-column marker, source-point difference count and source hashes. [Machine-readable evidence](../validation/round51-native-sphere-provider/test-evidence.json) records final results and the initial test-only enum correction.

The first focused build exposed a new test's reference to nonexistent WriteStatus.INVALID; the established enum is NO_WRITE_INVALID. The first combined compilation then exposed a missing global.TellusCoordinateContextFactory import in the new pure suite. Only those test references/imports were corrected; assertions and production status contracts were not weakened. These are compilation fixture issues before JUnit execution, not production outcome/status changes.

Strict source comparison against the Round50 recovery parent proves that removing only the new provider factory leaves both native generators and their existing fixed-biome kernels identical, and that the old shared provider routes/mapping remain identical after removing only the new native-column factory. The old snapshot raw/resolved factory tail remains unchanged. The common kernel differs only in the explicit optional native-context selection. Independent review checks these deltas and the test bounds before checkpointing.

## Remaining gates and rollback

- The native cache/source lifetime and volatile seed remain lifecycle-owned. There is no new atomic seed/profile binding across source fetch and decision, no persistent snapshot replay identity and no proof of standalone server-world seed initialization.
- The Request contains no generation vertical bounds. Those are validated by the existing native kernel after sampling; this round does not claim bad vertical profiles are source-I/O-silent. The provider's early guards bind only the profile data actually present in the request.
- Opaque geometry/provenance revisions and planet identity are not fully bound to a persistent-world profile. The owned path preserves an actual native in-process column; arbitrary low-level injected column resolvers remain caller-owned inputs.
- Native exact sampling is bounded by existing grid loading/caching, not a new strict time/network budget. Nonblocking remains QUEUED without scheduling, including when a grid might already be warm.
- Cube-Net still needs its distinct richer terrain/source context and later production phases remain. Vertical biome writes, default pipeline/lifecycle activation, real GIS/server acceptance and real persistent save/reload transactions remain incomplete. All six workstreams remain one active goal.

Rollback parent: `recovery/tellus-round50-sphere-fixed-biome` (`e358bdd08dd3f60b24b75be067e8a2cf0dc37478`). Restore the two generator files and three shared semantic/provider files from that tree, remove the two new test suites/docs/evidence and restore the status paragraph. The alternate-index checkpoint preserves the real branch, HEAD and index. No world-save conversion is introduced.
