# Round58 — Detached canonical Cube-Net underground plan and native replay

## Change and purpose

A new immutable `UndergroundStagePlan` describes ordered, bounded, cross-column underground deltas without Minecraft classes. Its request binds a canonical engine chunk to the corresponding face-local Cube-Net address, a generation profile/seed, 256-column surface/flood/floor snapshots, structure exclusions, flags, source revision and (for geology) a complete radius-two 20×20 surface halo. Arrays are copied on construction and access. Halo center values must equal the primary surface snapshot. Native arithmetic is checked before execution, so extreme surface/floor/sea values do not wrap inside the unchanged cave sampler.

`UndergroundStagePlanner` constructs a resolver only after validation. Nonblocking requests return QUEUED before resolver construction; UNKNOWN and QUEUED plans expose no delta prefix. PARTIAL is rejected. AVAILABLE may be empty. Coordinates are unique and bounded to one chunk/build interval; marker intent is restricted to water/lava. The request permits at most 4,096 structure exclusions and the plan at most 1,048,576 deltas. Explicit replay also queues manually constructed AVAILABLE nonblocking plans before target access. Existing SemanticMaterial IDs and serialized formats are unchanged.

`CubeNetUndergroundStageAdapter` applies completed plans under the existing non-reentrant actual-target identity lease. Complete cell and metadata preflight precedes writes. Each block and fluid marker occurrence is read back; failed publication restores old values in reverse order, complete duplicate-sensitive marker state, and dirty state. A RuntimeException returns INVALID after complete recovery; Error is rethrown unchanged after complete recovery; incomplete recovery throws with the original cause and recovery failure. The generic target and metadata callbacks are caller contracts, not proof of world persistence.

`CubeNetNativeUndergroundStage` supplies the concrete ProtoChunk path:

1. Validate canonical address, generation bounds, native singularity-cap admission, and unique exact section/state-palette objects.
2. Hold the participant lease while snapshotting the actual target.
3. Copy native states into a detached ProtoChunk and invoke the existing `CubeNetSphericalCaveSampler` unchanged, using only the frozen halo and a cave field derived from the request seed.
4. Record semantic deltas and marker intent. No real target writes occur during preparation.
5. Return an opaque prepared value bound to that actual target and full block baseline.
6. Reacquire the same participant lease at replay, reject foreign/stale targets, and apply the recorded deltas without source/sampler/registry queries.

The original sampler still decides native replaceability (carver tags versus base-stone tags), geology burial, cavity/ore precedence, structure carving exclusions and flood/floor policy. No second implementation of cave-field arithmetic was introduced. Existing generator entrypoints, defaults, Codec and save format are untouched.

## Verification

The three new test classes cover pure snapshot/planner contracts, generic publication/recovery contracts, and actual native sampler/ProtoChunk parity. Native fixtures use controlled block tags and surface/halo inputs; original tags are restored after the suite. They are not live datapack/GIS inputs.

The matrix contains six faces × three cave/ore/geology flag profiles, including rotated/negative engine coordinates, mixed native replaceability, structure exclusions, flood guards, floor guards and off-chunk halo reads. Every final native block state and ordered marker array is compared with an independently invoked native sampler. Preparation is repeated for deterministic plans and verified to leave the actual target unchanged.

The first focused run found a fixture that incorrectly assumed face zero produced a fluid marker. The parity matrix itself passed. The failure test now searches the bounded six-face/two-profile native matrix for an actually observed fluid marker and asserts one exists before injecting RuntimeException/Error after marker mutation. No production field or probability was altered. One compile-time holder-type correction was also confined to the test fixture.

Final serial regression passes mc1201 **430/430** and mc1211 **423/423**, with zero failures/errors/skips. Each version includes 31 new tests (10 pure, 14 generic replay, 7 native). The native matrix compares **18 chunks / 737,280 block states / zero mismatches**, **12,372 deltas**, **4 lava markers**, and complete ordered marker arrays; 11,088 native halo reads exercise off-chunk geology inputs. The observed set is CAVE_AIR, LAVA, COPPER_ORE, DEEPSLATE_IRON_ORE, GRANITE and TUFF. Native WATER was not observed. Runtime and Error injected after a real native fluid marker mutation both restore the original complete chunk block contents, marker list references/duplicates/nulls and dirty flag.

`../validation/round58-underground/test-evidence.json` records the actual suite inventories, test cases, outputs and 73 source/test hashes. All 66 prior raw hashes remain unchanged; all prior stdout observations, including the original 13,824-column probe and seven-case comparator baseline, were freshly reproduced from the final XMLs. Initial failing fixture XMLs remain labeled nonfinal separately.

## Boundaries and remaining work

- Snapshot sourceRevision/profile is a declaration supplied by the caller. This bridge freezes supplied values; it does not atomically acquire a GIS/cache/world manifest.
- Prepared native values are actual-object-bound, transient, and not storage payloads. The full block baseline check detects stale cells, but engine/mod writers outside the participant guard and world-key aliases remain external.
- Native cap admission uses the existing default policy. Pure canonical plans do not themselves claim native cap admission; the native bridge checks it before native work.
- Success/recovery concern blocks, markers and dirty state. No guarantee is added for palette allocator history, chunk status, lighting, ticks, structures, heightmap metadata, disk state or crash recovery.
- The observed native matrix must be reported by its actual material set. WATER not observed in a fixture is not native water acceptance; separate generic water-marker tests cover the interface only.
- Earth vanilla-density capture, snow overlays, decoration and multi-stage atomic composition remain open. Sphere-Face's existing empty later stages are unchanged.
- Generator-owned source capture, default lifecycle, real persistent-world save/reload and rollbackable runtime registration remain gated. Existing nondefault Cube Codec sea offset drift remains unchanged.

## Rollback

All production and test changes are new opt-in files. Removing the Round58 paths restores the Round57 checkpoint exactly. The recovery ref is created with an isolated Git index; real branch/HEAD/index are not staged or moved. The machine rollback audit verifies the parent tree before publishing the checkpoint.
