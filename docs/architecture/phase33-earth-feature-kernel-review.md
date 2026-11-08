# Round 33: Earth surface decision boundary and transactional feature sink

## Progress

This round advances two deferred pieces while keeping all new behavior explicit and opt-in:

1. Earth surface branch selection now has a Minecraft-free `TellusEarthSurfaceDecisionKernel` mirroring the native branch order and cover thresholds.
2. Earth column decisions can consume an injected, fully resolved `EarthSurfacePalette` through `TellusEarthColumnDecisionKernel`.
3. Both version-specific FeaturePlan sinks now perform complete preflight, actual canonical Cube-Net cell-center coordinates, mapping-before-mutation, state capture, readback, and reverse rollback.

No default generator, Codec, save format, lifecycle registration or persistent storage hook was changed.

## Earth surface kernel

`TellusEarthSurfaceDecisionKernel` consumes already resolved predicates; it does not access registries, data sources, biome holders, heights or noise. Its `selectBase` preserves native order:

```text
ocean / river-water-like -> tree cover when random land patch is off
-> mushroom -> beach -> stony shore -> badlands -> desert
-> mangrove -> swamp -> stony peaks -> gravelly hills -> cover fallback
```

Its `cover` method reproduces native cover classes 10/20/30/40/50/60/70/90/95/100/default, climate groups, roll thresholds and the alpine boundary. `oceanFloor` reproduces the native `<10 gravel`, `<15 clay`, otherwise sand partition. Inputs are range-checked and no source reads occur in the kernel.

The branch kernel is intentionally separate from the final material palette. Native biome-specific palette construction, badlands bands, Overture sand, slope overrides, and climate/biome registry resolution remain an integration boundary until their source inputs are represented in a complete semantic palette.

## Earth column kernel

`TellusEarthColumnDecisionKernel` is an explicit engine mode selected by `TellusColumnDecisionEngine.withEarthProductionRules`. It validates Earth ownership, sample coordinate and geometry identity, handles UNKNOWN/QUEUED before resolver work, computes the existing Earth rounded/clamped height, passes underwater state to an injected palette resolver, converts Earth total thickness to semantic filler count (`depth - 1`), and forwards the existing Earth biome classifier boundary.

The legacy `TellusColumnDecisionEngine()` remains stone/plains. The existing Earth biome-only opt-in remains unchanged. Earth strata are still the conservative stone fallback until the complete Earth geological palette is represented.

## Feature sink

Both mc1201 and mc1211 FeaturePlacementSink implementations now:

- preserve EMPTY/UNKNOWN/QUEUED terminal statuses;
- validate all placement owner/conflict/material conditions before mutation;
- resolve canonical non-apron Cube-Net columns through `CanonicalColumnPosition` cell-center transforms;
- retain explicit apron seam projection for apron mirrors;
- validate every destination against chunk ownership and build height;
- map every placement cell before the first target mutation;
- capture all previous states;
- verify each write by readback and mark successful chunks unsaved;
- rollback in reverse order, including a sink call that throws after changing its cell;
- continue rollback after repeated identical exceptions and report rollback incomplete when restoration fails.

The sink remains caller-owned and is not automatically invoked by the default generator or lifecycle.

## Verification

The machine-readable suite record is saved in `docs/validation/round33-earth-feature/test-evidence.json`.

The following serial runs passed:

| Target | Selection | Result |
|---|---|---|
| mc1201 | Earth surface kernel, Earth column kernel, Earth palette/depth tests | BUILD SUCCESSFUL |
| mc1201 | Feature sink package after dynamic canonical-chunk fixture correction | BUILD SUCCESSFUL |
| mc1211 | Earth surface kernel, Earth column kernel, Earth palette/depth tests and feature sink package | BUILD SUCCESSFUL |

Feature tests cover dynamic canonical chunk ownership, late mapper failure with zero mutations, throwing-cell rollback, wrong chunk/build-height no-write, terminal statuses and conflict handling. Earth tests cover native branch precedence, all roll/climate/alpine matrices, water-top conversion, filler-count conversion, legacy default preservation and non-Earth rejection.

## Native Earth callsite integration

The existing `EarthChunkGenerator.selectBaseSurfacePalette` and `coverDrivenSurfacePalette` now route their branch selection through `TellusEarthSurfaceDecisionKernel` while retaining the native palette constructors, biome predicates, random roll, mountain zone height, badlands plateau and grass/gravel fallback at the generator boundary. This is a behavior-preserving callsite migration rather than a new default generator mode: the kernel supplies the selected branch and the generator still owns Minecraft block states and source-backed special cases.

The native callsite was compiled and tested in both target layers. The Earth/Cube biome probe preserved its existing seven-case observation, including the single known bare/BWh mismatch; the focused LodBiomeSnow and Earth kernel suites passed.

## Remaining gates

- Wire the Earth surface kernel to a complete source-backed semantic palette, including biome-specific, badlands, slope, Overture and underwater variants.
- Apply Earth/Cube/Sphere kernel decisions at default production generator callsites only after full parity evidence.
- Invoke FeaturePlan placement as part of a composite transactional column path, with feature/column rollback coordination.
- Implement and verify real persistent save/reload transaction integration and default lifecycle registration.
- Re-run the recovered 13,824-column and seven-case probes through each production callsite migration.

The active global goal remains open because these gates are explicit requirements of the original six-workstream objective.
