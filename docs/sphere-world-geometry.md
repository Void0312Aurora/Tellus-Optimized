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

This first integration adds the geometry kernel and mathematical tests without
switching the live generator. The existing generator still depends on
projected block coordinates for roads, buildings, and terrain sources. The next
integration step is to give the terrain sampler a `SphereChunkAddress` entry
point, then convert its requested geodetic samples to the existing DEM/OSM
readers. The old projection path must remain available until those consumers
have been migrated and seam tests pass.

The Minecraft engine still stores a flat `ChunkPos`. A complete walkable
spherical world therefore needs a small address/neighbor adapter for chunk
loading, entity movement, collision, and rendering. This class does not claim
to have implemented spherical physics or a globe renderer.
