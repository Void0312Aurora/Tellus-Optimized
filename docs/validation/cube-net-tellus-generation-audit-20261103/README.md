# Tellus 生成机制接入立方球审计（2026-11-03）

本记录回答一个范围问题：Tellus 现有的地形生成机制是否已经能够被立方球
使用，以及当前接入是否等同于完整的 Tellus `earth` 生成器。

> **状态更新（2026-11-05）**：本文的“当前没有接入”部分是 11 月 3 日的
> 历史快照，已被后续接入提交取代。现在应以本文末尾的“当前剩余差异”和
> `docs/cube-net-generator.md` 为准。

结论分为两层：球面地形数据链已经接入并有实际验证；完整的平面 Earth
生成器特性还没有接入 `CubeNetChunkGenerator`。因此下一步应当是为需要保留
的特性增加球面坐标适配层，而不是重新设计高程或覆盖物采样器。

## 已经接入并验证的路径

当前立方球生成器的 canonical 路径如下：

```text
CubeNetAddressAdapter
  -> SphereWorldModel.SphereBlockAddress
  -> SphereWorldModel.GeoPoint
  -> TellusWorldgenSources.sphericalSampler(model)
  -> TellusElevationSource
  -> TellusLandCoverSource
  -> TellusLandMaskSource
  -> SphereChunkSampler.TerrainGrid
  -> SphereFaceChunkGenerator
  -> SphereTerrainMaterializer
  -> CubeNetChunkGenerator / native apron
```

这里的三个 Tellus 数据源都提供地理坐标重载；采样器按球面物理点读取高程、
覆盖物和陆海掩膜。切边 apron 只复制目标 canonical face 的列，不对同一物理
位置重新生成一份数据。坐标归属和跨面连续性见
[cube-net-cross-face-contract-20261103](../cube-net-cross-face-contract-20261103/README.md)。

本次新增的 `CubeNetChunkGeneratorTest.defaultGeneratorUsesTellusSphericalSourceFactoryForCanonicalColumns`
使用生产构造函数（不注入测试 `PointSampler`），只把明确的
`tellus.sphere.syntheticSampler=true` 作为测试数据源开关。测试从赤道物理点
定位 canonical 面，生成实际 `ProtoChunk`，再用同一球面采样结果核对地表高度
和覆盖物方块。这证明默认 Cube-Net 构造路径会延迟调用 Tellus 球面数据源工厂，
并把结果写入 Minecraft 方块和高度图。

第一阶段的坐标基础也已经落地：`SphereChunkSampler.TerrainGrid.Column` 现在
携带 canonical `SphereBlockAddress`、采样得到的 `GeoPoint` 和
`SurfaceData`，`SphereTerrainMaterializer` 通过这个完整上下文完成列计划。
后续 biome、水面和特性阶段可以沿用同一列上下文，不需要再次从 Cube-Net
平面坐标反推地理位置。

当前阶段的 `CubeNetBiomeSource` 已经可注册。它通过当前世界的 biome
registry 解码，按 canonical GeoPoint 读取 SurfaceData，并复用 Tellus
的 ESA + Köppen 陆地分类和可用的海洋气候分级；旧的固定 plains 配置仍然可以
继续使用，新测试世界可通过 `create_cube_net_datapack.py --geodetic-biomes`
选择该阶段。对 ESA 水体候选列，当前还会按 canonical 经纬度进行非阻塞 OSM
水体查询；命中内陆水体时返回河流 biome，命中海洋提示时回到海洋气候分级。
这一步只完成 biome 判定，缓存未命中时保留陆地回退，不会把网络等待引入地形
生成主路径。地形列现在另有适配器复用 Tellus 的
`InlandHydrologyPlanner`、`RiverNetwork` 和湖泊水面求解：它使用 Earth 投影
网格执行既有算法，再把结果写回 canonical 球面列；缺失 OSM 覆盖时保留原始
地形列。后续提交又把特殊 biome 变体、球面洞穴/矿脉/地质石层和装饰路径
接到了 canonical owner；本文末尾列出当前仍保留的差异。

