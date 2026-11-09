# Round 48 — Native capture surface matrix and terminal interleavings

## Result and scope

This round adds two test suites, with **no production-source changes** relative to Round 47. The final serial selection contains every prior suite plus eight new tests: mc1201 **273/273**, mc1211 **266/266**, zero failures/errors/skips. Exact XML inventory, new test names, prior stdout observations and source hashes are checked before recording [test evidence](../validation/round48-capture-surface-matrix/test-evidence.json).

The scope is native capture/replay fidelity at resolved-source boundaries and deterministic lease ordering. It is not real GIS/source convergence, geology correctness, a running-world test, persistent save/reload or default rollout acceptance.

## Actual native surface entrypoints: 48 columns / 1,200 cells

`EarthNativeCaptureSurfaceMatrixTest` invokes all four real capture-aware entrypoints: thin/full × cursor/section writer. At each of (0,0), (31,-17) and (-4097,8193), it tests dry plains, dry desert, tagged badlands with relief 16 and STONY_PEAKS strata: 3 × 4 × 2 × 2 = **48 columns**.

For every case, a collector-null native invocation is compared with collector-on native output. The actual captured body and actual resolved palette/strata/top/flags then feed the existing semantic kernel and materializer; all **25 cells per column** match the captured native post-surface stage. Both versions assert **1,200 cells / zero differences**, 12 real badlands contexts and 12 real strata contexts. Context thin/full mode and biome identity are asserted. The before body contains explicit bedrock and internal AIR; thin fixtures start with an AIR top.

This uses caller-resolved dry flags and cache-only auxiliary queries, not real hydrology. The sample supplied to the resolved kernel is deliberately synthetic AVAILABLE metadata; desert/badlands/strata native cover inputs are 60 while sample cover is 30. The resolved-column kernel consumes captured source facts, not an independently reconstructed sample/GIS terrain. This is a fidelity check, not sample-to-native resolution acceptance.

The native preparation and semantic path share the extracted surface rules. Therefore they are not independent correctness or historical oracles. The prior frozen-loop 2,622-case native matrix, 2,190 semantic cases and original parity probes are rerun alongside these tests rather than replaced by them.

## Explicit null-palette seam: 12 columns / 300 cells

Current native palette selectors are not asserted to return null. A separate, explicitly labeled test invokes the real native record/prepare/apply resolved-source seam with a null palette at the same three coordinates, thin/full modes and both backends.

- Thin mode selects TOP_ONLY, changing exactly one AIR top to the actual low-altitude DEEPSLATE fallback: six cases.
- Full mode records a present known-no-op context, writes no cells and keeps identical before/after states: six cases.
- All 12 complete captured columns replay through the semantic kernel/materializer: **300 cells / zero differences** per version.
- A distinct test omits the source context entirely. Before/after remain equal and the semantic body exists, but the kernel result stays UNKNOWN. Missing source facts are not upgraded into the known no-op case.

Only low-altitude null-palette TOP_ONLY is exercised here; this is not selector-reachability or high-altitude fallback coverage.

## Five deterministic terminal interleavings

`EarthNativeCaptureInterleavingTest` uses bounded latches/Futures, real small ProtoChunks, no sleeps/random stress and no private-field mutation. Gates release in finally blocks; worker pools shut down with bounded termination. Cross-thread callback assertions are observed through dependent stages.

1. Claim wins, then cancellation precedes delayed before/after/complete. Native writes still affect the old target; a distinct same-position successor chunk and its active slot remain intact.
2. Complete wins and its inline success observer blocks. Parallel close/failed calls and old claim probes return; a successor claims before the old observer is released. One success is delivered.
3. Native failure wins and its failure observer blocks. Late complete/close calls preserve the exact original throwable and do not impede a successor.
4. Close precedes a gated delayed claim; that claim returns null while a successor remains usable.
5. Cancelling/completing public `toCompletableFuture()` copies affects those copies only. The actual lease remains claimed/pending until its native worker completes once; a fake snapshot cannot hijack its result or notify its observer.

These public observables establish terminal behavior and successor isolation, not exhaustive JVM interleavings or physical nullness of private target/claim references. Observer execution is not performed under an artificial test-held lease monitor.

## Initial failed fixture, review and global state

The first focused run passed all five interleaving tests but failed the three matrix tests before their bodies: the manually built Earth owner record differed from exact reprojection of its GeoPoint. The fixture now uses `EarthCompatibilityPoint.fromGeoPoint` and asserts that all requested positive/negative integer floors remain correct. Production coordinate equality checks are unchanged. The original failed XML is preserved in [fixture attempts](../validation/round48-capture-surface-matrix/fixture-attempts/initial-coordinate-owner-mismatch.xml).

Independent read-only review found no high-confidence blocker and called out the resolved-sample metadata, shared oracle, null-selector reachability and global-state boundaries retained above. The badlands tag is constructed on this test's new lookup references; this is not vanilla datapack tag loading validation. System properties and classloader are restored, but class/static source initialization is process-global. Tests run serially, one Minecraft version per Gradle invocation, in fresh test JVMs as in prior selections.

## Retained gates and rollback

Every prior Round 47 stdout observation is freshly checked, including complete native fill 135,168 blocks / 0 and stage replay 145 cells / 0, native capture bridge 96 / 0, all native/semantic surface/base/Cube/spherical matrices, original mc1201 13,824 / 0 and canonical 256 / 0, and the unchanged seven-case single known bare biome difference.

The six-workstream objective is still incomplete. `AtomicStorageBackend` is an injected commit/reload interface; no real persistent-world implementation is introduced. The rollout gate remains an explicit caller-evidence check with flag/parity/storage requirements, not a default hook. Real save/reload, default lifecycle, later cave/hollow/snow/decorate producers and richer Cube/Sphere source contexts remain implementation and acceptance work. No rollout defaults, Codec, save encoding, terrain behavior or canonical/apron ownership contract changes this round.

Rollback is test/docs-only relative to `recovery/tellus-round47-full-native-fill` (`921e667f4c727350911484d08a331f4752e16862`). The independent recovery commit uses an alternate index, verifies only the expected new tests/docs/evidence changed and checks the user's real HEAD/branch/index remain untouched. Compare recovery commit to its explicit parent; the old real index does not track all recovered working files and can otherwise misleadingly show deletions.
