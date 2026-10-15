# Cube-net generator prototype

Fabric 1.20.1 registers `tellus:cube_net` as a single-Level alternative to the
six-dimension `tellus:sphere_face` setup. The Level keeps ordinary Minecraft
`ChunkPos` storage and uses one fixed planar cube-net view. The terrain sampler
still addresses the six canonical faces internally.

## Create a test datapack

The helper writes a new dimension and refuses to overwrite a non-empty output
directory:

```sh
python3 tools/create_cube_net_datapack.py \
  --output /path/to/test-world/datapacks/cube-net \
  --world tellus:earth --scale 1 --min-y -2032 --height 4064
```

Restart the server after copying the pack. The dimension id is
`tellus:earth_cube_net`. The generator uses the same conformal mapping and
vertical limits as the face generator:

```json
{
  "type": "tellus:cube_net",
  "geometry_version": 2,
  "mapping": "rancic-ccam30-double-v1",
  "excluded_radius_meters": 300000.0,
  "transition_width_meters": 50000.0,
  "biome_source": {"biome": "minecraft:plains"},
  "world_scale": 1.0,
  "sea_level": 64,
  "min_y": -2032,
  "height": 4064
}
```

`CubeNetRuntime` enables same-Level routing only when exactly one loaded Level
uses this generator. The five edges that touch directly in the planar net use
ordinary movement. The other seven physical face pairs preload the target area,
transform horizontal velocity and yaw, probe the translated bounding box, then
complete the move. A corner crossing is held because cube vertices are outside
the ordinary face policy.

This is a wiring and compatibility prototype. The existing datapack helper for
the six-face runtime still creates `tellus:sphere_face` dimensions. Live client
rendering and Distant Horizons behavior at cut seams require a separate runtime
acceptance run.

## Current server evidence

The focused Java suite covers the cube-net codec, gap handling, canonical block
lookup, and the rotated column order on all six faces. A separate development
server run with the synthetic sampler exercised all 24 directed face edges,
14 exact cut-edge starts, three-by-three FULL-chunk preload around each target,
velocity/yaw/pitch and fall-distance preservation, and a blocked landing rollback.
The result was `passed: true`; it used a packet sink rather than a connected
client, so client rendering and third-party renderer behavior remain untested.
