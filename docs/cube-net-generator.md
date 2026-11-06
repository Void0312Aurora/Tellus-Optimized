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
  "biome_source": {
    "world_scale": 1.0,
    "apron_chunks": 5,
    "fallback": {"biome": "minecraft:plains"},
    "settings": {
      "random_biomes": false,
      "cave_generation": true,
      "deep_dark": false,
      "underground_depth": 256
    }
  },
  "world_scale": 1.0,
  "sea_level": 64,
  "min_y": -2032,
  "height": 4064,
  "experimental_apron_chunks": 5,
  "river_lake_shoreline_blend": 5,
  "ocean_shoreline_blend": 5,
  "shoreline_blend_cliff_limit": true
}
```

## 世界设置（`biome_source.settings` 字段）

生成器现在把下列 CLI 选项写入 `biome_source.settings`；默认值与
`CubeNetBiomeSource.Settings.DEFAULT` 一致：

| CLI 参数 | JSON 字段 | 默认值 | 作用 |
| --- | --- | ---: | --- |
| `--caves` / `--no-caves` | `cave_generation` | `true` | Cube-Net 球面洞穴采样与洞穴生物群系路径 |
| `--deep-dark` / `--no-deep-dark` | `deep_dark` | `false` | Deep Dark/sculk 相关生成 |
| `--underground-depth N` | `underground_depth` | `256` | 地下壳、洞穴与地下采样深度（`64..512`） |
| `--ore-veins` / `--no-ore-veins` | `ore_veins` | `true` | Cube-Net 自有矿脉采样 |
| `--geological-stone-patches` / `--no-geological-stone-patches` | `geological_stone_patches` | `true` | Cube-Net 地质石块斑块采样 |
| `--caves-reach-surface` / `--no-caves-reach-surface` | `caves_reach_surface` | `false` | 洞穴是否允许接近/连到地表 |
| `--roads` / `--no-roads` | `roads` | `true` | 道路与道路相关投影 |
| `--buildings` / `--no-buildings` | `buildings` | `true` | 建筑与建筑相关投影 |
| `--structures` / `--no-structures` | 全部 `add_*` | `false` | 一键启用/关闭原版结构；逐项参数可覆盖 |
| `--add-strongholds` / `--no-add-strongholds` | `add_strongholds` | `false` | 要塞 |
| `--add-villages` / `--no-add-villages` | `add_villages` | `false` | 村庄 |
| `--add-mineshafts` / `--no-add-mineshafts` | `add_mineshafts` | `false` | 废弃矿井 |
| `--add-ocean-monuments` / `--no-add-ocean-monuments` | `add_ocean_monuments` | `false` | 海洋 monument |
| `--add-woodland-mansions` / `--no-add-woodland-mansions` | `add_woodland_mansions` | `false` | 林地府邸 |
| `--add-desert-temples` / `--no-add-desert-temples` | `add_desert_temples` | `false` | 沙漠神殿 |
| `--add-jungle-temples` / `--no-add-jungle-temples` | `add_jungle_temples` | `false` | 丛林神庙 |
| `--add-pillager-outposts` / `--no-add-pillager-outposts` | `add_pillager_outposts` | `false` | 掠夺者前哨 |
| `--add-ruined-portals` / `--no-add-ruined-portals` | `add_ruined_portals` | `false` | 废弃传送门 |
| `--add-shipwrecks` / `--no-add-shipwrecks` | `add_shipwrecks` | `false` | 沉船 |
| `--add-ocean-ruins` / `--no-add-ocean-ruins` | `add_ocean_ruins` | `false` | 海底废墟 |
| `--add-buried-treasure` / `--no-add-buried-treasure` | `add_buried_treasure` | `false` | 埋藏的宝藏 |
| `--add-igloos` / `--no-add-igloos` | `add_igloos` | `false` | 雪屋 |
| `--add-witch-huts` / `--no-add-witch-huts` | `add_witch_huts` | `false` | 女巫小屋 |
| `--add-ancient-cities` / `--no-add-ancient-cities` | `add_ancient_cities` | `false` | 远古城市 |
| `--add-trial-chambers` / `--no-add-trial-chambers` | `add_trial_chambers` | `false` | 试炼密室（此字段在 1.20.1 数据中通常没有结构集） |
| `--add-trail-ruins` / `--no-add-trail-ruins` | `add_trail_ruins` | `false` | Trail Ruins（此字段在 1.20.1 数据中通常没有结构集） |
| `--add-abandoned-camps` / `--no-add-abandoned-camps` | `add_abandoned_camps` | `false` | Abandoned Camps（保留 TellusStructurePolicy 语义；1.20.1 数据中没有该结构集） |

例如关闭建筑和原版洞穴、同时保留矿脉：

```sh
python3 tools/create_cube_net_datapack.py \
  --output /path/to/test-world/datapacks/cube-net \
  --no-buildings --no-caves --ore-veins
