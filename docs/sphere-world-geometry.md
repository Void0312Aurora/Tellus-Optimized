# Spherical world geometry

`SphereWorldModel` is the new canonical geometry kernel for the global-world
design. It is intentionally separate from the existing `GlobalMapModel`, which
still serves the current Tellus/Mercator generator path.

The spherical model has four rules:

1. The reference surface is a sphere described by geodetic latitude,
   longitude, and elevation. It is not a world-wide Web-Mercator plane.
2. The surface is indexed by a six-face equiangular cubed sphere. A
   `SphereChunkAddress` contains a face and non-negative local chunk indices;
   face-edge transforms are represented by the shared spherical coordinates,
   so adjacent faces have no geometric gap.
3. A chart is a fixed block region on one face. Its center is an exact point on
   the sphere. Local east/north offsets are expanded from that center with a
   configurable truncated spherical exponential map.
4. DEM, OSM, land-cover, and building readers receive geodetic coordinates.
   Their use of Web-Mercator tile numbers remains an external data-format
   detail and does not define the Minecraft world geometry.

The model is lazy by construction: a face, chart, or chunk is materialized only
when a caller requests its address. `chartHalfDiagonalMeters` and
`recommendedExpansionOrder` provide the local footprint and the truncation
order needed to meet the configured error tolerance.

The first integration added the geometry kernel and mathematical tests without
switching the live generator. The terrain and land-cover readers now also have
geodetic entry points, and building/road/water readers accept geodetic query
bounds split at the antimeridian. `SphereChunkSampler` requests one chunk's
terrain and vector data without converting points back into projected Minecraft
block coordinates. Its prepared chart selects at least the configured expansion
order and raises it when the chart footprint needs more terms to satisfy the
error tolerance. This is exercised at world scales 1, 30, and 1000.

Fabric 1.20.1 now registers `SphereFaceChunkGenerator` as `tellus:sphere_face`.
It fills real Minecraft chunks with radial terrain columns and primes their
heightmaps. `SphereFaceTopology` maps centered engine coordinates to canonical
face addresses and resolves cardinal neighbors across six faces. Edge tests
include corners and check that every transition has exactly one return
direction. The prototype and its limits are described in
[`sphere-face-generator.md`](sphere-face-generator.md).

The existing `tellus:earth` generator still uses projected block coordinates.
The face prototype is selected explicitly by a dimension configuration; it does
not change existing Earth saves. Source-cache tests use synthetic fixtures, and
a separate headless scale-1 run has now checked real DEM generation and saving.

The Minecraft engine still stores a flat `ChunkPos`. A complete walkable
spherical world still needs the multi-face chunk loader, entity movement hook,
collision frame changes, and globe rendering. The address adapter alone does
not make the face edges walkable or render curvature.
