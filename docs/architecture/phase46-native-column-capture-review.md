# Round 46: exact native Earth column capture and semantic replay

## Implemented boundary

The resumed work adds a Minecraft-free `TellusEarthNativeColumnSnapshotReader` and an explicit, single-selected-column capture lease on the native Earth generator. `captureNativeColumn(target, generation, coordinate)` validates the active native seed/scale/sea/build profile and exact Earth owner/target bounds. It is opt-in; nothing registers it by default, changes a Codec, or changes save encoding.

The lease binds the exact `ChunkAccess` object, not merely its position. Only one active lease is admitted per generator, and only one invocation can claim it. An unrelated chunk, including a second chunk object at the same position, does not consume the claim. Generation context is rechecked when the native fill claims a matching request.

The selected column is copied twice inside the actual production loop:

1. After the native rock, support/bedrock and water/inland-flow writes, before its surface operation.
2. After its existing surface operation, immediately before its snow operation.

Its stage is explicitly `POST_SURFACE_PRE_SNOW_AND_SHELL_SKIN`. Subsequent snow, shell side skin/curtains, roads, structure protection/clearance, later decoration and postprocessing metadata are not represented. This is not a final-world snapshot.

All four existing native surface signatures are retained. Capture-aware overloads pass an explicit nullable builder and retain the already resolved six-field palette, surface geology, proposed top (after thin-shell ore selection), flags/relief and biome identity. The full-mode absent-palette branch records a known no-op. No second source lookup, generic biome palette, reconstructed height or `getBaseColumn` substitution is introduced. Proposed top is an input to the native surface plan; it need not equal the actual written top in a higher-priority badlands branch.

## Exact state and semantic representation

Immutable raw `BlockState` lists cover minY through max(terrainY, explicit waterY), with no sky suffix. They retain properties, internal AIR, real support placement, mountain-body geology and any real pre-surface AIR at the reported thin-shell terrain top. Surface and body geology are not conflated.

The pure reader validates null/range/budget inputs before callbacks, then reads the supplied body once in ascending Y and emits exact inclusive RLE and depth-zero top. It verifies only explicitly declared water cells above terrain. UNKNOWN inputs or an unrepresentable water cell produce an empty semantic result, not a fallback material; programmer errors and callback exceptions retain their failure semantics. Existing valid wet decisions at/below terrain are preserved without invented above-body reads.

Raw snapshots remain available when semantic mapping declines a nondefault state, a foreign block or flowing water. Native default-state mapping remains exact. `semanticBefore` and the captured native surface context can explicitly drive the existing `semanticSurfaceColumnKernel`; the bridge tests compare its materialized cells and strata against `semanticAfter` and raw after-state. This replay remains caller-controlled and does not take over the default producer/writer lifecycle.

## Resource, error and concurrency contract

The section-writer reader accesses already-owned palette containers directly; unowned sections are temporarily acquired and released. Cursor reads also acquire/read/release sections directly. Neither reader consults potentially stale air counts nor changes dirty counts. No capture reads reacquire a palette already owned by the writer.

The immutable payload is assembled at its column boundary, but the result is completed only after the native fill has returned and writer finish/finally has released sections. The native generation failure is reported unchanged. Capture RuntimeExceptions are isolated into the capture result rather than changing normal native writes; fatal Errors still propagate.

Claim/terminal decisions are synchronized. Slot release is a compare-and-set, occurs before future completion, clears retained chunk/claim references, and cannot erase a successor lease. Completion callbacks run outside the lease monitor. Close is idempotent. Close before the delivery terminal transition suppresses success; close after that transition may return while completion finishes. Dependent observer failures stay on dependent CompletionStages. No callback is executed while the generator's section writer owns sections.

Source-off execution adds guards and delegation through preserved surface overloads, but performs no snapshot scan, semantic mapping, list allocation or additional source sampling. No zero-overhead performance claim is made.

## Validation scope

The final evidence is recorded in `docs/validation/round46-native-column-capture/test-evidence.json`, including the executed XML suite inventories, stdout counters and source hashes. The three new test XMLs per version are retained beside it.

New tests comprise 15 pure reader contracts, 12 native lease/section/context contracts, and three public-registration/kernel-bridge contracts. The bridge exercises full/thin surface × cursor/section backends: four columns, 96 exact body/water cells and zero differing blocks per version. The lease suite covers exact object identity, exclusive admission, concurrent opens, cancellation, successor preservation, raw property/water preservation, section access, errors, immutable results and callbacks outside the monitor. Native surface tests call all four real capture-aware surface overloads.

Final serial selections passed **mc1201 262/262** and **mc1211 255/255**, with zero failures, errors or skips. Both selections replayed every Round38 selected suite plus the 30 new tests. Executed stdout retained the original 13,824 columns / zero differing blocks, canonical heights 256 / zero differences, and seven biome cases with only the already known bare desert/stony_peaks mismatch. All prior Earth/Cube/spherical/materializer matrix counters were freshly checked. Comparator unit tests passed 4/4.

These are registry/ProtoChunk/cache-only resolved-input tests. They do not drive complete `fillFromNoise`/GIS resolution or a real server/world, and do not verify a persistent save/reload. Capture-specific source fixtures are underwater; dry/badlands/strata/native top-only branches retain the broader source-off surface matrix but do not yet have equivalent capture-specific source matrices. Deliberately raced claim/close/complete tests and complete capture-off/on native fill acceptance remain useful follow-up. Independent read-only review found no high-confidence defect within this narrow observation scope and recorded those gaps explicitly.

## Remaining gates and rollback

Complete source-backed native fill acceptance, later cave/hollow/snow/decorate semantics, richer specialized Cube/Sphere context and biome decisions, persistent world transactions and default lifecycle rollout remain engineering work. The six-workstream goal is not complete.

Rollback parent is `recovery/tellus-round38-earth-surface-plans` at `42bceef6a3ff76b65e875b56bdecdd3808c16a67`. Revert the generator hooks, capture helper, pure reader and their three suites as one unit. Existing source-off native generation continues when no capture lease is opened; no runtime registration or save migration must be undone. The checkpoint is saved on a separate recovery ref using an alternate index, preserving the real branch/index.
