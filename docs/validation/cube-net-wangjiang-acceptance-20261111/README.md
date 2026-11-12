# Wangjiang 1:1 Acceptance Closeout — 2026-11-11

## Scope

- Minecraft 1.20.1 / Fabric Loader 0.16.14 / Java 17.
- Tellus delivery commit: `b3314694e98143c102f2e8cd9be3d139920d1598` on `origin/feat/cube-net-parity`.
- Map: Sichuan University Wangjiang Campus, WGS84 `30.6335392, 104.0815556`, radius 10 km, `world_scale=1`.
- Target save: `/home/void0312/Workshop/output/tellus-wangjiang-20261110/fullscale-delivery/saves/Wangjiang-Chengdu-10km-1to1`.

## Completed

1. Reproduced the production client with Tellus 0.8.5, Sodium 0.5.13, Iris 1.7.6, Indium 1.0.36 and DH 3.3.1-tellus.1.
2. Confirmed the client enters `tellus:earth_cube_net` and starts the integrated server.
3. Confirmed Sodium native Cube-Net staging and DH OpenGL rendering initialize together.
4. Confirmed Tellus registers the DH Cube-Net generator with `path=direct seamAware=true`.
5. Confirmed the Cube-Net unfold bridge publishes a `POS_Z` chart to DH.
6. Confirmed the delivery datapack command `/function wangjiang:home` works in the command-enabled acceptance copy.
7. The production delivery save was left unchanged. The acceptance client is currently left open in the DH `chunk` path for inspection; the runtime-only `JvmArgs` change is outside the delivery repository.

## Evidence

- Client log: `/home/void0312/Workshop/output/tellus-wangjiang-20261110/client-runtime/logs/latest.log` (current chunk run)
  - `Sodium native Cube-net staging enabled for sodium-0.5.13+mc1.20.1`
  - `Tellus DH LOD setup ... path=chunk`
  - `Registered Tellus Cube-Net DH LOD generator path=chunk seamAware=false`
  - `TellusAcceptance ... logged in ... tellus:earth_cube_net`
  - `Cube-net far-view unfold published face=POS_Z ...`
- Direct-path log evidence is retained in the prior compressed runtime log; the paired direct screenshot is [`reopened-world.png`](/home/void0312/Workshop/output/tellus-wangjiang-20261110/startup-acceptance-20261111/reopened-world.png).
- F3 runtime evidence: `/home/void0312/Workshop/output/tellus-wangjiang-20261110/startup-acceptance-20261111/diagnostic-f3.png`
- Home-command evidence: `/home/void0312/Workshop/output/tellus-wangjiang-20261110/startup-acceptance-20261111/home-after-command.png`
- Saved native top-down evidence: `/home/void0312/Workshop/output/tellus-wangjiang-20261110/fullscale-delivery/fullscale-saved-blocks.png`
- Command permission audit: `/home/void0312/Workshop/output/tellus-wangjiang-20261110/startup-acceptance-20261111/commands-fix.json`

## Current visual diagnosis

A same-save A/B run now separates the render paths:

- With `JvmArgs="-Dtellus.dhCubeNetSeamLod=true"`, the log reports `path=direct seamAware=true`; [`reopened-world.png`](/home/void0312/Workshop/output/tellus-wangjiang-20261110/startup-acceptance-20261111/reopened-world.png) shows broad gray areas with only sparse buildings.
- With the flag removed, the log reports `path=chunk seamAware=false`; [`chunk-path-f3.png`](/home/void0312/Workshop/output/tellus-wangjiang-20261110/startup-acceptance-20261111/chunk-path-f3.png) shows the same world position with dense buildings, trees and structures spread across the view.

The cause is now narrowed to the Cube-Net direct LOD path, not the saved Anvil coverage:

1. `CubeNetLodGenerator` calls only `CubeNetChunkGenerator.sampleLodColumn` and writes sampled terrain layers to DH.
2. `sampleLodColumn` uses `materializer.plan(...).lodLayers(...)`; coarse cells use one representative terrain point.
3. The existing `sampleFeatures` method is not called by direct LOD, so direct LOD does not carry native roads, buildings or trees.
4. The native road mapper intentionally uses gray concrete, smooth stone, gravel, dirt path, cobblestone and stone bricks; a gray road-like surface in chunk mode is therefore not by itself proof of a terrain error.
5. Direct LOD also supplies a fixed plains biome wrapper, so vegetation/grass tint can differ from native columns.

The operational workaround is to leave the Cube-Net direct flag unset. DH then uses `TellusChunkLodGenerator`, which returns `VANILLA_CHUNKS` from `level.getChunk(...)`; this preserves native feature placement while DH still provides far rendering. The current acceptance instance is left in that chunk mode.

The distant high wall is partly native generated terrain, not a DH-only artifact. Read-only profiles from the unchanged delivery save show water at `Y=624` immediately beside a dry column at `Y=546` (a 78-block one-cell drop); the high side contains coarse dirt, clay, packed mud, terracotta, sandstone, sand and only 4–6 water blocks. Compact evidence: [`native-water-boundary-compact.json`](/home/void0312/Workshop/output/tellus-wangjiang-20261110/visual-diagnosis-20261112/native-water-boundary-compact.json). This is an elevated solid/water platform edge and should be reviewed as a native hydrology/terrain-generation anomaly, independently of DH.

The same save also contains complete building bodies. The highest sampled building spans `Y=559..673` with 384 façade columns and glass/quartz/polished-andesite profiles; a low-building sample contains its foundation, repeated floor slabs and stone-brick roof. Compact detail: [`native-wall-foundation-detail.json`](/home/void0312/Workshop/output/tellus-wangjiang-20261110/visual-diagnosis-20261112/native-wall-foundation-detail.json). Thus low buildings are not generally truncated in storage. Cube-Net's native building writer does, however, use one centroid `groundY` for every footprint cell and writes a uniform relative `heightBlocks`; it also ignores `minHeightBlocks`. This can create level platforms/walls and loses Earth's per-column floor/roof contact behavior. The vertical profile still ends at `maxY=1023`, with 683 sampled columns at the ceiling, so high-rise acceptance remains blocked until a taller-profile regeneration is checked.

## Known generation defect / acceptance blocker

`fullscale-delivery/reports/height-boundary-review.json` and `surface-summary.json` report:

- profile `minY=0`, `height=1024`, `maxY=1023`;
- 3,455 columns within 16 blocks of the ceiling;
- 683 columns exactly at `Y=1023`;
- sampled building-material tops reaching `Y=1023`.

This is a confirmed vertical-clipping risk for high-rise buildings. The 10 km package is therefore `NEEDS_REVIEW_HEIGHT_BOUNDARY`, not final visual acceptance. The prescribed follow-up is a taller profile (for example height 2048 or the project's intended global profile), followed by regeneration and the same coverage/visual checks.

## Remaining items

- Review Cube-Net inland-water/shoreline inputs for the native 78-block platform edge; this is separate from the DH direct LOD defect.
- Compare Cube-Net building placement with Earth's per-column base/floor/roof handling before accepting visual parity; the current Cube path uses centroid groundY and a uniform relative height.
- Clear DH cache and repeat the approach test if a stale LOD cache needs separate exclusion.
- Regenerate or validate with a taller vertical profile before accepting high-rise structures.
- M11 bidirectional walking/sprinting zero-hold evidence, GeoTP, shader-on and full edge matrix remain open.

The DH direct/chunk A/B comparison is complete. The current runtime workaround is the documented default chunk path: DH remains present for far rendering, while Tellus supplies native chunks containing roads/buildings/features. No delivery save or source world was modified by the A/B run.