另外，真实数据的服务器验证记录在
`docs/validation/terrain-stability-20261026/summary.json`：
`syntheticSampler=false`，Swiss 和 Chengdu 各生成 25 个 Cube-Net chunks，
首次生成、重启前、重载和重新生成的 25/25 状态哈希均匹配，记录的
`passed` 为 `true`。这覆盖了真实高程/覆盖物/陆海掩膜采样、方块物化和原生
chunk 持久化的一致性；它不代表完整地貌特性已经接入。

## 11 月 3 日快照（已被后续更新取代）

固定配置下，`CubeNetChunkGenerator` 仍使用 `FixedBiomeSource`；启用
`CubeNetBiomeSource` 后才会按 canonical GeoPoint 做第一阶段的基础 biome
解析、第二阶段的气候分类和非阻塞内陆水体 biome 探测。两种配置的 `fillFromNoise` 都使用 `SphereTerrainMaterializer` 的基础
石头、草、土、沙、雪、泥和水面调色板；它们都没有调用 `EarthBiomeSource` 或
`EarthChunkGenerator` 的完整列解析和特性阶段。

在 11 月 3 日时，以下机制仍属于普通 `tellus:earth` 路径：

- `EarthBiomeSource` 的气候/覆盖物生物群系选择和雪地缓存；
- `WaterSurfaceResolver` 的海岸、海底和完整区域水面策略；内陆河湖算法已通过
  Cube-Net 适配器复用，但仍需要真实数据下的跨面、重载和性能验证；
- OSM 道路、建筑、水体和沙地的方块级特性填充；
- 树木、洞穴、矿物、结构及其他 `EarthChunkGenerator` decoration/carver 阶段。

当时这些类大量接收平面 `(blockX, blockZ)`，并通过旧的 `EarthProjection` 或
普通世界生成上下文工作。直接把它们挂到 Cube-Net 会把面内坐标误当成全球
投影坐标，也会在接缝两侧产生不同的物理采样点，所以不能仅靠替换 biome
source 完成接入。

## 当时的后续工作边界

如果要把完整 Tellus 特性带入立方球，应按以下顺序增加适配和测试：

1. 让列解析和水面解析接受 canonical `GeoPoint` 或明确的
   `SphereBlockAddress`，并验证跨面同一点返回相同结果；
2. 在 canonical face 上应用地貌材料和水体，再由 apron 复制列，保持单一
   owner；
3. 对道路、建筑、树木和结构使用 `SphereChunkSampler.featureBounds` 的
   geodetic bounds 查询，并定义特性跨 chunk、跨 face 的所有权；
4. 最后才把这些结果接到 biome/decoration 阶段，并用真实数据做多面、重载、
   接缝和确定性哈希验证。

这段是历史计划；当前承诺应以本文下方的剩余差异为准。历史上立方球的可验证承诺曾限定为：球面高程、覆盖物、陆海掩膜
采样，基础方块/水面物化，canonical/apron 一致性，以及 chunk 持久化。

## 当前状态（2026-11-05，完整机制接入）

此前列出的四类缺口已经接入 Cube-Net：

- 球面列在地标物化前复用 Earth 的地面整平结果；
- 内陆水体通过共享的 `WaterSourceConversionGuard` 接入 Earth 的无限水源转换保护，
  同时保留水面、流速等级和 falling curtain；
- 海岸坡度缓冲复用 Earth 的河湖/海岸半径和 cliff-limit 设置，三个设置可在
  `cube_net` 生成器字段中覆盖，旧世界使用 Earth 默认值；
- Badlands 同时处理陡崖条带和低起伏台地顶部，并使用地理坐标驱动材料模式；
- 建筑补齐地基整平、入口门、外立面深度规则、阳台多格结构、住宅细节和各类屋顶设备。

洞穴、矿脉、地质石层、树木、道路、道路区域、道路标线、道路点设施、地标、动态积雪
和生物群系路径此前已经接入，apron 继续只镜像 canonical owner。

面内的 Earth 表面规则现在也通过球面列上下文接入：cover-driven palette、气候组、
岸线材质分类、四邻域凸度和水体 resolver 都在 canonical 物化阶段执行。水体数据
暂时不可用时仍保留原有的非阻塞 planner fallback。

当前剩余差异只在跨 Cube-Net 面的建筑外挑几何：建筑主体按地理足迹连续归属，
但阳台、雨棚和栏杆等面外偏移细节尚未在目标面重新栅格化。它不影响面内生成、
canonical/apron 所有权或水体运行时契约。
