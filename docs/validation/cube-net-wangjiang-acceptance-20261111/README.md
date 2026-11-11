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
7. Saved and stopped the acceptance client. The production delivery save was left unchanged; the command permission change was made only in the isolated acceptance copy and recorded in `commands-fix.json`.

## Evidence

- Client log: `/home/void0312/Workshop/output/tellus-wangjiang-20261110/client-runtime/logs/latest.log`
  - `Sodium native Cube-net staging enabled for sodium-0.5.13+mc1.20.1`
  - `Tellus DH LOD setup ... path=direct`
  - `Registered Tellus Cube-Net DH LOD generator path=direct seamAware=true`
  - `TellusAcceptance ... logged in ... tellus:earth_cube_net`
  - `Cube-net far-view unfold published face=POS_Z ...`
- F3 runtime evidence: `/home/void0312/Workshop/output/tellus-wangjiang-20261110/startup-acceptance-20261111/diagnostic-f3.png`
- Home-command evidence: `/home/void0312/Workshop/output/tellus-wangjiang-20261110/startup-acceptance-20261111/home-after-command.png`
- Saved native top-down evidence: `/home/void0312/Workshop/output/tellus-wangjiang-20261110/fullscale-delivery/fullscale-saved-blocks.png`
- Command permission audit: `/home/void0312/Workshop/output/tellus-wangjiang-20261110/startup-acceptance-20261111/commands-fix.json`

## Current visual diagnosis

The runtime view is not an acceptance pass. The saved native top-down image contains roads, water, vegetation and building-colored regions, while the live far view shows broad gray areas and buildings that become visible as the camera approaches. This strongly indicates that DH's lazy/direct LOD population and/or its cached coarse representation is involved in the apparent empty areas. The startup log confirms that DH is using the custom direct Cube-Net path, not a stock fallback.

The source path is `CubeNetLodGenerator`: it samples `CubeNetLodSurface.sampleLodColumn`, preserves each sampled `BlockState` as a DH data point, and suppresses DH's second full-detail regeneration pass through the runtime `SURFACE_ONLY` override. Therefore the remaining visual fault is narrowed to one of:

- direct Cube-Net LOD sampling/material aggregation;
- DH cache population, invalidation or coarse-to-fine replacement;
- native-vs-DH camera blending and render-distance handoff;
- actual generation data, especially the vertical profile.

The long distant wall may be a genuine generated structure or a coarse LOD artifact; this closeout does not classify it without the postponed A/B run. Roads rendered as rock likewise remain an open native-vs-DH classification item.

## Known generation defect / acceptance blocker

`fullscale-delivery/reports/height-boundary-review.json` and `surface-summary.json` report:

- profile `minY=0`, `height=1024`, `maxY=1023`;
- 3,455 columns within 16 blocks of the ceiling;
- 683 columns exactly at `Y=1023`;
- sampled building-material tops reaching `Y=1023`.

This is a confirmed vertical-clipping risk for high-rise buildings. The 10 km package is therefore `NEEDS_REVIEW_HEIGHT_BOUNDARY`, not final visual acceptance. The prescribed follow-up is a taller profile (for example height 2048 or the project's intended global profile), followed by regeneration and the same coverage/visual checks.

## Remaining items

- DH-off/native-only versus DH-on A/B screenshot around the same empty area.
- Clear DH cache and repeat the approach test to separate stale/coarse LOD from source generation.
- Compare a road and the distant wall against native chunk block states.
- Regenerate or validate with a taller vertical profile before accepting high-rise structures.
- M11 bidirectional walking/sprinting zero-hold evidence, GeoTP, shader-on and full edge matrix remain open.

This document records the state reached before the scheduled host shutdown; no further runtime verification was performed after the closeout request.
