# Round 53 — Shared native Cube badlands post-pass and transactional semantic overlay

## State-expression audit and chosen boundary

The current SemanticMaterial/mapper contract represents exact known default block states. Native inland flow sets WATER.LEVEL1..8 and post-processing markers, so replacing those with default WATER would lose behavior. This round leaves that flow stage native and unchanged. It migrates the independent badlands post-pass first, because all seven band colors and its plateau outputs already have exact semantic mappings.

A new Minecraft-free TellusCubeBadlandsDecisionKernel consumes resolved minY/surfaceY, worldScale, relief, slope and Earth material coordinates. It preserves native arithmetic and priority:

- finite relief uses ceil(relief/max(0.0001,scale)), narrowed to int then clamped at0;
- only relief below3 permits finite slope fallback, retaining `(int)Math.round(slope/9.0)*3` cast/multiply order;
- plateau is one top write, including the established brown-plateau switch case mapping to ordinary terracotta;
- cliff depth remains cliffBandDepth(3,localRelief), minimum24/maximum65,536;
- the top cell is always first, even at/below the lower clip boundary, followed by descending cells to max(minY+1,top-depth+1).

The immutable list uses a long descending iterator to avoid accidental loop-counter wrap. Parity is claimed for supported native build-height ranges and tested resolved arithmetic, not the old pathological 32-bit-height wraparound loop.

## Minimal native production adoption

Both CubeNetChunkGenerator versions call this kernel inside their existing applyCubeNetBadlandsBands. The current dry guard, actual IS_BADLANDS holder-tag check, raw source cache, quart query, canonical-to-Earth material coordinates and unsaved-tail remain. The native cursor still writes top-first using its existing MinecraftVersionCompat block setter. No source read is replaced with a provider snapshot and no new default pipeline hook is installed.

The native band/plateau block helpers are retained unchanged as an independent version-mapping oracle. The new shared semantic material mapper uses vanilla defaults rather than their older release-fallback lookup path. Across each actual target version,640 comparisons verify all seven band colors and plateau states match those retained helpers exactly. This is an explicit two-version proof, not a blanket promise for hypothetical versions with missing blocks.

## Explicit badlands FeaturePlan producer

Both versions also expose identical opt-in semanticBadlandsFeaturePlanner factories. The caller composes it with the generator's Round52 base provider/kernel and existing CanonicalPipeline. There is no new writer format, enum prefix, Codec, save record or default lifecycle registration.

The planner validates generation/sample/column identity, complete/terminal status, native face/profile/seed/canonical ownership and build bounds. Fixed biome sources retain the native post-pass no-op. Missing raw elevation yields UNKNOWN rather than fabricated overlay states; raw water is excluded regardless of enableWater. It reads the same fresh raw terrain source and real native biome tag at the raw height, not adjusted base height, sample.biome or cached provider SurfaceData. Each planned default-state cell is a canonical CUSTOM, SET, OWNER_ONLY placement with absoluteY and no horizontal offset.

The established composite writer combines base terrain and these overlays into one prepared, mapped, snapshotted and readback-verified block-state transaction. OWNER_ONLY SET preserves the native ability to overwrite any existing cell or write above cached base terrain. This proves final-state and transaction semantics, not that composite write order is identical to native top-first order: an overlapping base cell can retain the writer map's earlier insertion position.

## Real tagged native tests and independent oracles

CubeNetBadlandsFeaturePipelineTest creates an independent real MappedRegistry with vanilla biome values and explicitly binds IS_BADLANDS to the three badlands holders. VanillaRegistries.createLookup alone does not load those datapack tags. The source still performs its real classification and RandomBiomeMixer; density0.4 is not a force-badlands switch. A bounded search inspects up to64 admitted candidate chunks and256 local columns per chunk, selecting four columns whose actual native getNoiseBiome holder reports IS_BADLANDS. Thus the badlands branch is non-vacuous and is established by observed native tags, not key strings.

The first fixture demanded four arbitrary fixed positions all lie in a random patch and found no such combination on NEG_Y. Its failed XML is archived. The correction selects four actually tagged columns from the bounded native observation; it changes no production mixer, density, ownership guard or state comparison.

The final matrix covers six faces × two profiles (including water-off, height scales1.7/2.5 and effective sea offset) × three plateau/relief-cliff/slope-fallback cases × four observed tagged columns:144 complete columns and55,296 actual native block-state comparisons per version. Planned placements are also checked against a frozen pre-migration algorithm rather than comparing only two consumers of the new kernel. The base-only result must actually differ after overlays, and real native full fill must match the transactional pipeline. These dry cases leave flow inactive; later carver/decorate stages are not invoked.

Additional integration tests verify:

- all640 exact native version-helper mapping comparisons, including all seven band colors;
- raw wet input skips badlands even with water disabled, and queued requests stay source-cold;
- a mapping failure in an overlay material leaves the actual target ProtoChunk all-air;
- changing a raw source after the base grid is cached intentionally produces overlays above cached base height, matching the real native fill/post-pass for that complete column.

Seven pure tests independently compare6,912 resolved cases with the old algorithm across coordinates, heights, scales, relief and slope thresholds. They cover top preservation, descending order, ceil/slope precedence, finite-extreme cast order, depth/clipping bounds, all seven band materials, current plateau outputs, list immutability and invalid cells. The old plateau brown/coarse switch cases remain source-compatible but are not emitted by the current plateau policy.

## Evidence and remaining gates

[Machine-readable evidence](../validation/round53-cube-badlands/test-evidence.json) stores exact final suites, XMLs, source hashes, counters, prior parity observations and initial fixture failure. Full selected regressions run serially across mc1201 and mc1211, not concurrently. Removing the new planner and the one native post-pass delegation segment restores the entire previous generator file; the new planner/post-pass blocks are identical across versions. Codec, constructors, old material helpers, base producer and flow stage stay unchanged.

The new path performs fresh source/tag reads, not an atomic source snapshot or a persistent replay proof. Seed/profile binding still uses current in-process native fields rather than a world manifest. Missing raw data and out-of-range planned writes stop semantic composition; no clipping silently invents a complete result. FeaturePlan transaction coverage is block states only and does not absorb native flow post-processing metadata.

Default lifecycle, exact flowing-water properties/markers, later caves/decorations, vertical biome-container writes, live GIS/server acceptance and real persistent-world save/reload remain engineering work. The existing non-default-heightOffset Cube Codec sea drift characterized in Round52 is still unchanged and remains a save-compatibility gate. The whole goal remains active.

Rollback parent: `recovery/tellus-round52-native-cube-base` (`9b352b7193dd925a56d082cace6e11158089bc45`). Restore both Cube generator files, remove the new shared kernel/tests/docs/evidence and restore the status paragraph. The alternate-index checkpoint preserves the real branch, HEAD and index; no world-save migration is introduced.
