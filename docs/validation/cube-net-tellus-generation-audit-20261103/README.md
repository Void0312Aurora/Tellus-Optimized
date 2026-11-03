# Tellus 生成机制接入立方球审计（2026-11-03）

本记录回答一个范围问题：Tellus 现有的地形生成机制是否已经能够被立方球
使用，以及当前接入是否等同于完整的 Tellus `earth` 生成器。

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

另外，真实数据的服务器验证记录在
`docs/validation/terrain-stability-20261026/summary.json`：
`syntheticSampler=false`，Swiss 和 Chengdu 各生成 25 个 Cube-Net chunks，
首次生成、重启前、重载和重新生成的 25/25 状态哈希均匹配，记录的
`passed` 为 `true`。这覆盖了真实高程/覆盖物/陆海掩膜采样、方块物化和原生
chunk 持久化的一致性；它不代表完整地貌特性已经接入。

## 当前没有接入的路径

`CubeNetChunkGenerator` 的 biome source 仍是 `FixedBiomeSource`，`createBiomes`
只填充固定生物群系；`fillFromNoise` 使用 `SphereTerrainMaterializer` 的
基础石头、草、土、沙、雪、泥和水面调色板。它没有调用 `EarthBiomeSource` 或
`EarthChunkGenerator` 的完整列解析和特性阶段。

因此以下机制目前仍属于普通 `tellus:earth` 路径，不能宣称已经融入立方球：

- `EarthBiomeSource` 的气候/覆盖物生物群系选择和雪地缓存；
- `WaterSurfaceResolver` 的河流、湖泊、海岸和区域水面求解；
- OSM 道路、建筑、水体和沙地的方块级特性填充；
- 树木、洞穴、矿物、结构及其他 `EarthChunkGenerator` decoration/carver 阶段。

这些类大量接收平面 `(blockX, blockZ)`，并通过旧的 `EarthProjection` 或
普通世界生成上下文工作。直接把它们挂到 Cube-Net 会把面内坐标误当成全球
投影坐标，也会在接缝两侧产生不同的物理采样点，所以不能仅靠替换 biome
source 完成接入。

## 后续工作边界

如果要把完整 Tellus 特性带入立方球，应按以下顺序增加适配和测试：

1. 让列解析和水面解析接受 canonical `GeoPoint` 或明确的
   `SphereBlockAddress`，并验证跨面同一点返回相同结果；
2. 在 canonical face 上应用地貌材料和水体，再由 apron 复制列，保持单一
   owner；
3. 对道路、建筑、树木和结构使用 `SphereChunkSampler.featureBounds` 的
   geodetic bounds 查询，并定义特性跨 chunk、跨 face 的所有权；
4. 最后才把这些结果接到 biome/decoration 阶段，并用真实数据做多面、重载、
   接缝和确定性哈希验证。

在这些适配完成前，立方球的可验证承诺应限定为：球面高程、覆盖物、陆海掩膜
采样，基础方块/水面物化，canonical/apron 一致性，以及 chunk 持久化。
