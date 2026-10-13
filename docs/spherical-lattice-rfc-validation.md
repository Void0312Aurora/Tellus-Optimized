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

The prototype tests currently cover:

* deterministic metric reports for both local chart candidates;
* agreement with the closed-form stereographic scale estimate;
* owner identity under reversed patch descriptor order;
* shared-edge neighbor reciprocity and vertical identity;
* rejection of missing or nonreciprocal boundaries;
* explicit evidence that the current cubed-sphere metric is bounded but not
  locally orthogonal and unit-scale everywhere;
* conservative high-latitude and antimeridian coarse-locator coverage;
* rejection of noncanonical overlap neighbors and direct edits; and
* connected-mesh seam and global metric reports, including extraordinary
  cells.

In the current Windows checkout, the machine runtime is Microsoft OpenJDK
21.0.12.1. `:mc1201:compileJava` completes successfully with Gradle 9.4.0 and
Fabric Loom 1.14.10. The focused RFC sources and tests were also compiled with
`javac --release 21` and executed with JUnit Platform Console 1.10.2: 26 tests
started and 26 passed. A complete Gradle `:mc1201:test` run remains
environment-blocked because Gradle's Java HTTPS client cannot complete the
Maven Central TLS handshake while a direct PowerShell request succeeds; no
full Gradle test pass is claimed here.

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

## Phase 2 persistence check

`SphericalLatticePersistence` adds a versioned, deterministic research
snapshot. It stores the grid version, sorted patch descriptors, reciprocal
boundary links, sampled owner decisions, and edited `BlockID`s. Encoding sorts
all records by their canonical keys, so patch/link/edit insertion order cannot
change the bytes. Decoding reconstructs the atlas and recomputes every stored
owner decision before returning the snapshot; unknown patch IDs, duplicate
edits, bad counts, trailing bytes, and a bad magic value are rejected.

The executable check uses two adjacent stereographic patches and edits cells on
both sides of their shared edge. A round trip preserves the IDs, owner results,
vertical `k` values, and reciprocal cross-seam neighbor. This proves only the
prototype's identity and persistence contract. It is not yet an Anvil region
layout, a crash-safe commit protocol, or a Minecraft chunk migration.

The existing `tellus:sphere_face` generator remains unchanged while these
experiments run. Phase 3 still requires extraordinary-vertex runtime behavior
and a real Fabric integration test; the sparse identity-layer entry check is
recorded below.

## Phase 3 entry check: global index plus sparse materialization

`SparseLatticeMaterializer` keeps the complete Atlas as an identity and
neighbor index but stores only requested `BlockID`s. A two-patch fixture has 32
possible cells while the test materializes two point requests, and a seam
neighbor request materializes only the requested destination. Reversing request
order produces the same sorted IDs and deterministic cell state. A seam request
preserves the vertical `k` coordinate and the reciprocal neighbor relation.

This establishes that a global index and demand-driven materialization are
compatible at the identity layer. It does not establish chunk ticketing,
eviction, thread scheduling, disk-backed sparse storage, or a player-visible
Fabric world. Those remain the next integration boundary.

## Review follow-up: seam geometry and direct-ID ownership

The first persistence/materialization fixtures exposed two concrete gaps raised
in the review. Reciprocal integer boundary links alone do not imply a shared
physical edge. For the same adjacent 50 km stereographic patches used above,
sampling the two independently mapped edges gives a maximum endpoint mismatch
of approximately `0.1925 m`; the local chart's `7.7e-6` scale error does not
bound this seam error.

`ClosedQuadSphereEmbedding` now audits a connected closed-cube embedding using
one exact spherical position for every shared vertex and both incident cells'
continuous edge curves. At 65 samples per edge, the subdivision-5 fixture
reports vertex and interior-edge mismatch below `1e-7 m`. This is a hard shared
geometry result for the existing cubed-sphere embedding, not a low-distortion
result: its measured stretch and angle errors remain the baseline in the Phase 1
table.

The same connected mesh now has an executable metric report. For the
subdivision-5, `worldScale=1.0` fixture (150 cells, 300 shared edges), the
nominal face-centre edge is `2,001,511.444 m`; measuring the actual spherical
arcs and spherical cell areas gives:

| connected mesh metric | all cells | cells incident to degree-3 vertices |
|---|---:|---:|
| edge-length scale range | 0.710022–0.996177 | 0.858381–0.858381 |
| maximum right-angle error | 0.523599 rad | 0.523599 rad |
| area scale range | 0.786069–0.991901 | 0.786069–0.786069 |

The extraordinary-cell group contains 24 cells in this resolution. These
values are measured from the seam-constrained embedding itself and therefore
replace neither the isolated-chart estimate nor a production tolerance. They
make the present baseline explicit: the embedding is watertight at shared
edges, but its global metric distortion and extraordinary-vertex behavior are
still far from the RFC's intended weak-distortion target.

The prototype also now defines direct-ID validity as a center-ownership rule:
`Atlas.canonical(BlockID)` maps a cell through its patch cell center and rejects
overlapping non-owner cells as ghosts. Sparse materialization, neighbor lookup,
and persistence edit capture/restore enforce this rule. It prevents duplicate
mutable state under the current model, but it is not yet a complete physical
footprint rule for clipped or transition cells. That rule, a scalable coarse
atlas locator, and 1 m/16x16 chunk persistence remain open before Fabric seam
integration.

## Review follow-up: coarse atlas lookup

`Atlas.owner` now uses a 2-degree latitude/longitude coarse locator, a
conservative spherical-cap filter, and a safe chart-domain check before exact
inverse coordinates. The regression fixture indexes 182 small patches plus a
dateline-crossing patch: a point lookup examines fewer than one tenth of the
registered patches, keeps the same lowest-key owner, resolves the antimeridian,
and rejects an uncovered point without propagating a stereographic antipode
exception. Longitude coverage is derived from the spherical-cap bound
`asin(sin(alpha) / cos(latitude))`; charts that reach a pole conservatively
register every longitude bucket. Regression cases cover the reported 80°N and
80°S false-negative geometry and a near-polar chart.

`Atlas.neighbor` now validates the destination as well as the source. If a
within-patch or cross-patch step resolves to a center-non-owner overlap cell,
the operation fails instead of returning a mutable ghost identity. This keeps
the prototype's chosen neighbor contract explicit; it does not yet define
transition cells or complete physical footprint ownership.

This remains a correctness and candidate-reduction check, not yet a
200,000-patch latency benchmark or a distributed hierarchical index.
