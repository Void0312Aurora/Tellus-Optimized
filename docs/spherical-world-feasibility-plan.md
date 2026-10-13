# Spherical world feasibility, design, and test plan

This document records the feasibility boundary for the twelve spherical-world
requirements and defines the next research and prototype gates. It is a design
decision document, not a claim that the current `tellus:sphere_face` prototype
is player-ready.

## Decision summary

There is an engineering-feasible design if the requirements are interpreted as
follows:

* the sphere is globally closed and every ordinary playable cell has one
  canonical identity;
* ordinary regions use a logically rectangular, six-neighbor block lattice;
* local metric error is bounded by a stated tolerance rather than being zero;
* a small, explicitly mapped set of singular caps or transition cells is
  excluded from ordinary gameplay, or is handled by a separate runtime policy;
* the client and collision system consume spherical chart/frame data instead of
  treating `ChunkPos` as a global flat metric.

There is no design that simultaneously makes a closed sphere globally
orthogonal, entirely degree-four, and free of exceptional vertices while also
retaining a single non-overlapping quadrilateral lattice. This is a topology
constraint, not an implementation defect. A closed cubed sphere therefore has
eight degree-three corner vertices; the current prototype measures the resulting
extraordinary-cell group directly.

The recommended direction is a **canonical capped quadrilateral atlas**:

1. Keep one versioned global cell identity and one authoritative owner for every
   ordinary cell.
2. Use a seam-constrained cubed-sphere or another closed quadrilateral mesh for
   the ordinary area. Optimize its embedding for edge and angle error under hard
   shared-boundary constraints.
3. Place the eight exceptional caps in designated low-value regions and measure
   the resulting loss of accessible land, roads, buildings, and population. Do
   not silently route players through an ordinary six-neighbor contract there.
4. Represent the cap boundary with explicit transition metadata. A transition
   must preserve position, tangent velocity, collision ownership, and stable
   block identity, even when its local cell shape is not an ordinary cube.
5. Keep storage demand-driven and chunk-shaped, but add a spherical local frame,
   a client render path, and a collision path. Vanilla flat rendering and flat
   collision cannot provide seamless spherical gameplay by themselves.

This design can satisfy the stated requirements under the allowed “normal
accessible area” and “controlled distortion” wording. It cannot satisfy a
stronger interpretation that forbids every singular or nonstandard cell on the
entire closed sphere.

## Evidence and current baseline

The cubed-sphere literature treats uniformity, orthogonality, and rectangular
logic as a tradeoff. Putman and Lin compare gnomonic, conformal, and numerically
optimized cubed-sphere grids and report that slight non-orthogonality can be a
better overall compromise than maximizing orthogonality alone:

* [Putman and Lin, *Finite-volume transport on various cubed-sphere grids*](https://science.gsfc.nasa.gov/610.3/documents/Putman_sdarticle.pdf)

HEALPix demonstrates a different useful property: hierarchical spherical
indexing and equal-area quadrilateral elements, while retaining special polar
regions. It is a strong index/data-layout comparator, not a drop-in Minecraft
block lattice:

* [Górski et al., *HEALPix: A Framework for High-Resolution Discretization and Fast Analysis of Data Distributed on the Sphere*](https://iopscience.iop.org/0004-637X/622/2/759/pdf/61342.web.pdf)

The geodesic-mesh literature also describes the cube-sphere as logically
Cartesian on each face with special treatment near the eight cube corners:

* [Sovinec et al., *Technologies for supporting high-order geodesic mesh frameworks for computational astrophysics and space sciences*](https://link.springer.com/article/10.1186/s40668-020-00033-7)

The current repository evidence is consistent with those limits:

* The seam-constrained prototype has shared-edge mismatch below `1e-7 m`, but
  the equiangular baseline has edge scale `0.710022–0.996177`, area scale
  `0.786069–0.991901`, and maximum right-angle error `0.523599 rad`.
* The spherified candidate improves edge scale to `0.736749–1.094868` and area
  scale to `0.815917–0.879204`, but does not remove the angle defect or the
  extraordinary vertices.
* The existing Fabric path proves demand-driven terrain generation and face
  routing, but still uses flat Minecraft collision/input/rendering and has no
  full-scale vertical storage or client-side seamless globe view.

See [`spherical-lattice-rfc-validation.md`](spherical-lattice-rfc-validation.md),
[`sphere-world-geometry.md`](sphere-world-geometry.md), and
[`sphere-face-generator.md`](sphere-face-generator.md) for the measured local
results and their limits.

## Candidate designs

| Candidate | Strength | Failure against the requirements | Role |
|---|---|---|---|
| Seam-constrained cubed sphere, capped corners | Rectangular ordinary patches, one closed topology, easy chunk mapping | Exceptional caps and bounded metric distortion remain | **Recommended baseline** |
| Conformal expanded cube, capped corners | Interior coordinate lines are orthogonal and the eight singular locations are explicit | The corners are still singular; scale is nonuniform and clusters near the corners; deleting only the mathematical points is insufficient | **Priority research candidate** |
| Optimized quasi-orthogonal cubed sphere | Can reduce angle and length error together | Requires numerical mesh generation and a stable versioned embedding | Main geometry experiment |
| HEALPix-like hierarchical quads | Equal-area indexing and strong hierarchy | Polar topology and cell shape do not directly provide ordinary Minecraft cubes | Index/data-layout comparator |
| Yin-Yang or overlapping charts | Each chart can avoid a local pole singularity | Overlap creates duplicate authority unless a canonical ownership layer is added | Runtime/chart comparator |
| Icosahedral triangle/hex dual | Better global uniformity and small local distortion | Breaks the standard six-neighbor cube contract | Rejected for the primary path; useful lower-bound comparator |
| Cartesian voxel globe shell | Familiar cubes and collision | Stair-step surface, poor geography fidelity, and global storage blow-up | Negative control |

The experiment must compare candidates at the same physical block scale and
the same requested region. A visually attractive low-resolution mesh is not
evidence for scale-1 playability.

## Candidate eight-vertex placement

The attached proposal supplies these approximate locations:

| id | latitude | longitude | nearest land reported in the proposal |
|---:|---:|---:|---:|
| 1 | 2.6°N | 21.7°W | 1,117 km |
| 2 | 35.9°S | 41.9°E | 1,185 km |
| 3 | 70.6°N | 6.8°E | 360 km |
| 4 | 18.2°N | 89.8°E | 386 km |
| 5 | 18.2°S | 90.2°W | 1,553 km |
| 6 | 70.6°S | 173.2°W | 580 km |
| 7 | 35.9°N | 138.1°W | 1,293 km |
| 8 | 2.6°S | 158.3°E | 488 km |

Using the rounded coordinates, the antipodal pairs are approximately
`(1,8)`, `(2,7)`, `(3,6)`, and `(4,5)`. The absolute error of the other
pairwise dot products from `1/3` in magnitude is about `0.0011` at worst. This
is strong evidence that the table is a rotated cube orientation, but it is not
yet a geospatial coverage result: the reported nearest-land distances need to
be recomputed against the same coastline, bathymetry, transport, population,
road, and building datasets used by the generator.

The orientation must therefore be stored as a grid-versioned rotation matrix,
not as rounded latitude/longitude text. The coverage study must search rotations
and cap radii, then score the excluded regions by reachable land, shipping and
air routes, population, roads, buildings, and terrain value. Nearest land alone
does not prove that a cap is unreachable in play.

## What “remove the eight vertices” means

For a conformal expanded cube, the interior coordinate lines are orthogonal, but
the eight cube corners remain coordinate singularities. The published MITgcm
implementation explicitly reports nonorthogonality at those corners and handles
the surrounding finite-volume stencil specially:

* [Adcroft et al., *Implementation of an Atmosphere–Ocean General Circulation Model on the Expanded Spherical Cube*](https://journals.ametsoc.org/view/journals/mwre/132/12/mwr2823.1.pdf)
* [MITgcm expanded spherical cube overview](https://mitgcm.org/projects/cubedsphere)

The Minecraft design cannot copy the finite-volume workaround because the
player needs a discrete block ownership and collision contract. A valid removal
policy must be one of:

1. **Excluded cap:** no ordinary blocks are generated inside a spherical cap;
   reaching its boundary is an explicit out-of-world or barrier event.
2. **Transition ring:** a finite ring of special cells surrounds the cap and
   owns movement, collision, and persistence explicitly.
3. **Special-cell cap:** the cap remains traversable but abandons the ordinary
   six-neighbor cube contract and is handled by a separate cell topology.

There is a fourth research option that is closer to the spherical-grid
literature: **incision plus overset transition patches**. Purser and Rančić's
NOAA grid note describes cuts that transect each polyhedral vertex and local
overlapping patches that remove the remaining corner discontinuity from the
numerical operator region. This is not the same as deleting a point from a
closed mesh. For Tellus, an overset cell may have multiple chart views but must
still resolve to one canonical `BlockID`; aliases can be read-only views and
cannot own independent edits. The proposal is therefore compatible with the
global-index requirement only if the ownership layer is authoritative before
the overset layer.

Reference: [Purser and Rančić, NOAA Office Note 467](https://repository.library.noaa.gov/view/noaa/6967/noaa_6967_DS1.pdf).

Deleting only the eight zero-area mathematical points is not enough. Conformal
maps preserve angles in the interior, but their scale factor is not constant;
published expanded-cube results report resolution clustering near the corners.
The cap radius must be selected by a measured minimum-cell-scale and collision
condition, not by a visual distance from the vertex.

The current repository does not yet implement the Rancic/McGregor conformal
map. It only contains the seam-constrained equiangular and spherified
embeddings plus the candidate-vertex audit. This distinction matters because a
conformal map's interior angle property cannot be inferred from the existing
spherified metrics.

The first exception-boundary gate is now prototyped in
`SphericalIncisionPolicy`. It classifies every cell against the eight
degree-three vertices and rejects ordinary admission/traversal inside a
conservative cap-plus-transition bound. Classification keeps the existing
topology IDs. A conformal candidate can reuse this policy after providing a
valid whole-cell enclosure, but the policy does not implement overset identity,
transition collision, client rendering, or a Fabric materializer integration.

[The first baseline sweep](research/spherical-incision-baseline.md) measures
both existing embeddings at subdivisions 64 and 128, with 0/100/300/360 km
caps and a 50 km transition band. At subdivision 128 and a 300 km cap, ordinary
corner-polygon coverage is about 99.2%, but maximum ordinary angle error is
still 27.51 degrees (equiangular) or 23.98 degrees (spherified). This supports
keeping the conformal map as a separate priority: cutting out the corners of
the current maps does not by itself make the remaining lattice orthogonal.

The first cap-radius sweep should measure, for each candidate radius:

* minimum and percentile horizontal cell scale outside the cap;
* maximum angle and aspect-ratio error outside the cap;
* cap and transition-ring area;
* coastline, land, road, building, transport-route, and population loss;
* player path reachability and boundary behavior;
* canonical-ID and persistence behavior at the cap boundary.

The result should be a Pareto table of `(cap radius, geometry quality, excluded
value)`. A radius is acceptable only if it passes both the geometry threshold and
the coverage budget.

As a scale check only, eight equal spherical caps of radius 360 km would cover
about 3.26 million km², or 0.64% of a spherical Earth. This is not an acceptance
result: it assumes non-overlapping circular caps, ignores ocean travel and air
routes, and uses the proposal's rounded nearest-land number rather than a live
coastline calculation. It is useful for sizing the coverage experiment, not for
declaring the locations safe.

## Proposed runtime architecture

### 1. Canonical identity layer

Use a versioned key such as:

`BlockID = (GridVersion, PatchID, i, j, k)`

where `(i,j)` is an ordinary surface lattice coordinate and `k` is the signed
radial block index. Ownership is decided once from the immutable embedding and
stored by canonical key. Every mutation, neighbor lookup, cache entry, and
persistence record must use that owner; chart overlap must never create a second
authoritative state.

### 2. Ordinary patch layer

Materialize ordinary cells into normal chunk-sized storage and expose the six
Minecraft directions within the patch. A patch boundary is a transport/storage
boundary, not a second coordinate system. Face and patch transforms must be
generated from one shared vertex/edge map and versioned with the grid.

### 3. Exceptional-cap and transition layer

Each singular cap has an explicit state: `EXCLUDED`, `TRANSITION`, or
`SPECIAL_CELL`. The chosen state is data, not an accidental consequence of a
chart inverse failure. For `TRANSITION`, define a finite-state handoff with:

* source and destination canonical IDs;
* spherical position and tangent-frame transform;
* velocity, yaw, and collision ownership transfer;
* chunk tickets and rollback on failed destination load;
* persistence rules for edits that touch the transition boundary.

### 4. Geodetic content layer

Sample DEM, land cover, roads, water, and buildings from geodetic coordinates.
Keep source projection details inside the data readers. Add polar datasets before
claiming global content: the current Web-Mercator DEM/OSM path stops near
`±85.05°`, and WorldCover has a narrower coverage boundary.

### 5. Client and physics layer

Keep the authoritative server identity in the canonical layer, but render and
collide using a local tangent frame that is periodically rebased. The client
must receive enough neighboring-face geometry to avoid a visible seam. Collision
must use the same frame and embedding as movement validation; a dimension
teleport is an interim routing mechanism, not the final continuity solution.

### 6. Elevation layer

Reserve a signed scale-1 radial interval covering at least `[-11000 m, +8000 m]`
relative to the reference surface, with explicit sea-level and datum metadata.
The height range is part of the dimension/storage contract and must be tested at
both endpoints and at chunk-section boundaries.

## Research and experiment sequence

### R0 — Freeze the acceptance contract

Before selecting a mesh, record numeric thresholds for:

* maximum and percentile angle error in ordinary playable cells;
* edge-length and cell-area distortion;
* position and tangent mismatch across seams;
* the fraction of land, population, roads, buildings, and reachable surface
  excluded by exceptional caps;
* movement/collision continuity error and allowed cold-load stall;
* scale-1 generation latency, cache memory, and persistence throughput.

Without these thresholds, “尽可能降低畸变” cannot decide between candidates.

### R1 — Geometry and topology study

Implement one common metric harness for:

1. current equiangular cubed sphere;
2. current spherified cube;
3. one numerically optimized quasi-orthogonal cubed sphere;
4. one hierarchical equal-area comparator;
5. one triangular/hex negative control.

For each candidate, report seam `C0` position error, tangent/frame error, edge
scale, angle error, area scale, valence distribution, cap area, and lookup cost.
The result must be reproducible from a grid version and a fixed seed.

### R2 — Coverage placement study

The first fixed-orientation study is executable; see
[the eight-vertex geographic validation](research/eight-vertex-geographic-validation.md).
It checks GSHHG full/high coastlines, ideal caps, actual n=128 quarantine, and a
bounded OSM probe. The fitted western-Pacific vertex is only about 238 km from
represented land. The 300+50 km configuration affects observed settlements,
and the conservative coarse-cell mask also produces disconnected exclusion
fragments. A rotation search, tighter cell bounds, current global feature
coverage, and approved loss/scale budgets remain required for R2 acceptance.

Use the real geographic masks already used by Tellus to place exceptional caps.
Compute loss over land area, population, roads, buildings, and high-value
terrain. Test multiple cube orientations and rotations. The output is a
coverage Pareto frontier, not a visual choice.

### R3 — Identity, generation, and persistence

Stress deterministic ownership under reversed insertion order, overlapping chart
requests, concurrent generation, eviction, restart, and failed writes. Then map
ordinary cells to chunk sections and prove that an edited physical block has one
stable authority after reload.

The first storage-layer subgate is now implemented in
`SparseLatticeMaterializer`: explicit edits are a durable overlay over the
demand-driven cache, eviction does not discard an edit, restored edits are
validated as a batch, and `chunkOf`/`sectionOf` provide deterministic storage
coordinates using floor division for negative radial indices. The focused tests
cover concurrent first requests, edit eviction/reload, cross-seam neighbors,
and chunk-section identity. This is still a standalone RFC layer; it is not yet
the Fabric chunk or Anvil persistence path.

### R4 — Seam gameplay spike

Build a small real Fabric test world with two ordinary patches and one transition
boundary. Test walking, sprinting, jumping, falling, riding, block placement,
breaking, entity collision, chunk unload/reload, disconnect/reconnect, and
server restart while crossing the boundary. Capture both server traces and a
client video or frame dump. Headless transfer alone is insufficient.

The Fabric 1.20.1 routing prototype now records the complete source state before
holding a player at an edge. If destination chunk loading fails or times out, it
restores source position, yaw/pitch, velocity, and fall distance before applying
the retry cooldown. This closes the failure rollback subgate; it does not yet
prove client-visible seamless collision or walking because the implementation
still routes between face dimensions.

### R5 — Global-content and scale-1 gate

Run sampled locations from equatorial, mid-latitude, polar, antimeridian, deep
ocean, high mountain, and deep basin regions. Verify DEM/OSM availability,
vertical endpoints, terrain/materialization, cache behavior, and fallback policy.
Do not turn missing polar data into sea level or silently clip elevation.

## Acceptance gates

The primary design may advance only if all of these are true:

1. **Topology:** no ordinary reachable cell is incident to a singular vertex;
   singular-cap placement and exclusion are explicit and reproducible.
2. **Geometry:** all ordinary cells pass the agreed angle, edge, area, and seam
   thresholds at the target scale.
3. **Identity:** every tested physical block has exactly one authoritative
   `BlockID` after generation, eviction, edit, restart, and reload.
4. **Continuity:** cross-boundary position, velocity, collision, and block-edit
   traces stay within the stated tolerance without a visible or logical seam.
5. **Coverage:** the measured excluded region is within the approved low-value
   budget and includes no protected test geography.
6. **Data:** all claimed global regions have an actual source or an explicit
   unavailable status; source coverage is not inferred from clipping.
7. **Scale:** the vertical interval and chunk-section layout cover `-11 km` to
   `+8 km` at scale 1.
8. **Performance:** generation, lookup, memory, and persistence stay below the
   recorded budgets under concurrent demand.

Failure of a gate should reject or revise the candidate; it should not be
hidden by changing the definition of a normal region after measurements are
available.

## Immediate next work

The standalone conformal candidate and its capped-mesh/local-derivative
experiments are now available. See
[the conformal validation record](research/conformal-cube-validation.md).
This completes the numerical reference stage below; the eight-point geographic
rotation, loss budget, versioned persistence, and Fabric collision gates remain
open. The recorded thresholds are provisional numerical checks, not agreed
gameplay acceptance thresholds.

The current RFC should proceed in this order:

1. Add the acceptance-threshold table and grid-version metadata to the standalone
   metric harness.
2. Completed as a standalone reference: reproduce the conformal expanded-cube map from the Rancic/McGregor family in
   a standalone evaluator, with a hard shared-boundary map and a separate
   singular-corner report.
3. Reproduce the incision/overset construction as a second candidate, including
   canonical-owner and read-only-alias tests.
4. Sweep finite exclusion/transition cap radii around the eight-point rotation
   and compare them with the current equiangular and spherified candidates.
5. Add an optimized quasi-orthogonal candidate with hard shared-boundary
   constraints, keeping the spherified cube as the baseline.
6. Add singular-cap placement and geographic coverage scoring before any new
   player-facing runtime claim.
7. Completed the standalone identity subgate: chunk/section mapping, explicit
   edit overlay across eviction/reload, and concurrent request order are now
   tested. The remaining part is wiring the same contract into real Fabric
   chunk-section and restart persistence.
8. Implement the smallest client-visible seam/collision spike, then repeat the
   geometry and performance measurements at scale 1.

Until those gates pass, the correct status is **research prototype with a
plausible architecture**, not “requirements fulfilled.”
