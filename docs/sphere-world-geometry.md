# Spherical world geometry

`SphereWorldModel` is the new canonical geometry kernel for the global-world
design. It is intentionally separate from the existing `GlobalMapModel`, which
still serves the current Tellus/Mercator generator path.

**Runtime status:** the Fabric `tellus:sphere_face` prototype now uses the
versioned conformal cubed-sphere mapping
`rancic-ccam30-double-v1`. The previous equiangular path remains available only
for comparison in the standalone geometry harness.

The spherical model has four rules:

1. The reference surface is a sphere described by geodetic latitude,
   longitude, and elevation. It is not a world-wide Web-Mercator plane.
2. The surface is indexed by a six-face conformal cubed sphere. A
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

The first integration added the geometry kernel and mathematical tests. The
live spherical generator now uses the versioned conformal map. The terrain and
land-cover readers now also have
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

Fabric 1.20.1 now also connects six explicitly grouped face dimensions with
`SphereFaceRuntime`. Continuous chart transitions preserve fractional spherical
position and transport the coordinate velocity/yaw using an analytic Jacobian.
Destination chunks load asynchronously before an unmounted player changes
dimension. A headless real-data server check has exercised the engine player
transfer in both directions, without a connected client.

The server now sends one versioned `sphere_frame` payload when a player joins
or completes a face transfer. It carries the active planet, conformal mapping
ID, face index, face-local position, world scale, yaw, and a monotonic revision.
The client keeps this as the single spherical-frame snapshot and ignores older
revisions. This closes the state-contract gap for the next movement and
rendering adapters; it does not yet make the vanilla client renderer or
prediction spherical.

The client tick now refreshes the snapshot's local pose from the rendered
player while retaining the server-authoritative frame identity. A shared
`SphereClientMotion` preview uses that snapshot and the same conformal
`SphereSurfaceMotion` seam transform as the server. It reports an ordinary
face step, cross-face candidate, quarantine/collision stop, or invalid mapping
without changing dimensions; the server remains the authority for the actual
transfer.

The common geometry kernel now exposes a validated east/north/up tangent-frame
conversion contract for the client and the within-face collision work that
remains. The Minecraft
engine still stores a flat `ChunkPos`; local collision and input physics inside
a face still use Minecraft's flat metric, and the renderer does not display an
adjacent face before a dimension change. The 1.20.1 Fabric path now intercepts a
server player's proposed move when it would leave the continued face chart, so
the player is held or routed before vanilla commits an out-of-domain position.
This is an edge-routing guard, not a replacement for spherical body collision,
client movement validation or globe rendering. Vertical storage covering the
complete scale-1 elevation range also remains open. The conformal runtime now
fails closed around the eight corner singularities using the configured excluded
cap and transition band; those transition regions remain unavailable until their
exceptional terrain and collision handler exists. `SphereSurfaceMotion` is now
the shared server seam decision and Jacobian path and is covered by geometry
tests; it is not yet the authority for all Fabric entity collision. The current
runtime can pause at an edge while a cold destination loads. After the target
chunks finish loading, the server now probes the destination player's vanilla
bounding box with the target level's collision query before committing the
dimension transfer; a blocked landing rolls back to the source.
The client mixin now applies the same quarantine result to local prediction:
only blocked steps are cancelled; cross-face candidates continue to the server
route. The client preview also accepts a real `ClientLevel.noCollision` probe
and classifies ordinary collision separately, leaving Minecraft's vanilla
sliding and step solver authoritative. This is a prediction safety gate, not
yet full client-side body collision or seamless movement.
