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
  "geometry_version": 3,
  "mapping": "rancic-ccam30-double-v1",
  "excluded_radius_meters": 300000.0,
  "transition_width_meters": 50000.0,
  "biome_source": {"biome": "minecraft:plains"},
  "world_scale": 1.0,
  "sea_level": 64,
  "min_y": -2032,
  "height": 4064,
  "experimental_apron_chunks": 5
}
```

`CubeNetRuntime` enables same-Level routing only when exactly one loaded Level
uses this generator. The five edges that touch directly in the planar net use
ordinary movement. The other seven physical face pairs preload the target area,
transform horizontal velocity and yaw, probe the translated bounding box, then
complete the move. A corner crossing is held because cube vertices are outside
the ordinary face policy.

This remains a wiring and compatibility prototype. Cut-edge apron chunks are
now generated as derived native `LevelChunk` instances from canonical face
columns; transition-buffer chunks use the same ordinary sampler, while
excluded corner chunks remain empty. The existing
datapack helper for the six-face runtime still creates `tellus:sphere_face`
dimensions. A server mixin prevents derived chunks from being serialized, and
ordinary `Level#setBlock` writes in an apron are forwarded to the canonical
target chunk with the inverse quarter-turn before the loaded apron cache is
refreshed. Live probes now cover chest and sign NBT synchronization, chest
restart persistence, two-way fluid propagation, a moving-arrow rebase, a
non-player boat/passenger-tree rebase, and loaded proxy/owner redstone
neighbor updates. A bounded local non-player leash-bundle path and one restart
persistence check are verified. Player-mounted boats also have bounded live
evidence, while broader block entities and general neighbor behavior remain
open before this is a complete storage contract. The original-height synthetic
fixture now has a saved-chunk cold reload and elevated walk/sprint routes in
both directions on the ordinary Fabric client. A follow-up repaired saved-edit
apron recovery by loading edited owners asynchronously and waiting for the
overlay before staging chunk packets. Repaired walk/sprint and mounted routes
now complete in both directions. A ready-chart pause bug was then found and
fixed on both server and client: a live chart survived 138 seconds from ready
to commit without repeating the earlier terrain gap. Real first-generation
terrain now has bounded ordinary-interior evidence at scale 1; real terrain
loading at cut seams, exact zero-hold continuity and Distant Horizons/Sodium
behavior remain separate acceptance work. See
[the client acceptance record](cube-net-client-acceptance.md)
for the measured scope and capture limitations.

For staged-client traversal, set `--apron-chunks` when creating a new test pack;
the helper defaults to 5 chunks.
At the current 24-block handoff threshold the required width is
`3 + max(effective render distance, server simulation distance)` chunks,
including the far edge of the threshold chunk and a neighbor halo. The
effective render distance is the smaller of the advertised client distance and
the server view distance. The runtime rejects staging if the world's configured
apron is narrower. For example, client view 4 and simulation distance 4 require
7 apron chunks; an 8-chunk test band supplies a small margin. The old default
of 2 was a bounded generator fixture and did not support this complete-view
gate. The supported width is 0..35 chunks; view/simulation distance 32 requires
35 chunks. Corner/overlap quarantine and
full-height compilation cost still need separate acceptance.

## Current server evidence

The focused Java suite covers the cube-net codec, native apron resolution,
canonical block lookup, water columns, and the rotated column order on all six
faces. A separate development server run with the synthetic sampler exercised
all 24 directed face edges, 14 exact cut-edge starts, three-by-three FULL-chunk
preload around each target, velocity/yaw/pitch and fall-distance preservation,
and a blocked landing rollback.
The result was `passed: true`; that server-only run used a packet sink and does
not establish client rendering acceptance. Later ordinary-renderer client
results and the unverified third-party renderer combinations are recorded in
[the client acceptance record](cube-net-client-acceptance.md).
