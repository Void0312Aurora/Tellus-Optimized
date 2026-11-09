# Round59 — Exact Earth snow production-rule reuse

## Scope

The Minecraft-free `TellusEarthSnowDecisionKernel.resolveColumn(seed, input)`
reuses the unchanged `SurfaceMaterialDepthPolicy.snowColumn` depth/powder rule
and `MountainSurfaceRules.glacierSurfaceMaterial` palette. The two existing
Earth `applySnowCover` entrypoints consume the resolved depth/material while
retaining their original native write loops: cursor top-down with block-entity
removal, and section `fillColumnConstant` bottom-up. Eligibility, source queries,
weather, support and height inference stay with existing callers.

Glacier hollows change the material, not the snow depth; writes end at the
resolved surface, not surface+1. Native integer arithmetic is retained, including
the cursor/section difference at extreme integer bounds. The `SemanticMaterial`
ID order and existing default block-state mapper remain unchanged.

## Explicit detached API

`EarthChunkGenerator.semanticSnowColumnKernel()` returns a kernel bound to the
current seed and generator height bounds. `resolveCursor` and `resolveSections`
expose distinct effective ordered cell lists; old detached kernels do not follow
later generator seed changes. This is an explicit planning API, not a lifecycle
hook or automatic semantic transaction takeover.

## Verification

Nine focused pure tests and four native tests were added. Native test oracles
freeze the pre-extraction depth/noise/glacier arithmetic and old loops rather
than deriving expected output from the new kernel. They exercise both actual
private native entrypoints, ordered writes, entity-removal trace, min/max bounds,
complete untouched cells, factory seed binding and extreme integer arithmetic.
Final dual-version observations and exact counts are recorded separately in
`docs/validation/round59-production-integration/test-evidence.json` after execution.

These are controlled resolved-source native fixtures, not live GIS, realtime
weather, world persistence or complete terrain/biome/cave/decoration transaction
acceptance. Sphere-Face's empty later stages and all default lifecycle hooks are
unchanged.
