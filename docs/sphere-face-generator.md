# Spherical face generator prototype

Fabric 1.20.1 registers `tellus:sphere_face`, a demand-driven terrain generator
for one face of the canonical cubed sphere. It converts requested engine chunks
to spherical addresses, samples DEM and cover using geodetic points, and writes
real block palettes, section counts, and heightmaps. The ordinary `tellus:earth`
generator retains its existing generation behavior.

The current Fabric entry point uses the **conformal** cubed-sphere mapping
`rancic-ccam30-double-v1`. The generator codec records this mapping and rejects
the previous equiangular geometry version instead of silently interpreting an
old save with different coordinates.

This is a terrain-only prototype. Buildings, roads, inland water levels,
vegetation, real biome classification, collision frame changes, and curvature
rendering are not wired into this entry point. The
prototype uses a fixed biome and a basic cover palette. It is not a generation
quality comparison or a substitute for the full Tellus building/road pipeline.

## Geometry and storage

Each instance owns one face and a fixed world scale. Engine coordinates are
centered on that face. The face address and geometry version are persisted with
the generator codec; Minecraft's local `ChunkPos` is storage addressing, not an
authoritative Mercator coordinate.

Y stores radial elevation using `sea_level + elevation_metres / world_scale`,
rounded away from zero at the block boundary. It receives no latitude-dependent
Mercator height correction. The storage columns are rendered by the existing
flat Minecraft renderer until chart-aware rendering is implemented.

One immutable 256-column terrain grid is shared by generation, height queries,
and base-column queries. A per-generator cache is bounded to 128 grids and
coalesces concurrent requests for a grid. Missing elevations are not cached, so
a request can succeed after data becomes available. There is no globe-wide
pre-generation or global chart cache.

All 256 columns are checked before any blocks are written. Missing elevations
or heights outside the dimension range fail explicitly. The prototype does not
clip mountains to the ceiling or convert missing data to sea level. A full
scale-1 elevation range still needs the later vertical storage/frame work;
the currently supported dimension range is [-2032, 2032).

`SphereFaceTopology` provides cardinal chunk neighbors and continuous chart
changes across every face edge, including corners. `SphereFaceRuntime` connects
six explicitly grouped dimensions and moves players between them after the
destination chunks are ready. Outside-face storage chunks remain empty; ordinary
Minecraft rendering does not draw the neighboring face through the seam.
The shared `SphereSurfaceMotion` adapter now combines a collision probe with the
same conformal seam transform. In the 1.20.1 Fabric target, a server-side entity
move hook uses the face chart guard before vanilla commits a move outside the
face. Vanilla collision remains authoritative within a face; the adapter is not
yet a complete replacement for body collision or client movement prediction.
The server sends a `sphere_frame` payload on join and after a successful face
transfer. Its mapping ID, face, local position, scale, yaw, and revision form
the client-side authority shared by the movement and rendering work that
follows; receiving and caching that payload is implemented, while those client
systems still use vanilla behavior.
The client updates only the local pose each tick and uses the shared
`SphereClientMotion` preview for the same-face or cross-face result. It does not
perform a client-side dimension change, so server transfer remains the single
authority while prediction and collision adapters are added. The client mixin
cancels only a preview classified as blocked by the quarantine/collision probe;
ordinary collision is reported separately and remains with Minecraft's vanilla
sliding solver, while cross-face candidates reach the server route.
After the destination chunks are ready, the server also checks the translated
player bounding box against the target level before teleporting; a blocked
landing follows the existing transfer rollback path.

## Dimension configuration

Use a new dimension with a matching height range. For example, the `generator`
part of a dimension JSON can be:

```json
{
  "type": "tellus:sphere_face",
  "geometry_version": 2,
  "mapping": "rancic-ccam30-double-v1",
  "excluded_radius_meters": 300000.0,
  "transition_width_meters": 50000.0,
  "biome_source": {"biome": "minecraft:plains"},
  "face": 2,
  "planet": "tellus:earth",
  "world_scale": 1.0,
  "sea_level": 64,
  "min_y": -2032,
  "height": 4064
}
```

The enclosing dimension type must have the same `min_y` and `height`. This
configuration is for an explicit new dimension; there is no world-preset button
or automatic save conversion at this stage. The two radius fields define the
eight-corner quarantine. Chunks whose conservative footprint intersects either
the excluded cap or its transition band fail closed until an exceptional-region
terrain and collision handler is added. All six faces in one planet must use
the same two values. With the default values, the ideal eight-cap fractions are
0.4434% for the excluded caps and 0.6034% for the cap plus transition band.
The conservative n=128 conformal mesh study measured about 0.7559% for the
whole-cell quarantined area; the latter is the current planning figure until a
production cell-bound proof replaces the study enclosure.

## Six-face runtime

Create a new datapack directory in a test save, then restart the server:

```sh
python3 tools/create_sphere_datapack.py \
  --output /path/to/test-world/datapacks/sphere \
  --planet tellus:earth --scale 1 --min-y -2032 --height 4064
```

The tool writes six dimensions and one matching dimension type, and refuses to
overwrite an existing nonempty directory. All six generators must have the same
explicit `planet`, scale, sea level and vertical range, exactly one of each face
0--5, and dimension types with matching heights. An incomplete, duplicate or
inconsistent face set is logged and routing for that planet is disabled. An
omitted `planet` leaves a standalone face. Different planet ids do not route
players into each other's dimensions.

In game, an operator can check and enter the planet with:

```text
/tellus sphere status
/tellus sphere enter tellus:earth 45 0
```

At a face edge, continuous floating-point coordinates are re-owned by the
adjacent face without rounding to a block center. The analytic differential of
the chart change transports the horizontal velocity and yaw. Y, vertical
velocity, pitch and accumulated fall distance are preserved. The horizontal
coordinate norm can change because cubed-sphere axes have position-dependent
scale and are not everywhere orthogonal; the transported quantity is the same
spherical tangent vector to first order.

The runtime holds an unmounted player half a block inside the source edge while
loading the destination and its valid immediate neighbors to `FULL`. Its custom
region ticket is released on completion, cancellation, disconnect, server stop
or timeout. A short vanilla post-teleport ticket bridges the handoff to ordinary
player chunk tracking. The 1.20.1 off-thread `getChunkFuture` branch is used to
avoid that API's main-thread `managedBlock` wait. Failed loads leave the player
at the source edge with a message and a retry delay. Mounted entities are not
transported by this prototype.

This is a routing prototype, not seamless spherical gameplay. A cold destination
can still take seconds and the player pauses while it loads. Local collision,
input physics, renderer and the flat coordinate metric within a face remain
Minecraft's existing implementations. Cross-edge body collision, seamless
adjacent-face visibility, horizon curvature and the full scale-1 vertical range
remain work for later stages.

## Validation

```sh
./gradlew -PtellusTargets=mc1201 :mc1201:build --offline --no-daemon
```

The earlier runtime build recorded 698 tests, two skips, and no failures.
The 2026-10-15 follow-up `:mc1201:check --offline --no-daemon --max-workers=2`
completed successfully; the ordinary and parallel-fill reports contain 719
tests, three skips, and no failures or errors, including the cube-net tests.
The installable Fabric jar is produced by `:mc1201:build` under
`mc1201/build/libs/`; `check` alone does not establish packaging acceptance.

Tests exercise real `ProtoChunk` output on all six faces, agreement between
generated blocks and every heightmap/base-column query, explicit water levels,
missing-data retry, failure before writes, codec persistence, and chart error
tolerance at scales 1, 30, and 1000. Topology tests cover all face edges, corner
reciprocity, and odd face sizes. Those fields and source-cache fixtures are
synthetic and make no real-scene quality claim.

A separate scale-1 runtime smoke run loaded all six dimensions and exercised
the production transfer path twice near 45 N, 0 E: face 0 -> face 2 -> face 0.
It used a real engine `ServerPlayer` with a packet sink, **no connected client**,
real data sources and real `FULL` chunks. Both passes preserved fractional
position, radial height, yaw, pitch, velocity and fall distance. Numerical
spherical position residuals were below 2e-9 m; this is floating-point mapping
agreement, not the accuracy of the DEM or terrain.

The initial route calls took 2.323 ms and 0.320 ms. Loading lasted 267 and 138
server ticks while tick processing continued. Those times are a single smoke
sample and make no speedup claim. Reading `level.dat` confirmed all six persisted
face/planet/scale configurations; both saved destination chunks were
`minecraft:full` with bedrock, stone, dirt and grass palettes. Client movement
acknowledgements, walking, target collision and visuals were not tested by this
historical real-data run.

A later isolated synthetic-fixture run used the same six-face datapack and the
production Fabric runtime with `tellus.sphere.syntheticSampler=true`. It passed
the face-0 to face-2 transfer, then placed a solid block at Y=2000 on the
reverse destination. The target FULL-chunk AABB probe rejected that landing and
restored the source face, Y, velocity and fall distance
(`collisionRollbackPassed=true`). The fixture is evidence for routing and rollback wiring only; it is
not a DEM, land-cover or visual-quality validation.

The follow-up `conformal-client-frame-r5` run also passed after the frame
payload integration (`passed=true`, `collisionRollbackPassed=true`). It still
uses the packet-sink fixture without a connected client, so it verifies that
the server transfer and rollback path remains healthy, not client rendering or
prediction. Payload encoding, malformed-frame rejection, and stale-revision
handling are covered by the module tests.

Local artifacts are `tools/benchmark-results/sphere-runtime-scale1-checked.json`,
its `.server.log`, and `sphere-runtime-scale1-saved-check.json`. The checked run
is under `sphere-runtime-smoke-x_2vnc4l/checked-run`; the earlier run was stopped
after a command-parser error, which was fixed before the passing run. Artifacts
are ignored by Git. The runner records the base revision and dirty-worktree
status because the tests preceded committing this change.

A separate isolated headless server run requested one `FULL` chunk near
46.02176 N, 7.74811 E using existing DEM caches, face 2 and scale 1. The engine
coordinates were chunk (303841, -51518), with an 8 GiB server heap. The first
run completed in 2591.121 ms, excluding its 81.712 ms block-state hash pass.
Its state hash was `8922976138508213083`. Reading the saved region confirmed
`minecraft:full`, terrain tops Y=1671--1673, bedrock/stone palettes, and a
persisted `tellus:sphere_face` configuration in `level.dat`.

After adding the bounded grid cache and final topology adapter, a fresh isolated
run against the same copied input cache completed in 2477.213 ms and produced
the same target block-state hash. This is a repeatability check, not a speedup
measurement. The test was run before committing; its JSON explicitly records
the base Git revision and a dirty worktree.

This proves the source-to-block and save path for that location. It does not
establish a speedup, global dataset coverage, building quality, or client gameplay
at face seams. Rendering was not part of either run.
