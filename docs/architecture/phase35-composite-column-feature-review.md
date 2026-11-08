# Rounds 34–35: one terrain and feature block-state transaction

## Implemented path

Both explicit version CanonicalPipeline.write methods now consume the FeaturePlan from the same shared plan as the ColumnDecision. They delegate to a thin version-specific CompositeColumnWriter and one shared TellusCompositeColumnWriter implementation. An EMPTY or absent feature plan keeps the existing terrain-only behavior. AVAILABLE placements are actually applied with the terrain; they are no longer merely a planning/status gate. Default generator registration and lifecycle wiring are unchanged.

The shared writer has no Minecraft storage dependency. Its Target interface supplies version-local chunk identity, build bounds, reads, writes, an air predicate and a dirty marker. The native adapters use ChunkAccess and BlockState and perform no chunk access when constructed. An explicit SphereWorldModel overload is available for custom face sizes.

## Preparation and commit

1. Resolve the persistence identity and validate canonical decision ownership and terminal UNKNOWN/QUEUED states. Apron writes are rejected by the composite path; the standalone apron feature sink retains its separate derived-mirror responsibility.
2. Expand the terrain with the existing TellusColumnMaterializer, preserving PARTIAL/NO_WRITE policy and continuous known-layer guards.
3. Validate every feature owner, operation and material. Accumulate a long cell count and reject batches above 1,048,576 cells before feature expansion.
4. Resolve the actual Cube-Net cell-center or centered Sphere engine coordinate. Check feature arithmetic, generation bounds, target chunk ownership and target build bounds for every candidate.
5. Map the complete terrain-plus-feature batch before reading original block states or performing a mutation. A mapper failure in the last feature therefore leaves the entire terrain untouched.
6. Capture each unique original position once and resolve the effective final states in deterministic plan order. Terrain is the initial overlay; features see that terrain plus preceding features.
7. Apply each unique final position once, verify it by readback and mark the successful chunk unsaved.
8. On a runtime write/readback/dirty-marker failure, restore every attempted position in reverse order, including a setter that changes its block before throwing. Continue remaining restores when a restore fails, including reused exception instances. Return an empty invalid result only after complete state restoration; otherwise throw an explicit rollback-incomplete exception.

Duplicate terrain/feature coordinates never overwrite the original snapshot. The returned mapped payload represents the unique final writes, rather than intermediate states that were superseded before commit. Persistence identity and the terrain WRITTEN/PARTIAL status are retained. The pipelines also reject a generation argument that differs from WriteContext.generation before sampling.

## Feature conflict semantics

These are local batch semantics over the effective prepared state:

| Policy or operation | Behavior |
|---|---|
| REPLACE_AIR | Skip a candidate unless its effective cell is air. |
| PRESERVE_EXISTING | Preserve effective non-air, including this batch's terrain or a preceding feature. |
| REPLACE_TERRAIN | Replace this batch's terrain or air; reject a protected pre-existing non-air cell outside that terrain or non-air from a preceding feature. |
| OWNER_ONLY with SET/FILL | After ownership validation, apply ordered last-planned-state wins. |
| MIRROR / MIRROR_OWNER | Reject on the canonical composite path. |

Feature X/Z offsets retain the existing sink's engine-frame offsets from the resolved base. Feature offsetY retains its existing absolute generation Y meaning. All destinations must be in the supplied chunk; callers must partition multi-chunk feature work before invoking this transaction.

These rules are not durable structure/edit protection. OWNER_ONLY can replace pre-existing states within the owned chunk. No new global conflict registry or per-feature durable ownership was invented.

## Validation and review

Focused tests use both an in-memory generic Target and actual ProtoChunks. The shared suite covers 17 cases: complete preparation, late mapper failure, throwing feature cell with whole-terrain rollback, reused rollback exceptions, overlapping SET/FILL, effective-state conflict policies, budget/coordinate/build bounds, PARTIAL policy, owner/model mismatch, apron rejection and terminal status/identity preservation. Each version additionally has six real CanonicalPipeline tests for combined terrain/features, late mapper failure, feature-phase rollback of terrain, out-of-chunk rejection, effective REPLACE_AIR/PRESERVE_EXISTING and generation mismatch before sampling.

The final serial version selections include the existing write/decision/feature suites, the 1,176-column spherical production pipeline comparison and 128-column materializer comparison. The final mc1201 selection also reruns all three recovered original probes (13,824-column consistency, 256-column height parity and seven-case biome observations). The final selection passed mc1201 122/122 and mc1211 115/115, with zero failures, errors or skips. Machine-readable counts, suite names, source SHA-256 hashes and stdout observations are in docs/validation/round35-composite-column-feature/test-evidence.json. A separate evidence validator checked 13,824 columns with zero differences, 256 canonical height columns with zero differences, both versions' 1,176 spherical columns and 128 materializer combinations with zero differing blocks, and all seven biome observations. The bare case remains exactly Earth minecraft:desert versus Cube minecraft:stony_peaks; all six other cases match, avoiding reliance on the original probe's weak summary assertion alone.

A separate read-only review found no blocking defect in preflight, duplicate-state capture, mapper failure, throwing-write/readback/dirty-marker rollback or feature-empty compatibility. It identified a pre-existing limitation: the CanonicalPipeline UNKNOWN/QUEUED early returns are still identityless, even though direct shared composite calls preserve identity. This is recorded rather than silently described as fully uniform.

## Transaction boundary and remaining work

The caller owns exclusive access to its chunk during preparation and commit. This is one synchronous block-state transaction, not isolation against concurrent external edits. Chunk metadata, heightmaps, lighting, block entities, durable edit protection and disk save/reload are not part of the state rollback guarantee. A failed call may leave dirty metadata set even after every block has been restored.

The existing real-world storage coordinator contract has not been connected to a persistent world backend. Default lifecycle and semantic generator takeover remain gated. Earth source-backed semantic palette, Cube-Net specialized surface/biome migration and durable transaction acceptance are still remaining requirements of the global goal.

Rollback baseline: recovery/tellus-round33-earth-callsite at dd1204ceabc3915b09582a49765bf386cfd18b4e. Restore the two pipeline changes, shared/native composite writers and their tests together. Standalone column writers and standalone feature sinks remain available. No Codec or save-format migration is needed to revert this slice.
