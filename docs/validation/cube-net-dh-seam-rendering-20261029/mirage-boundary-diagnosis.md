# Cube-Net DH renderer and boundary diagnosis (2026-10-30)

This note records the current Prism run after the earlier renderer batches. It
keeps visual observations, protocol state, and source-level diagnosis separate.

## Test state

The Tellus `main` branch was `5903fe7c` and synchronized with
`origin/main` at the start of this run. The DH parent bridge was `1724226b4`
and the DH core implementation was `951d4b059`, both on
`github/tellus/3.3.1-iris-render-order`. The Prism profile used Tellus 0.8.4,
Distant Horizons 3.3.1-tellus.1, Sodium 0.5.13, Iris 1.7.6, Complementary
Reimagined r5.5.1, and an RTX 3090 with NVIDIA 580.178.04.

## What the current run proves

With the original client settings (`renderDistance=4`,
`simulationDistance=12`) and a world apron of 7 chunks, the client rejected the
crossing configuration because it requires at least 15 chunks. This is a
deliberate apron guard, not a deadlock.

The isolated test server used `view-distance=4` and `simulation-distance=4`,
which satisfied the apron guard. The Minecraft client rejected `simulationDistance:4`
as below its own 5-chunk UI minimum and clamped that option; this did not block
the server-side handoff test. The client log records destination chunk receipt,
Sodium staging, DH readiness, renderer promotion, and the first custom motion
at 14:12:23--14:21:33. The server log then records the deferred apron rebase,
seam transfer completion, and acceptance of the first custom motion at
14:21:33. The player moved after the transfer. This is one successful current
boundary crossing.

The later attempt to inspect a distant marker was not a valid same-marker
approach: the player was falling in an unloaded-looking view, and the two
captures do not identify the same structure. Those captures cannot establish
the reported near-disappearance.

## Reproducible DH failure found during the visual run

At 14:28:16 the server-side DH worker repeatedly logged:

```text
Cube-Net LOD column outside canonical/apron coverage
at CubeNetLodGenerator.buildLod(CubeNetLodGenerator.java:143)
```

The failing request was a coarse 64-column tile at `chunk=[1216,-64]`,
`detail=4`. The client had already received the native destination chunks but
then remained in `waiting for DH render envelope`; it timed out at 14:29:02 and
repeated the same cycle. This is the concrete cause of the observed boundary
hold in this run: DH readiness never became true, so the seam state machine
could not commit.

The request was not a legitimate second face. Its sample rectangle reached the
excluded cubed-sphere corner region. `CubeNetRemoteProjection` accepted the
single chart, while `CubeNetChunkGenerator.sampleLodColumn` correctly returned
empty for the excluded address. The DH availability predicate therefore
claimed a coarse area was usable even though generation later rejected one of
its columns.

## Implemented repair

`CubeNetChunkGenerator.isUniformArea` now checks the same corner samples for
`SphereSingularityPolicy.admitsTerrainMaterialization` after the chart check.
An area touching an excluded corner is reported as non-uniform before DH
allocates a coarse tile. For a finest 16x16 tile, the method checks every
column, so a legal seam tile can still be generated when its columns have
canonical or apron owners.

The regression tests
`coarseAreaTouchingExcludedCornerIsQuarantinedBeforeDhGeneration` and
`finestTileMayStraddleTwoOrdinaryCharts` cover both cases. The final targeted
Cube-Net tests pass: 16 tests, 0 failures, 0 errors. The full `mc1201:test` and
`testParallelChunkgen` suites pass: 973 and 7 tests respectively, with no
failures or errors.

A fresh Prism client and production-style server were then run with the rebuilt
jar (SHA-256 `d0673659d5baff575e0765b246dc7b63072b49413d7504a05325b1e32e8e2238`).
At 15:48:56 the server recorded `seam transfer complete` and `first custom
motion accepted`; the client recorded renderer promotion, pose commit, and
post-commit movement at the target coordinates. The run produced no
`outside canonical/apron coverage` exception and no DH envelope timeout. This
validates the corner-request repair and the boundary handoff for this fixture.

## Rendering diagnosis boundary

The earlier `staged-before-commit.png` does show a lower transparent/black
region while distant fixture geometry remains visible. It is a real envelope
mismatch observation, but it does not hold one landmark at a fixed camera ray
through a near approach. The source contains two plausible contributors:

* the DH terrain shader discards or fades LOD geometry inside its near clip
  distance; and
* the direct Cube-Net LOD generator materializes terrain columns, while
  ordinary block edits and structures are not part of those columns.

Neither contributor is claimed as the sole cause of the user's exact
near-disappearance until a fixed marker is captured before, during, and after
approach with the repaired runtime.

Evidence paths:

* runtime client log:
  `tools/benchmark-results/sodium-native-20261030/` and the Prism instance log;
* production test server log:
  `tools/benchmark-results/sodium-native-20261029/production-diagnosis/logs/latest.log`;
* earlier controlled captures:
  `docs/validation/cube-net-dh-seam-rendering-20261028/prism-live-acceptance-20261028/`.
