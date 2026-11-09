# Round57 — Later-stage composition matrix and source-stability gate

## Observed native call order

The current generator implementations expose different later-stage surfaces; they must not be forced through the existing column `FeaturePlan` sink without a separate multi-cell stage contract.

| Generator | Biome stage | Terrain stage | Carver/underground stage | Decoration stage | Current semantic adapter status |
|---|---|---|---|---|---|
| Earth | `createBiomes` → `fillBiomesWithColumnCache` | `fillFromNoise`; its surface fill already applies the primary snow overlay while capturing terrain/water/biome context | `applyCarvers`: vanilla density caves, ore and geological patches; direct block writes and fluid marker effects | `applyBiomeDecoration`: native features, structure protection, trees, buildings, realtime snow, road lights, deferred detail and landmarks | Native capture/context exists; later stages still mutate directly and the fill-time snow overlay is not part of a unified source-stable transaction |
| Cube-Net | `createBiomes` → native biome source | `fillFromNoise` / canonical materializer | `applyCarvers` → `applySphericalCarvers` → `CubeNetSphericalCaveSampler`; direct block writes and fluid marker effects | `applyBiomeDecoration`: native decoration, trees, realtime snow, Cube features and apron mirrors | Flow/badlands and vertical biome are explicit; cave/decorations are not source-stable semantic plans |
| Sphere-Face | `createBiomes` → native biome source | `fillFromNoise` → `SphereTerrainMaterializer` | `applyCarvers` currently empty | `applyBiomeDecoration` currently empty | Base/biome providers exist; no later cave/snow/decoration production stage is registered |

The order visible in source is `biomes → terrain → carvers → decoration`. A source-stable semantic composition must capture the shared generation identity before `applyCarvers`, retain it through decoration, and release it only after all participating stages complete. Existing Round55 leases protect participating writers on an actual target object but do not automatically cover the native engine's direct calls.

## Why the existing column plan is insufficient

`FeaturePlacement` and `TellusCompositeColumnWriter` model owned column cells and typed flow markers. Caves and underground patches are different:

- they write arbitrary Y ranges below a surface column;
- neighbouring surface heights affect the result;
- structure-exclusion boxes affect carving;
- one stage can write fluid blocks and post-processing markers;
- the same chunk may contain a 256-column surface snapshot plus a multi-cell underground field;
- native and Cube-Net samplers use different source fields.

Using a single `FeaturePlacement` per column would hide the source snapshot, halo requirements, cross-column writes and post-processing marker ownership. It would also make a successful column plan appear atomic while carvers are still able to mutate neighbouring cells directly.

## Deterministic matrix collected in this round

### Source identity inputs

Every later-stage plan must bind, before source I/O:

- world seed;
- world scale and geometry identity;
- sea level and exact vertical bounds;
- canonical chunk address / face for Cube-Net and Sphere-Face;
- 256-column surface heights and water/flood guards;
- generation-floor and underground-structure exclusions;
- cave/ore/geological/snow/decorations feature flags;
- source revision and cache namespace.

A source callback changing any of these values mid-stage should stop the stage before the first target mutation. This is a repeated profile check, not an atomic source or world-manifest transaction.

### Terminal matrix

| Input state | Required outcome | Target/source effect |
|---|---|---|
| disabled or unsupported stage | explicit invalid/no-write | no source or target mutation |
| NON_BLOCKING / QUEUED source | QUEUED | no source read, no target read where possible |
| UNKNOWN / incomplete surface or exclusion snapshot | UNKNOWN | no speculative cave/snow/decorations writes |
| complete source but missing cross-column halo | QUEUED or UNKNOWN | no prefix writes |
| complete stage plan | AVAILABLE | all mapping and destination ownership checks precede writes |
| mapper/block setter failure | restore complete stage snapshot | restore blocks, marker arrays and dirty state |
| native direct writer outside participant lease | external boundary | no concurrent-isolation claim |

## Smallest next implementation boundary

The next production increment should be a pure, Minecraft-free `UndergroundStagePlan` rather than a direct modification of the native cave samplers. It should contain:

1. a complete 256-column surface/exclusion request;
2. explicit stage flags and source identity;
3. ordered cell replacements `(x,y,z,semantic material/fluid, marker intent)`;
4. UNKNOWN/QUEUED/AVAILABLE terminal states with no partial cell prefix;
5. cross-column destination ownership checks;
6. a separate native adapter that can replay the plan under the existing target lease.

The first adapter should target Cube-Net's existing `CubeNetSphericalCaveSampler`, because its surface halo, field and marker callback are already explicit. Earth's vanilla density sampler and Sphere-Face's currently empty carver path should remain separate adapters. A common plan must not replace or reinterpret either native field.

Snow and decorations should follow only after underground stage ownership is explicit. Earth already has a decoration context carrying surface, water and biome data; Cube-Net and Sphere-Face do not expose equivalent source-stable contexts. No default registration should be added until all three adapters have complete terminal and rollback contracts.

## Round57 decision

No production code was changed in this matrix-only step. Adding a column-shaped cave placement now would falsely claim source stability and atomicity. The existing Round56 contracts remain unchanged and opt-in. The next implementation can be reviewed against this matrix with a focused Cube-Net underground plan and deterministic 256-column/halo fixture before touching Earth or Sphere-Face lifecycle hooks.

Evidence boundary: this is a source-call matrix and design gate, not live Minecraft, GIS, datapack, multi-chunk persistence or save/reload acceptance. The Round56 recovery point remains the parent for the next code increment.
