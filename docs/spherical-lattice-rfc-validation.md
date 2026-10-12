# Spherical lattice RFC validation

This document records the first executable part of Issue #1. It is a
deterministic research prototype, not a replacement for `tellus:sphere_face`.
The prototype lives in `SphericalLatticePrototype` and is deliberately
independent of Minecraft chunk materialization.

## Phase 0 contract

The prototype fixes these meanings before testing a metric:

```text
GridVersion = (PlanetID, integer version)
PatchKey    = (hierarchy level, x, y)
BlockID     = (GridVersion, PatchKey, i, j, k)
```

`PatchDescriptor` owns one finite chart and maps its integer `(i,j)` cells to
geodetic points. `Atlas.owner(point)` uses the chart inverse and a lowest
`PatchKey` tie-break on shared boundaries. The owner therefore does not depend
on the order in which descriptors are supplied. `Atlas.neighbor` uses explicit
reciprocal boundary links; it refuses a one-way link before any cell can be
materialized. Vertical `k` is carried through an edge transition unchanged in
this surface-only prototype.

These are storage contracts only. No block state, block entity, scheduled tick,
or Minecraft `ChunkPos` is duplicated by the prototype.

## Phase 1 metric baseline

The report samples a face of the existing equiangular cubed-sphere mapping and
50 km isolated local charts at 65 points per axis. Scale values are relative to
the nominal chart or face-center block scale.

| candidate | min scale | max scale | max condition | area range | max angle error |
|---|---:|---:|---:|---:|---:|
| existing cubed sphere | 0.667828 | 1.152703 | 1.726048 | 0.708948–0.999998 | 0.520590 rad |
| exponential chart, 50 km | 0.999995 | 1.000000 | 1.000005 | 0.999995–1.000000 | 5.13e-6 rad |
| stereographic chart, 50 km | 0.999992 | 1.000000 | 1.000000 | 0.999985–1.000000 | 2.77e-12 rad |

The cubed-sphere row is the measured baseline that motivates the RFC. The
isolated-chart rows do not prove seam compatibility: they only show that a
finite chart can have a much smaller local metric error. The stereographic
formula also matches the RFC estimate for a 50 km square:

```text
L² / (8 R² + L²) = 7.7e-6
```

The exponential chart preserves radial geodesic distance more directly but its
rectangular coordinates have a small angle error away from the anchor.
Stereographic coordinates are conformal and keep the sampled axes orthogonal,
while their scale varies across the patch.

## Executed checks

```sh
./gradlew -PtellusTargets=mc1201 :mc1201:test \
  --tests 'com.yucareux.tellus.worldgen.SphericalLatticePrototypeTest' \
  --offline --no-daemon
```

The six prototype tests currently cover:

* deterministic metric reports for both local chart candidates;
* agreement with the closed-form stereographic scale estimate;
* owner identity under reversed patch descriptor order;
* shared-edge neighbor reciprocity and vertical identity;
* rejection of missing or nonreciprocal boundaries;
* explicit evidence that the current cubed-sphere metric is bounded but not
  locally orthogonal and unit-scale everywhere.

The closed-topology experiment is covered separately by:

```sh
./gradlew -PtellusTargets=mc1201 :mc1201:test \
  --tests 'com.yucareux.tellus.worldgen.ClosedQuadSphereTopologyTest' \
  --offline --no-daemon
```

`ClosedQuadSphereTopology` builds a deterministic quadrilateral subdivision of
the six faces of a cube.  Its audit for subdivision `n` is:

```text
faces = 6 n², vertices = 6 n² + 2, edges = 12 n²
sum(4 - degree(v)) = 8, Euler characteristic = 2
```

The executable checks pass for `n = 1`, `4`, `5`, and `7` generation-order
probes.  They verify eight degree-three extraordinary vertices, degree four
elsewhere, two incidences per edge, exactly one reciprocal neighbor direction,
stable canonical signatures under reversed insertion, and identical exact
spherical positions for all face-local uses of a shared cube vertex.  This is a
topology and indexing result; the class intentionally does not claim that the
cube embedding has low metric distortion.

## What this does not establish

The closed-topology result still has no full low-distortion embedding proof, no
adaptive sparse coverage policy, no persistent Anvil-backed store, and no
Minecraft seam collision or client test. In particular, the small isolated
chart error and the cube topology audit cannot be promoted to a global metric
claim. The eight extraordinary vertices need an explicit runtime policy before
they can be used by a player-facing lattice.

The next step is Phase 2: persist the canonical IDs and owner decisions,
reload them, and exercise edits across a seam. Only after that should the
prototype be connected to real Fabric chunk materialization. The existing
`tellus:sphere_face` generator remains unchanged while these experiments run.
