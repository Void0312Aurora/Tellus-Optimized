# Cube-Net deferred 世界设置

本文记录 T2 deferred 的三类 Earth 世界设置在 Cube-Net 视图中的归属、实现和边界。

## spawn_latitude / spawn_longitude

- **判定**：可安全实现。Cube-Net 已有 `CubeNetChunkGenerator.surfacePosition(LevelHeightAccessor, latitude, longitude)`，它复用 `CubeNetFeatureProjector`、canonical face terrain sample 和 `SphereTerrainMaterializer` 的地表 Y 计算，等价于 Earth 的 geodetic surface lookup。
- **实现**：`CubeNetBiomeSource.Settings` 增加 `spawn_latitude` / `spawn_longitude`（`Codec.optionalFieldOf`，默认复用 `EarthGeneratorSettings.DEFAULT`），`CubeNetChunkGenerator#getInitialSpawnPosition` 调用该 surface lookup；pre-26 初始出生点 mixin 同时识别 Cube-Net。mc1201 Java writer、shared/mc1211 Java writer 和 `tools/create_cube_net_datapack.py` 均输出字段。Codec/工具校验纬度 `[-90,90]`、经度 `[-180,180]` 且要求有限值。
- **限制**：出生点只在 Cube-Net generator 被用于该维度初始 spawn 时生效；无可用地形采样时明确抛出/返回空结果，由调用方处理，不伪造平面坐标。
- **验证**：`CubeNetChunkGeneratorTest` 覆盖默认经纬度到 `surfacePosition` 的委托；`CubeNetDatapackWriterTest` 覆盖字段输出；离线 Cube-Net 测试命令见文末。

## terrestrial_height_scale / oceanic_height_scale

- **判定**：可安全实现。Cube-Net 的高程到块 Y 映射集中在 `SphereTerrainMaterializer.height(double elevationMeters)`；该层同时服务 terrain column、water surface、heightmap、LOD 和地标高度，适合作为唯一映射点。倍率必须围绕海平面作用，不能改变 `seaLevel`。
- **实现**：`CubeNetBiomeSource.Settings` 增加两个正的有限倍率（`Codec.optionalFieldOf`，默认均为 `1.0`）；Cube-Net 生成器把设置传入 mc1201 与 shared/mc1211 的 `SphereTerrainMaterializer`。正高程使用 terrestrial 倍率，负高程使用 oceanic 倍率，随后按原有正值 `ceil`、负值 `floor` 规则换算块坐标；`seaLevel` 保持不变。两个 Java writer 和 Python 工具（含 `--terrestrial-height-scale`、`--oceanic-height-scale`）保持字段一致。
- **限制**：倍率改变的是 DEM 高程相对海平面的垂直表现，不是世界半径、球面经纬度映射或 datapack 的 dimension height；倍率过大导致超出维度垂直区间时，材质化预检明确报错。
- **验证**：`SphereTerrainMaterializerTest` 验证 worldScale=1、seaLevel=0 时 `100m × 2.0 = 200`、`-100m × 0.5 = -50`，以及倍率 `1.0` 与旧公式一致；writer 测试验证两个字段输出。

## min_altitude / max_altitude

- **判定**：继续 deferred。两项是 EarthGeneratorSettings 的 DEM 高程裁切语义，不是 Cube-Net 生成器自身的坐标边界。Cube-Net 世界的可用垂直区间由 datapack 的 `dimension_type`（`min_y`、`height`，以及对应的 `logical_height`）拥有；生成器的 `min_y` / `height` 必须与该维度类型一致。
- **实现现状**：不新增会与维度类型重复的 Cube-Net altitude 字段。Java writer 使用固定的 section-aligned `min_y=-2032`、`height=4064`；Python writer 在写盘前校验 `min_y`、`height` 的 section 对齐、范围及 `sea_level` 包含关系。两个生成器 codec/构造器也会在加载期校验垂直区间，非法值以明确异常终止，而不是静默截断。
- **限制**：当前 datapack API 不能用 `min_altitude` / `max_altitude` 表达 DEM 高程裁切；直接把它们映射成生成器截断会让 `dimension_type` 与材质化列的边界产生歧义。需要可配置 altitude 时，应由 datapack 同时调整 `dimension_type.min_y` / `height`，并由加载期校验确保 generator 与 dimension type 完全一致。
- **验证**：Python datapack writer 的垂直范围校验、Java/generator vertical-range codec 校验，以及离线编译命令覆盖当前边界；未宣称 Earth altitude 裁切已实现。

## 离线验证命令

```powershell
.\gradlew.bat :mc1201:test --tests "com.yucareux.tellus.worldgen.CubeNet*" --offline
.\gradlew.bat :mc1211:compileJava --offline
```

以上命令不启动 Minecraft；结果以任务收尾时实际输出为准。
