# Round 50 — Native Sphere-Face fixed-biome semantic bridge

## Production delta and unchanged behavior

Both native SphereFaceChunkGenerator implementations now expose an identical explicit `semanticColumnKernel()` factory. It reuses the optional spherical BiomeResolver added in Round 49 and the actual native FixedBiomeSource; it does not introduce a climate classifier or install a default hook.

The native Sphere-Face materializer is constructed with water enabled and terrestrial/oceanic scales 1.0, matching `TellusSphericalDecisionKernel.Settings.DEFAULT`. Each materializer receives one package-private read-only `worldSeed()` accessor, so the factory checks the actual current native seed rather than an invented caller seed.

For an otherwise valid complete spherical decision, the callback checks:

- SphericalOwnerView and the exact generator face;
- generation scale, sea level, minimum Y, exclusive maximum Y and current native materializer seed;
- model chart bounds and `model.blockForGeo(coordinate.geo()) == owner.block()`;
- the generator's native singularity terrain-admission policy (transition allowed, excluded cap rejected).

The callback then converts canonical chunk/block coordinates through SphereFaceTopology to the centered engine frame using checked arithmetic and local floorMod, queries the real fixed source at the engine quart X/Z and proposed terrain quart Y, and maps only a real holder registry key. Direct/unkeyed holders remain UNKNOWN identity without discarding otherwise valid terrain.

The entire prior source of each generator is unchanged after removing just the inserted factory. The entire materializer source is unchanged after removing just its seed accessor. This verifies native constructors, Codec, createBiomes, fill/materialization, policy and other existing methods remain unchanged. Both factory insertions are identical across versions. No save format, generator default, world-registration hook or canonical/apron ownership contract changes.

## Native palette filling and terrain comparison

`SphereFaceNativeBiomeKernelBridgeTest` runs actual native createBiomes and fillFromNoise on 36 real ProtoChunks: six faces × three fixed keys (plains, desert, frozen ocean) × two runtime seeds (0,42). The three controlled terrain inputs include dry grass, inland water and cold-cover ocean at independent positive/negative elevations.

Before filling desert/frozen-ocean palettes, the test asserts the initial palette differs from the intended source key, avoiding a vacuous default-value comparison. The unchanged native createBiomes fills all 4×4×4 quart cells in every section. The test advances BIOMES status as the engine does, then reads **55,296 actual native biome quart cells**; each equals the chosen fixed identity. The compatibility status reflection handles the 1.20 and 1.21 ChunkStatus package change without altering production code.

Each native fill samples precisely 256 terrain points, totaling 9,216. Four selected columns per chunk span negative/positive engine frames and non-quart offsets, giving **144 semantic columns**. The explicit kernel adds no native terrain samples. Its fixed biome matches the actual native palette at the proposed terrain quart Y. The old unknown-biome kernel and new factory retain equal height/surface/water/strata and equal semantic materializer writes; all **18,672 materialized cells** and **55,296 full-height actual native block-state comparisons** match, zero differences per version. Independent CanonicalColumnPosition assertions check the expected centered engine positions.

These are distinct claims: the bridge itself does **not** write a biome container. The test invokes the unchanged native createBiomes to establish the native palette baseline, then compares the returned semantic identity to it. FixedBiomeSource ignores coordinates, so id equality alone is not an independent coordinate oracle; checked topology arithmetic, independent canonical-position assertions and source review provide that bounded support.

## Additional five tests

1. Face, scale, sea level, vertical bounds, current seed, Cube-Net owner and unrelated geographic-point mismatches fail without native terrain reads.
2. One existing kernel observes later runtime seed changes: old generation seed is rejected, a matching new generation succeeds, and restoring the seed re-admits the old context. This does not freeze an obsolete initialization snapshot.
3. EXCLUDED singularity-cap materialization fails closed while queued/unknown requests retain their terminal statuses and no native terrain reads occur. Existing generic kernel short-circuits still happen before profile callbacks.
4. A direct unkeyed native fixed holder leaves the biome unknown rather than inventing a plains id.
5. The full generator Codec encode/parse/re-encode is unchanged before/after explicit factory creation and retains FixedBiomeSource, face, scale, sea/bounds. Serialization uses the correct polymorphic provider context on 1.21 and the legacy path on 1.20, as established in Round 49. Runtime seed is still initialized separately (restored materializer starts at 0); it is not newly persisted in the Codec.

## Verification and review

The focused mc1201 new suite passes **6/6 on its first run**. The first full mc1211 run exposed a fixture-only reflection mismatch: that version uses `ProtoChunk.setPersistedStatus`, not 1.20's `setStatus`. Cached mapped API inspection confirmed the exact signature; the fixture now requires exactly one matching native status setter and obtains BIOMES from its actual parameter type. The original failed XML is archived; native status handling and production code are unchanged. The corrected focused mc1211 suite and final full selections are rerun.

The final selection runs strictly serially: mc1201 **290/290**, then mc1211 **283/283**, zero failures/errors/skips. Every previous exact suite/count, stdout observation, seven-case details and prior source hash is checked alongside the new marker. [Evidence](../validation/round50-sphere-fixed-biome/test-evidence.json) records exact XMLs, counters, hashes, initial fixture failure and the four-source insertion-only guards.

Fresh retained observations include Cube native biome 264 ids/0 and 34,320 unchanged semantic cells; all Earth capture/full-fill/surface/base matrices; Cube cover 81,900/0; spherical pipeline 1,176/0; materializer 128/0; original mc1201 13,824/0, canonical256/0 and the unchanged bare desert/stony_peaks difference among seven biome cases.

Independent read-only production review found no high-confidence introduced defect. The review confirmed native defaults, real fixed-source identity, centered-coordinate arithmetic, boundary semantics and unchanged native code. Its lifetime/provenance limitations remain explicit below.

## Remaining boundaries and rollback

- The materializer seed is volatile and checked dynamically, not atomically pinned for a whole request. Seed rebinding must remain lifecycle-initialization work rather than concurrent generation mutation. This round adds no locking or stronger atomic snapshot guarantee than native materialization already has.
- Standalone Sphere-Face currently begins with native seed 0 until its setter is invoked; existing Cube-Net createState propagates a seed to its faces. The bridge checks the current native value, not an independently verified persistent-world seed.
- Generation/sample opaque geometry ids are still checked by the common kernel; the new factory does not bind every provenance/revision id or planet identity. Geographic ownership must identify the same native block, but it does not prove every external provider input was sampled at the same instant or intra-cell point.
- Native createBiomes continues to fill fixed palettes independent of terrain admission. The explicit semantic terrain path appropriately rejects excluded caps; it does not retroactively change that engine biome stage.
- Actual deterministic ProtoChunk calls are not real GIS coverage, a running server, performance acceptance or persistent save/reload. Default callsite/lifecycle integration, richer specialized/later production phases and real atomic persistent-world storage remain engineering work. The six-workstream goal remains active.

Rollback parent: `recovery/tellus-round49-native-cube-biome` (`9e9e1cd67fc6558e32a0c4f1aca4fff92559b8a9`). Restore the four versioned production files from that recovery tree, remove the new shared test/docs/evidence and restore the status paragraph. The alternate-index recovery checkpoint validates only this expected delta and preserves the real branch, HEAD and index. No world-save migration is needed.
