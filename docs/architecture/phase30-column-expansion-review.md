# Round 30: positioned column materialization and real chunk writes

## Result and scope

The opt-in mc1201/mc1211 column path now expands and writes known strata, filler, top and water cells rather than writing only one top cell. The existing CanonicalPipeline calls consume the expanded path through their ColumnWriter. No generator constructor, Codec, save encoding, default lifecycle hook or default production callsite was changed.

The previous placeholder descriptions in the phase 5/12 reviews are historical. This change completes the positioned known-column writer slice; it does not complete the remaining production DecisionKernel, FeaturePlan or persistent-storage integrations.

## Pure expansion

`TellusColumnMaterializer.materializeCells` returns immutable `SemanticColumnCell(y, material)` values in ascending Y with no duplicates. The compatibility `materialize` method projects those cells to materials; writers use the positioned API.

- Strata ranges include both endpoints; input order is normalized and overlapping ranges are rejected.
- Surface `depth` counts filler cells below the top, matching native SphereTerrainMaterializer. Earth palette total thickness remains a separate convention requiring explicit conversion.
- Known surface and filler take precedence over body strata. An explicit bedrock cell at minY is retained; a terrain top colliding with it is rejected.
- Water occupies terrainY+1 through waterY inclusive. Water at/below terrain adds no cells; water at/above the generation exclusive upper bound is rejected.
- Complete decisions require known surface/water/strata and continuous body coverage. Unknown complete data emits no payload.
- PARTIAL + KNOWN_LAYERS_ONLY emits only proved cells. Unknown surface/filler bands mask lower-priority strata, preventing guessed stone from leaking into an unknown layer.
- UNKNOWN, QUEUED, NO_WRITE partial policy and coordinate mismatch preserve terminal no-write behavior and persistence identity.
- No sky AIR clearing or implicit bedrock is invented. This matches the native fresh-column fill scope; arbitrary stale terrain above the declared column is not erased.
- Long arithmetic handles depth/endpoints. A 1,048,576-cell vertical-span budget bounds allocation/work.

This materializes the supplied terrain height; it does not reproduce the native height calculation in the simplified default DecisionEngine. Production height/rule migration remains workstream 3.

## Actual engine coordinates

`CanonicalColumnPosition` resolves Earth floor coordinates, transformed Cube-Net canonical cell centers and centered Sphere engine coordinates. Raw face-local canonical indices are no longer written as Minecraft world X/Z.

Cube mapping uses the fixed layout transform of canonical block centers, including reflected axes at integer grid-line input. All six face orientations and negative chunk coordinates are tested. Sphere centering uses floor(chunksPerFace/2)*16, including an explicit odd-chunk model test. Default resolution follows native world-scale models; custom-size adapters/writers accept an explicit SphereWorldModel. The canonical identity remains face-local and is not replaced by the engine position.

## Adapter preparation and rollback

Both version adapters share the same algorithm:

1. Reject terminal status, apron and invalid partial policy before touching a target.
2. Expand the pure semantic plan.
3. Resolve actual engine X/Z and verify target ChunkPos ownership.
4. Validate every emitted Y against target build bounds and map every material before any mutation.
5. Capture every previous block state.
6. Apply all cells and verify each retained state; mark a successful chunk unsaved.
7. On RuntimeException, restore attempted cells in reverse order, including a cell changed before the throwing sink call.

A fully restored failure returns an empty invalid result. If restoration itself fails, every remaining restore is still attempted and the adapter throws an explicit rollback-incomplete exception instead of claiming an untouched target. Reused exception instances are guarded against self-suppression.

This is block-state rollback within one explicit call, not a durable save/reload transaction. Chunk metadata/lighting/block-entity transactions and storage activation are separate remaining work.

## Verification

All final Gradle runs were serial and passed:

| Target | Selection | Result |
|---|---|---|
| mc1201 | write package, new native materializer comparison, three recovered original probes | 41 tests, zero failures/errors/skips; BUILD SUCCESSFUL |
| mc1211 | write package, new native materializer comparison, existing decision contracts | 38 tests, zero failures/errors/skips; BUILD SUCCESSFUL |

The new direct comparison writes real ProtoChunks through the new ColumnWriter and compares every Y against the existing native materializer's independent ColumnPlan.stateAt. Each version covers 128 combinations of eight cover palettes, four filler depths and four water levels:

```text
SEMANTIC_MATERIALIZER_PARITY columns=128 mismatchBlocks=0
```

Other focused regressions cover ordered strata, gap/overlap rejection, explicit bedrock, unknown masking, PARTIAL policy, all face transforms, odd-chunk sphere offset, wrong chunk/build height, late mapper failure, sink failure after mutation and repeated failures during rollback.

The recovered original baseline was rerun in the same final mc1201 invocation:

```text
CONSISTENCY_PROBE faces=6 locations=9 columns=13824 mismatchColumns=0 mismatchBlocks=0
PARITY_PROBE canonicalColumns=256 mismatches=0 maxHeightDifference=0
BIOME_PROBE cases=7 mismatches=1
BIOME_PROBE case=bare earth=minecraft:desert cube=minecraft:stony_peaks equal=false
```

These are deterministic fixture observations, not a GIS dataset coverage audit. The older data-dependent climate test's Overture HTTP 404 is not claimed fixed and was outside this selection. Exact suite names, fresh stdout observations and implementation SHA-256 values are in `docs/validation/round30-column-expansion/test-evidence.json`.

## Review and rollback

A read-only production-rule review established filler-count depth, inclusive water, actual cube cell-center transforms and chunk-based sphere centering from the native generators. A second scoped code review found the reused-exception rollback bug; it was fixed in both versions and covered by an explicit failing-sink regression in the final run.

Recovery baseline: `recovery/tellus-sources-20261108`, commit `151fded7d751a7c692e0ae371a4cd26bdace2520`. Disabling explicit pipeline calls retains the legacy producer paths. Reverting this slice to that checkpoint restores top-cell-only behavior; restore implementation and its updated tests together. No runtime registration or save migration is required for rollback.
