# Spherical face generator prototype

Fabric 1.20.1 registers `tellus:sphere_face`, a demand-driven terrain generator
for one face of the canonical cubed sphere. It converts requested engine chunks
to spherical addresses, samples DEM and cover using geodetic points, and writes
real block palettes, section counts, and heightmaps. The ordinary `tellus:earth`
generator retains its existing generation behavior.

This is a terrain-only prototype. Buildings, roads, inland water levels,
vegetation, real biome classification, cross-face movement, collision frame
changes, and curvature rendering are not wired into this entry point. The
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

`SphereFaceTopology` provides cardinal chunk neighbors across every face edge,
including corner chunks. It does not perform a dimension change or move an
entity. Chunks outside a face's address domain remain empty until the multi-face
loader is connected.

## Dimension configuration

Use a new dimension with a matching height range. For example, the `generator`
part of a dimension JSON can be:

```json
{
  "type": "tellus:sphere_face",
  "geometry_version": 1,
  "biome_source": {"biome": "minecraft:plains"},
  "face": 2,
  "world_scale": 1.0,
  "sea_level": 64,
  "min_y": -2032,
  "height": 4064
}
```

The enclosing dimension type must have the same `min_y` and `height`. This
configuration is for an explicit new dimension; there is no world-preset button
or automatic save conversion at this stage.

## Validation

```sh
./gradlew -PtellusTargets=mc1201 :mc1201:build --offline --no-daemon
```

The final build completed with 627 passing tests, three skips, and no failures
across the ordinary and parallel-fill test JVMs. The installable Fabric jar is
produced under `mc1201/build/libs/`.

Tests exercise real `ProtoChunk` output on all six faces, agreement between
generated blocks and every heightmap/base-column query, explicit water levels,
missing-data retry, failure before writes, codec persistence, and chart error
tolerance at scales 1, 30, and 1000. Topology tests cover all face edges, corner
reciprocity, and odd face sizes. Those fields and source-cache fixtures are
synthetic and make no real-scene quality claim.

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
establish a speedup, global dataset coverage, building quality, or gameplay at
face seams. Rendering was not part of the run.
