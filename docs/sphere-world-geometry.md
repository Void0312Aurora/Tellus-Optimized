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
geodetic entry points, so a spherical chunk materializer can request external
data without converting its point back into a projected Minecraft block
coordinate. The existing generator still depends on projected block coordinates
for roads and buildings; those consumers remain on the compatibility path until
their geodetic feature queries and seam tests are complete.

The Minecraft engine still stores a flat `ChunkPos`. A complete walkable
spherical world therefore needs a small address/neighbor adapter for chunk
loading, entity movement, collision, and rendering. This class does not claim
to have implemented spherical physics or a globe renderer.