```

`CubeNetChunkGenerator` 从 `CubeNetBiomeSource.Settings` 解析这些值；没有
Cube-Net biome source 时才回退到系统属性默认值。`TellusBiomeGenerationSettings`
仍会按代码过滤复制的原版生成功能：树按生物群系保留（当前有红树林例外），
矿石、地质石块、洞穴/峡谷、Deep Dark、熔岩泉/池等按对应过滤规则处理；
`freeze_top_layer`/`snow_and_freeze` 会被过滤，因此结冰与树/矿/石层不是由这
个复制过滤器直接生成。Cube-Net 自有球面采样器再依据上述设置决定洞穴、矿脉和
地质石块路径。

### 海陆权威与实时系统

海陆分类使用 Overture `subtype=ocean` 面状要素：完整查询且点落在海洋多边形内
才是海洋；完整查询但没有包含海洋多边形时按陆地处理；未完成、失败或异常时为
`UNKNOWN`，沿用现有地形/陆地掩膜回退，不阻塞地形线程。Cube-Net 通过
`CachingGeodeticOceanAuthority` 对按地理分辨率量化的查询做有界缓存；已知结果
默认缓存 10 分钟，`UNKNOWN` 默认缓存 60 秒，最多 8192 项。

Cube-Net 的实时系统属性是显式 opt-in：

* `-Dtellus.cubeNet.realtimeTime=true`：无 Earth generator settings 的 Cube-Net
  世界启用实时本地时间；
* `-Dtellus.cubeNet.realtimeWeather=true`：启用 Open-Meteo 天气轮询与客户端天气
  状态；
* `-Dtellus.cubeNet.historicalSnow=true`：启用历史温度/降雪网格与积雪处理。

这些属性只作为 Cube-Net 无 Earth 设置时的回退；实时管理器仍按玩家地理锚点、缓存
刷新周期和天气/积雪状态运行。`/tellus map`（GeoTP）负责地图传送 UI；
`/tellus weather` 当前显示所在位置的 Tellus 天气/时间状态，且需要已加载的
实时数据才能反映其变化。它们不会把上述三个 JVM 属性写入 datapack，也不是生成器
settings 字段。

The spherical source maps
canonical water through the available ocean climate sample and applies the same
Tellus ESA + Köppen classification used by `EarthBiomeSource`, including random
biome patches when enabled, badlands promotion, snow biome shape,
sunflower/flower/cherry/dappled variants, and cave biome selection. Suspected
water-like land-cover columns also make a geodetic, non-blocking OSM water query;
a mapped inland feature gets the vanilla river biome, while an ocean hint reuses
the ocean climate path. This path applies canonical terrain and water levels
through Tellus's existing inland hydrology and OpenWaters adapters. The
generator also runs registry biome decoration settings on canonical chunks and
mirrors the finished result into apron chunks.

`CubeNetRuntime` enables same-Level routing only when exactly one loaded Level
uses this generator. The five edges that touch directly in the planar net use
ordinary movement. The other seven physical face pairs preload the target area,
transform horizontal velocity and yaw, probe the translated bounding box, then
complete the move. A corner crossing is held because cube vertices are outside
the ordinary face policy.

The canonical terrain path now also carries Tellus's offline GLiM/Macrostrat
geology result into `StrataPolicy`: native columns, LOD columns and derived
apron columns use the same geodetic rock profile. Ocean columns reuse the
OpenWaters bathymetry lookup through a spherical adapter; a missing bathymetry
sample keeps the existing DEM floor and never invents ocean membership. The
Macrostrat overlay is opt-in, while the bundled GLiM lookup remains offline.
The same path now attaches Tellus's geodetic slope neighbourhood to each
canonical surface. `MountainSurfaceRules`, `SurfaceMaterialDepthPolicy` and
`DeepslateSlopePolicy` choose the surface palette and depth from that canonical
input; native columns and distant-horizon LOD reuse the same decision. When
slope coverage is unavailable, the previous cover-class palette and three-block
surface fallback remain in force.
Canonical land-cover tree anchors now reuse Tellus's ETH canopy-height sample
and procedural tree generator. Only the owning face chunk decorates an anchor;
apron chunks do not own trees. The canonical chunk path now reuses Tellus's
 underground and OSM feature rules, while vanilla structure starts are admitted
 only for canonical chunks. Cube-Net caves, ore veins and geological stone
 patches use `CubeNetSphericalCaveSampler`: each block is converted to the exact
 canonical `GeoPoint`, sampled in an ECEF-like spherical field with radial
 underground depth, and then written to the owning Minecraft chunk. Equivalent
 points on adjacent faces therefore use the same cave field without a planar
 chunk seed or a lower-face owner rule. The sampler retains Tellus's surface
 cover, water/flood guard, underground floor, base-stone replacement and
 geological burial policies. Apron chunks build the same canonical owner in a
 temporary chunk and seam-rotate its complete vertical state into the derived
view, so they do not run a second noise field. Its deterministic multi-scale
field uses the current world seed and physical ECEF-like coordinates, while
the Tellus replacement rules retain the same cave, ore, flood and geological
burial policies. It therefore avoids the version-specific planar `ChunkPos`
carver API while keeping one field across all faces and loaders. The AIR
carving step remains the only admitted carving step. Face-corner chunks are
intentionally isolated by the final Cube-Net materialization policy: cells
touching two cut seams lie outside the ordinary face chart and remain empty.
This is a topology rule, not incomplete cave generation or a discontinuity in
the spherical cave field.

Cube-Net also exposes geodetic OSM feature queries and a canonical projector for
roads/buildings. Every returned vertex carries its owning face coordinates and
the matching planar position. The canonical decoration pass now reuses the road
surface style and building material/profile rules for ordinary geometry. Road
segments are sampled and split at canonical face ownership changes. Normal,
bridge and tunnel modes reuse the canonical road surface style; bridges use a
bounded raised deck and sparse supports, while tunnels carve the shared
seven-block interior. Building polygons that cross a face are rasterized
per canonical cell through their geodetic footprint, so they do not bridge the
planar net gap. A cache miss remains an empty
feature set and never invents geometry.
Canonical feature blocks are projected into loaded apron views through the same
owner-to-source seam transform; apron decoration does not issue a second OSM
query or select a different foundation height. Building edge tests include a
one-cell owner halo so walls do not repeat at chunk boundaries.

The same feature path also rasterizes polygonal road areas, places mapped road
amenities and street lights through a geodetic infrastructure query, and
projects Tellus landmark meshes onto the owning face. Road lane markings use
the shared dashed-divider rule and follow the same projected bridge and tunnel
deck heights. At world scale 1:1, building cells use Tellus's floor plans,
stairwells, doors, furnishings, rooftop equipment and boundary-aware exterior
details; coarser scales keep the exterior LOD model. Overture sand polygons feed the shared spherical surface
policy through a non-blocking cache lookup. Dynamic historical/weather snow is
外立面规则使用建筑蓝图对应的 Earth 投影坐标计算，住宅窗板也沿用同一
Arnis 规则；面内坐标只负责决定 canonical 外侧方位和写入位置。
also applied from the geographic point mapped by each canonical face column;
apron views receive it through the owner mirror. Lush-cave axolotl decoration
uses the same canonical-owner rule and never runs in a derived apron view.
Mapped inland rivers reuse the hydrology planner's flow levels and
falling-water curtains after spherical materialization, including derived
apron views.
Shoreline columns now reuse the Earth generator's configurable river/lake and
ocean bank-ramp settings through the same seam-aware planar lookup. Canonical
surface material decisions also reuse Earth's cover-driven palette, climate
groups, shoreline classification and four-neighbour convexity; badlands biomes
write Tellus's plateau and coloured terracotta band palette before the apron
mirror. The three shoreline fields are optional; omitting them keeps the Earth
defaults. Building bodies, boundary tests and exterior details now use the
same geodetic footprint on both sides of a cut seam; exterior offsets cross
the seam table and retain their face orientation. Target-face chunks also
replay a cross-face exterior detail when the source chunk cannot own its
destination block. A displacement that reaches a cube vertex through two edges
is deliberately omitted because that point has no unique face-local
continuation.
Mapped traffic signals, bus stops, benches, recycling points and bicycle racks
use the corresponding multi-block point layouts; each point remains owned by
the canonical chunk and an occupied block is never replaced.

Vanilla structure starts and references are likewise created only on canonical
chunks; apron views do not create duplicate starts. When the canonical owner is
inside the active worldgen region, its complete block state (including structure
pieces) is seam-rotated into the apron. If that owner is outside the region,
the derived view remains unchanged until the owner can be synchronized safely.

This remains a wiring and compatibility prototype. Cut-edge apron chunks are
now generated as derived native `LevelChunk` instances from canonical face
columns; transition-buffer chunks use the same ordinary sampler, while the
final corner-isolation policy leaves excluded corner chunks empty. The existing
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
35 chunks. The final corner/overlap isolation policy is part of the topology;
full-height compilation cost still needs separate acceptance.

## Configure the apron before creating a test world

The apron width is a fixed world-generation setting. Choose it manually when
creating the datapack; the runtime does not resize it when player or server
distances change. The default of **5 chunks only admits effective render
distance 2 and server simulation distance 2**. The helper's default is a
minimal test fixture, not a preset for ordinary higher distances.

At the current 24-block rebase threshold, use:

```text
effective view = min(client requested view, server view-distance)
required apron = 3 + max(effective view, server simulation-distance)
```

All distances in this calculation are in chunks. Examples:

| Client view | Server view | Server simulation | Minimum apron |
|---:|---:|---:|---:|
| 2 | 2 | 2 | 5 |
| 4 | 4 | 4 | 7 |
| 12 | 4 | 6 | 9 |
| 32 | 32 | 32 | 35 |

For a new world with server `view-distance=4`, `simulation-distance=4`, and
client view at most 4:

```sh
python3 tools/create_cube_net_datapack.py \
  --output /path/to/new-test-world/datapacks/cube-net \
  --world tellus:earth --scale 1 --min-y -2032 --height 4064 \
  --apron-chunks 7
```

If the configured apron is too narrow, staging returns `-1` before starting
the destination preload. The player receives the required width, the world's
current width and a `--apron-chunks` example. Lower the effective view and
simulation distances to fit the existing world, or create a new test world with
the required width. Changing generator settings in an already-generated save
is not an accepted migration path.

The `CubeNetViewCoverageTest` regression calls this same runtime admission and
diagnostic path: view/simulation 4 with width 5 is rejected with a width-7
diagnostic; width 7 admits a data radius of 5 without a warning. It also checks
the default 2/2 fixture, server view caps, simulation distance above the view,
and the representable 32/32 case at width 35. These are configuration-gate
checks; they do not establish a full client crossing at each distance.

Representable width is separate from measured throughput. High-distance TPS,
handoff completion time and peak heap remain unverified and are tracked by
[the existing performance follow-up](https://github.com/Void0312Aurora/Tellus-Optimized/issues/21).
The 64 MiB load budget is an estimate, not a hard cap on JVM heap use. Sodium,
Distant Horizons and shader compatibility remain a separate renderer task.

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
