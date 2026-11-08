# Tellus 统一生成抽象迁移路线

> 状态：设计文档（不包含本轮源码改动）。
>
> 目标：在不扩大现有测试面的前提下，把 Earth、Cube-Net 与 Sphere Face 的共同生成语义收敛到一条可回退的抽象链；保留版本层的 Minecraft API、区块写入、网络与客户端生命周期适配。
>
> 基线分支：`feat/cube-net-parity`（当前 HEAD `1c35ccbb2`）。
>
> 相关现状：[Cube-Net 生成器](../cube-net-generator.md)、[结构 parity](../cube-net-structure-parity.md)、[1.21.1 移植评估](../cube-net-mc1211-port.md)、[客户端验收矩阵](../validation/cube-net-acceptance-matrix-20261106/README.md)、[zero-hold 诊断](../validation/cube-net-zero-hold-20261106/README.md)。

## 1. 迁移结论与不可变约束

### 1.1 当前 parity 证据

本路线把任务给出的两组数据作为迁移期间的 golden baseline，不在迁移过程中重新解释指标：

| 对照 | 当前证据 | 迁移期间的门槛 |
|---|---|---|
| Cube / Sphere canonical columns | `13,824` columns，`0` mismatch | 每一阶段都应保持 `13,824 / 0`；若改变比较器或样本来源，先记录等价性证明，再迁移。 |
| Earth / Cube biome cases | `7` cases，`1` mismatch，已知为 `bare / BWh` | 迁移不得产生第二个 mismatch；该已知差异必须继续作为显式 golden case，不能通过更换默认值或采样顺序隐藏。 |

`bare / BWh` 是一个待单独决定的行为差异，不是本抽象迁移的隐式修复目标。只有单独提交规则修复、更新向量并保留前后对照后，才可把门槛改为 `7 / 0`。

### 1.2 不扩大测试面

迁移只复用目前已有的样本、fixture、测试任务和人工验收组合：

* 不新增世界尺寸、渲染器、第三方 mod、外部数据集、随机种子集合或 biome case 集合。
* 不把未完成的 M4/M8/M10/M11 客户端验收写成已通过；现有矩阵中的历史通过、未跑和 open 状态保持原样。
* 13824-column 对照和 7-case biome 对照每阶段只作为同一份 parity 证据重跑或重放，不因新接口而增加样本。
* 新增的测试仅限于验证抽出的接口与既有输入的等价性；不添加新的地理场景。
* 任何缓存 miss、非阻塞查询、synthetic sampler、apron owner、corner isolation 和结构开关语义都保持当前文档约定。

### 1.3 迁移原则

1. **先包裹、后替换、最后删除。** 旧类先作为 adapter 的实现，至少保留一个可选回退路径。
2. **地理语义优先于视图语义。** Earth 的 planar 坐标、Cube-Net 的 face-local 坐标和 Sphere 的 face 坐标都是输入视图；canonical `GeoPoint` 与 owner 是决定地形和特征的权威。
3. **纯决策与 Minecraft 写入分离。** registry、`BlockState`、`ChunkAccess`、网络 payload 和线程调度不进入 decision kernel。
4. **未知是值，不是默认值。** 数据尚未完成、失败或 cache miss 时继续传递 `UNKNOWN`/unavailable；不借抽象迁移补造陆海、地形或 OSM 几何。
5. **owner 只有一个。** canonical chunk 负责查询、决策和首次写入；apron/derived view 只做 seam transform 后的镜像，不重复查询或重新决策。
6. **版本层只实现边界。** `mc1201`、`shared/mc1211`、其他目标层负责 Minecraft API、Codec、payload 和 writer；根 `src/main` 负责无版本的地理、采样、决策和计划。

## 2. 目标结构

```text
EarthGeneratorSettings        CubeNetBiomeSource.Settings
            \                  /
             -> TellusSettingsView (只读、语义字段、fingerprint)
             -> TellusRuntimeView  (只读动态状态、能力、owner/preload)

EarthProjection    SphereWorldModel    CubeNetLayout/SeamTransform
          \              |                    /
           -> TellusCoordinateContext (GeoPoint + view + Earth grid + owner)

TellusWorldgenSources / SphereChunkSampler / preload / water / climate / OSM
                              |
                   TellusSampleProvider
                              |
                   TellusDecisionKernel
                 (biome/surface/water/strata)
                              |
                   TellusFeaturePlanner
            (road/building/tree/landmark/structure)
                              |
        TellusColumnPlan + TellusFeaturePlan (中立计划)
                              |
                 version-specific writer
       (ChunkSectionWriter / SphereMaterializer / CubeNet owner+apron)
                              |
             runtime adapter / payload / client
```

### 2.1 公共 settings/runtime view

建议在 `src/main/java/com/yucareux/tellus/worldgen/runtime/` 新增两个只读接口（名称可在落地时保持一致）：

```java
interface TellusSettingsView {
   double worldScale();
   int heightOffset();
   int seaLevel();
   int minY();
   int height();
   double terrestrialHeightScale();
   double oceanicHeightScale();
   boolean enableWater();
   boolean caveGeneration();
   boolean roads();
   boolean buildings();
   boolean randomBiomes();
   double randomBiomeDensity();
   long randomBiomeSeed();
   TellusStructurePolicy.StructureToggles structures();
   TerrainPreloadFingerprint preloadFingerprint();
   // 其他开关按语义分组，不暴露 Minecraft Codec 类型。
}

interface TellusRuntimeView {
   TellusSettingsView settings();
   TellusGeneratorKind kind();
   TellusCoordinateContext coordinate(double x, double y, double z);
   TellusRuntimeCapabilities capabilities();
   TerrainPreloadView preload();
   Optional<TellusRealtimeState> realtimeState();
}
```

接口是目标契约，不要求本轮立即创建源码文件。`EarthGeneratorSettings` 是完整 codec/持久化来源；`CubeNetBiomeSource.Settings` 是 Cube-Net view 的局部来源；二者都通过 adapter 投影到同一 view，不能通过让 Cube-Net settings 继承或复制 Earth record 来解决。

`TerrainPreloadPackageRegistry.SettingsView` 已经证明了按 settings fingerprint 提供只读数据视图的方向，但它只覆盖 preload；新 view 应复用其 fingerprint/缓存语义而不是把 `EarthGeneratorSettings` 重新暴露给所有消费者。`TellusGeodeticPosition` 保留为 GeoTP、freeze、命令和运行时的兼容门面，内部逐步委托给 `TellusRuntimeView`。

### 2.2 coordinate context

当前 `src/main/.../global/SphereColumnContext.java` 已包含 `SphereBlockAddress`、`GeoPoint`、`SurfaceData`，是合适的起点。目标是将其扩展为无 Minecraft 类型的 `TellusCoordinateContext`，至少包含：

* canonical `GeoPoint` 和 world scale；
* 当前 view 坐标（Earth planar、Cube-Net planar、Sphere face-local）；
* 可选 Earth-grid 坐标（经纬度到 block X/Z 后的采样坐标）；
* canonical face/block/chunk owner；
* derived/apron 标记和 `CubeNetSeamTransform` 方向；
* resolution、excluded/singularity 状态和负坐标 floor 结果。

`BlockPos`、`ChunkAccess` 和网络姿态不进入该 record。Earth 使用 `GlobalMapModel`/`EarthProjection` adapter，Sphere 使用 `SphereFaceTopology`，Cube-Net 使用 `SphereWorldModel` + `CubeNetLayout` + `CubeNetAddressAdapter`。Corner exclusion 仍是拓扑规则，不由 context 自动修复。

### 2.3 sample provider

`TellusSampleProvider` 应提供语义采样结果和明确 availability，而不是直接返回某个版本的 terrain column：

```java
interface TellusSampleProvider {
   TellusSurfaceSample surface(TellusCoordinateContext context, SamplingPurpose purpose);
   TellusClimateSample climate(TellusCoordinateContext context, SamplingPurpose purpose);
   TellusWaterSample water(TellusCoordinateContext context, SamplingPurpose purpose);
   Optional<TellusGeologySample> geology(TellusCoordinateContext context, SamplingPurpose purpose);
   Optional<TellusCanopySample> canopy(TellusCoordinateContext context, SamplingPurpose purpose);
}
```

OSM 道路、建筑、基础设施和地标应由 planner 使用同一坐标/availability 约定的 `TellusFeatureSource` 取得；不把异步 feature 列表塞进每个 surface sample。`SamplingPurpose` 至少区分 native、preload、LOD、biome-locate 和 structure-probe，避免把精度/阻塞策略隐式放在调用者。

当前 `TellusWorldgenSources.cubeNetSurfaceSampler`、`SphereChunkSampler`、`CubeNetPreloadAdapter`、`WaterSurfaceResolver`、`CubeNetInlandWaterResolver` 和 `CubeNetOceanSurfaceResolver` 已形成 provider 的实际组成部分。

### 2.4 decision kernel

`TellusDecisionKernel` 只接受 settings view、coordinate context 和 sample provider 返回的 immutable sample，输出中立 decision：

* biome/surface biome（包括 cave depth 和 structure probe）；
* surface top/filler/depth/slope/terrain form；
* water/shoreline/river/lake；
* geology/strata；
* structure admission/fit 的纯条件。

现有 `CubeNetSurfacePolicy.resolveEarthEquivalent` 是最接近目标的入口；`CubeNetSurfacePolicy.Decision` 可作为中立 surface decision 的兼容原型。`MountainSurfaceRules`、`SurfaceMaterialDepthPolicy`、`DeepslateSlopePolicy`、`TerrainSlopePolicy`、`TellusBiomeLocatePolicy`、`TellusCaveBiomeDepthPolicy`、`StrataPolicy` 和 `TellusStructurePolicy` 应作为 kernel 的规则组件，而不是继续由两个 generator 私有复制。

### 2.5 feature planner

planner 输出带 owner 和 conflict policy 的中立计划，不直接写块：

* road segment/area、bridge/tunnel、lane marking、street light 和 mapped amenities；
* building footprint、blueprint、facade、interior、roof details；
* tree anchor/canopy、landmark placement；
* hydrology flow/falling-water plan；
* structure start admission、地下 fit/offset 和结构碰撞过滤。

规划只在 canonical owner 执行；apron 通过 owner plan + seam transform 镜像。已有 `InlandHydrologyPlanner` 的“native resolution 规划，blocks/previews/LOD 共用结果”是该层应保持的模型。

### 2.6 writer

writer 将中立计划翻译为版本层写入：

* `TellusColumnPlan`/`TellusLayerSpan`：bedrock、strata、surface、water、sky 以及 heightmap 影响；
* `TellusFeaturePlan`/`TellusBlockWrite`：优先级、占用保护、owner、BlockState adapter、可选 block entity；
* `TellusColumnWriter`/`TellusFeatureWriter`：只依赖目标版本的 `ChunkAccess`、registry、light/heightmap 生命周期。

`SphereTerrainMaterializer` 当前依赖 `EarthChunkGenerator.ChunkSectionWriter`，应先抽出 writer contract，再让 1.20.1/1.21.1 materializer 各自适配。当前“先预检全部列，之后批量写入；新鲜 chunk；最后 prime heightmaps/setUnsaved”的行为必须作为 writer contract，而非实现细节。

### 2.7 runtime 替换

runtime adapter 是最后接入层：

* Earth：默认 legacy adapter，保留平面坐标、GeoTP、realtime、DH/Voxy 现有行为；
* Cube-Net：保留 same-Level seam、apron、PREPARE/STAGED/COMMIT/RELEASE、payload 与 edit routing，adapter 只更换 settings/context/sample/plan/writer 来源；
* Sphere Face：保留六维连接、face topology 和 `SphereFaceRuntime` 的碰撞/传送行为；
* 版本层：保留各目标 `TellusChunkGeneratorBridge`、Codec、payload receiver 和 loader lifecycle。

运行时替换不得先动 Sodium/DH、第三方 renderer 或 payload 协议；这些仍是外层 capability。`TellusCommon`、`TellusRealtimeManager`、`DistantHorizonsIntegration`、`TellusLodGenerator`、`TellusVoxyPregenManager` 和 mixin 中直接 `instanceof EarthChunkGenerator` 的分支，只有 runtime view 稳定后才逐个改为 capability/view 查询。

## 3. 分阶段实施路线

每个阶段都要求：先增加 adapter/contract，再接入一个旧调用者，跑本阶段最小矩阵，保留旧路径，最后才扩大到下一个调用者。下面的“改动文件范围”是未来实施范围；本次只创建本计划文件。

### 阶段 0：证据冻结与迁移闸门

**目标**

* 固定当前 revision、settings JSON、parity comparator 和已有 acceptance 命令。
* 把 `13,824 / 0` 与 `7 / 1 (bare/BWh)` 写入迁移报告模板，作为所有阶段的同一 baseline。
* 确认旧实现可编译、可回退，不在此阶段改变运行语义。

**改动文件范围**

* 计划/证据索引：`docs/architecture/tellus-migration-plan.md` 及现有验证记录的引用。
* 验证入口只复用：`tools/check_cube_net_acceptance.py`、现有 Gradle test task、现有 parity fixture/runner。
* 不改 `src/**`、`mc1201/**`、`shared/**`。

**验收**

* 记录 HEAD `1c35ccbb2`、dirty state、JDK/Gradle offline 条件。
* `python tools/check_cube_net_acceptance.py` 的现有静态层仍为 `12/12`；带 Gradle 的既有 Cube-Net 契约仍为 `13/13`（若任务实际输出不同，以输出为准并记录）。
* Cube/Sphere 证据仍为 `13824 columns, 0 mismatch`；Earth/Cube biome 证据仍为 `7 cases, 1 mismatch bare/BWh`。

**回滚点**

* 直接回到 `1c35ccbb2`，不需要数据迁移或世界迁移。

**风险**

* 证据未与具体 revision/fixture 绑定，导致后续“通过”不可复现。
* 将历史客户端通过误写成当前通过，尤其是 M11 open。

**最小测试矩阵**

| Lane | 输入 | 门槛 |
|---|---|---|
| P-column | 现有 13,824 columns | `0 mismatch` |
| P-biome | 现有 7 cases | 只允许已知 `bare/BWh` 1 mismatch |
| C-contract | `python tools/check_cube_net_acceptance.py` | 现有静态项全通过 |
| C-gradle | 现有 `:mc1201:test --tests "com.yucareux.tellus.worldgen.CubeNet*" --offline` | 无新增失败 |

### 阶段 1：公共 settings/runtime view

**目标**

* 把 Earth 完整 settings 与 Cube-Net 子集投影成同一只读语义 view。
* 将动态 runtime 状态、能力、preload view 和 generator kind 收口，避免消费者继续直接依赖具体 generator。
* 不改变 Codec 字段、默认值、settings fingerprint 或 runtime property 归属。

**改动文件范围**

* 新增目标目录：`src/main/java/com/yucareux/tellus/worldgen/runtime/` 中的 `TellusSettingsView`、`TellusRuntimeView`、adapter/records。
* adapter 来源：`src/main/java/com/yucareux/tellus/worldgen/EarthGeneratorSettings.java`、`CubeNetBiomeSource.java`、`TellusGeodeticPosition.java`。
* 只读 preload 连接：`src/main/java/com/yucareux/tellus/preload/TerrainPreloadPackageRegistry.java`、`TerrainPreloadView.java`。
* 仅替换一个无副作用消费者：优先 `TellusRealtimeManager` 或 `TellusCommon` 的 settings 读取；暂不改 generator 主路径、DH、payload、mixin。

**验收**

* Earth 与 Cube-Net 重叠字段的 defaults、effective sea level、vertical range、water/cave/road/building/structure toggles 相等；Cube-Net 专有 apron/singularity 字段仍只属于 view adapter。
* 旧 JSON codec round-trip 和 datapack writer 输出不变。
* `TerrainPreloadPackageRegistry.SettingsView` 仍按同一 fingerprint 命中/拒绝 package。
* 新 view 未接入时 legacy 路径结果与接入一个无副作用消费者的结果相同。

**回滚点**

* 回退 runtime view adapter 和单个消费者改动；保留 `EarthGeneratorSettings`、`CubeNetBiomeSource.Settings` 原实现。

**风险**

* duplicate default 漂移；Cube-Net `heightOffset`/`seaLevel` 与 Earth effective height 语义混淆；可变 realtime 状态被错误缓存。

**最小测试矩阵**

* `src/test/.../EarthGeneratorSettingsCodecTest`。
* `src/test/.../preload/TerrainPreloadSettingsOverridesTest`。
* `src/test/.../worldgen/TellusWorldgenSourcesTest#cubeNetWaterSettingsFollowWorldScaleAndSeaLevel`。
* `mc1201` 现有 `CubeNetDatapackWriterTest`、`CubeNetChunkGeneratorTest`。
* P-column/P-biome 两条 parity lane照常执行。

### 阶段 2：coordinate context

**目标**

* 让所有 terrain/biome/feature 决策携带同一个 canonical point 和 owner，停止在各处从 planar X/Z 重新反推经纬度。
* 复用并扩展 `SphereColumnContext`，只在 context adapter 层处理 Earth、Sphere、Cube-Net 坐标差异。
* 明确 seam、apron、corner 和负坐标 floor 规则。

**改动文件范围**

* 新增：`src/main/java/com/yucareux/tellus/worldgen/global/TellusCoordinateContext.java`、`TellusViewCoordinate.java`、owner/availability records（具体命名可按现有风格调整）。
* 适配：`SphereColumnContext.java`、`SphereWorldModel.java`、`SphereFaceTopology.java`、`GlobalMapModel.java`、`EarthProjection.java`。
* Cube-Net 几何：`CubeNetLayout.java`、`CubeNetAddressAdapter.java`、`CubeNetApronResolver.java`、`CubeNetSeamTransform.java`、`CubeNetRemoteProjection.java`。
* 先接入 `SphereChunkSampler` 和 `CubeNetSurfacePolicy`；Earth/Cube/Sphere generator 主循环保持旧入口。

**验收**

* 现有 round-trip、seam、cross-face、negative-coordinate、corner exclusion 结果不变。
* 同一 canonical point 在 native、LOD、apron 视图得到同一 owner 和 GeoPoint；apron 不产生新 owner。
* 13824 columns 仍为 0 mismatch，7 biome cases 仍只有 bare/BWh mismatch。

**回滚点**

* 保留 `SphereColumnContext` 构造路径；将单个 consumer 的 context factory 切回旧 `EarthProjection`/`SphereWorldModel` 调用。

**风险**

* 经度 ±180°、面边界、负坐标 floor/truncation、corner 无唯一 continuation；Earth 的单轴 `TellusGeodeticPosition` 与双轴实现混用。

**最小测试矩阵**

* `EarthProjectionTest`、`SphereWorldModelTest`、`SphereFaceTopologyTest`。
* `CubeNetAddressAdapterTest`、`CubeNetLayoutTest`、`CubeNetCrossFaceConsistencyTest`、`CubeNetBoundaryContractTest`。
* `SphereChunkSamplerTest` 使用现有 sampler doubles。
* P-column/P-biome 两条 parity lane；不增加面、分辨率或随机 seed。

### 阶段 3：sample provider

**目标**

* 统一 Earth、Cube-Net、Sphere 的 elevation/cover/land-mask/geology/climate/water/slope/canopy 采样入口。
* 保留 source precedence、preload-first、non-blocking、local-only、memory-only、resolution 和 `UNKNOWN` 语义。
* 让 provider 可以在 native、preload、LOD 和 biome locate 之间显式选择采样目的。

**改动文件范围**

* 新增：`src/main/java/com/yucareux/tellus/worldgen/sample/` 的 `TellusSampleProvider`、sample records、availability/status、factory。
* 来源 adapter：`TellusWorldgenSources.java`、`SphereChunkSampler.java`、`CubeNetPreloadAdapter.java`、`TerrainPreloadView.java`、`TerrainPreloadPackageRegistry.java`。
* 水域 adapter：`WaterSurfaceResolver.java`、`CubeNetInlandWaterResolver.java`、`CubeNetOceanSurfaceResolver.java`、`GeodeticOceanAuthority.java`。
* source 类只作为 provider 的后端：`TellusElevationSource`、`TellusLandCoverSource`、`TellusLandMaskSource`、`TellusKoppenSource`、`SphericalGeologySource`、OISST/Open-Meteo 和 OSM source。
* 先将 `CubeNetSurfacePolicy` 接到 provider；EarthChunkGenerator、CubeNetChunkGenerator、SphereFaceChunkGenerator 的完整循环暂不替换。

**验收**

* `TellusWorldgenSourcesTest` 的 synthetic sampler 仍仅在显式 property 下启用。
* preload package fingerprint/分辨率不匹配仍是 miss；正在加载、失败、无覆盖不被转换成有值的默认样本。
* Cube-Net hydrology/bathymetry 仍优先于 fallback，缺 bathymetry 不伪造 ocean membership。
* P-column/P-biome parity 不变；provider 不引入第二份采样缓存或不同采样分辨率。

**回滚点**

* 每个 generator 保留旧 source accessor；通过 factory 开关把 provider consumer 切回 `TellusWorldgenSources` 旧调用。

**风险**

* Earth grid 与 geodetic sample 的 resolution 转换；cache fingerprint 错配；并行采样完成顺序改变；`UNKNOWN` 被错误降级成 dry/land。

**最小测试矩阵**

* `TellusWorldgenSourcesTest`、`SphereChunkSamplerTest`。
* `CubeNetPreloadAdapterTest`、`CubeNetInlandWaterResolverTest`、`CubeNetOceanSurfaceResolverTest`。
* `WaterSurfaceResolverTest`、`TellusWorldgenSourcesTest#cubeNetWaterSettingsFollowWorldScaleAndSeaLevel`。
* P-column/P-biome；无新的外部 tile 或 OSM 数据。

### 阶段 4：decision kernel

**目标**

* 把 biome、surface、shoreline、water/strata、structure-admission 的共同判断变成纯、可重复的 kernel。
* Earth 和 Cube-Net 只负责准备 context/sample，Sphere 只负责 face view；kernel 不读取 `ChunkAccess`、registry、线程局部状态或网络。
* 明确保留 `bare/BWh` 已知差异，禁止在抽取过程中通过顺序/默认值改写它。

**改动文件范围**

* 新增：`src/main/java/com/yucareux/tellus/worldgen/decision/TellusDecisionKernel.java` 及 `BiomeDecision`、`SurfaceDecision`、`WaterDecision`、`StrataDecision` records。
* 首批规则迁移：`CubeNetSurfacePolicy.java`、`MountainSurfaceRules.java`、`SurfaceMaterialDepthPolicy.java`、`DeepslateSlopePolicy.java`、`TerrainSlopePolicy.java`、`StrataPolicy.java`。
* biome/locate 适配：`EarthBiomeSource.java`、`CubeNetBiomeSource.java`、`TellusBiomeLocatePolicy.java`、`TellusCaveBiomeDepthPolicy.java`。
* structures 纯门控：`TellusStructurePolicy.java`；地形/水域/OSM 碰撞仍暂留 generator，直到阶段 5。

**验收**

* 现有 `CubeNetSurfacePolicyTest`、`SurfaceMaterialDepthPolicyTest`、`DeepslateSlopePolicyTest`、`TerrainSlopePolicyTest`、`StrataPolicy` 相关测试全部通过。
* Earth/Cube 7 biome vectors 保持 1 个且仅 1 个已知 `bare/BWh` mismatch；输出 decision 的 top/filler/depth/biome identity 与迁移前一致。
* Cube/Sphere 13,824 columns 仍为 0 mismatch；任何 decision hash 变化都必须逐字段解释。

**回滚点**

* kernel 提供 `LegacyDecisionAdapter`；每个 generator 可按决策类型切回旧私有方法，旧 `EarthBiomeSource`/`CubeNetBiomeSource` 逻辑暂不删除。

**风险**

* Earth 生物群系规则中 ThreadLocal structure probe/fast spawn；随机 biome seed 与 canonical noise coordinate；NaN/unknown 的分支顺序；不小心把 Cube-Net 的 Earth-equivalent surface policy 当成全部 Earth biome parity。

**最小测试矩阵**

| 组 | 复用的既有测试/证据 | 门槛 |
|---|---|---|
| surface | `CubeNetSurfacePolicyTest`、`SurfaceMaterialDepthPolicyTest`、`DeepslateSlopePolicyTest` | 同输入同 decision |
| biome | 7 个 Earth/Cube parity case | 仅 bare/BWh 已知差异 |
| column | 13,824 Cube/Sphere columns | 0 mismatch |
| structure | `TellusStructurePolicy` 现有结构/生成器 focused tests | toggle/path 结果不变 |

### 阶段 5：feature planner

**目标**

* 将道路、建筑、树、地标、路灯、mapped amenities、hydrology 和结构过滤从“大 generator 方法”变为中立计划。
* 强制 canonical owner、幂等、占用保护、跨面切分和 apron mirror 规则。
* 规划阶段仍不写块，不改变异步 OSM 查询模式。

**改动文件范围**

* 新增：`src/main/java/com/yucareux/tellus/worldgen/feature/` 的 `TellusFeaturePlanner`、plan records、owner/conflict policy、feature source facade。
* 提取来源：`TellusBuildingBlueprints`、`TellusBuildingArchitecture`、`TellusBuildingFacade`、`TellusBuildingInteriors`、`TellusBuildingMaterials`、`TellusBuildingProfiles`、`TellusBuildingStyles`、`ArnisBuildingRules`。
* 道路/设施：`StreetLightPlanner`、`StreetLightModel`、`ArnisRoadRules`、OSM road/infrastructure classes、`CubeNetFeatureProjector`、`CubeNetFeatureRasterizer`、`CubeNetRoadVerticalPolicy`。
* 树/地标/水/结构：`TellusProceduralTreeGenerator`、`TellusLandmarks`、`InlandHydrologyPlanner`、`TellusStructurePolicy` 与 `CubeNetStructurePlacement`。
* 仅先让 Cube-Net 以 canonical owner 生成 plan；Earth 和 DH 继续使用旧写入调用，作为对照。

**验收**

* 既有 OSM cache miss 仍产生 empty/unknown plan，不产生 geometry；feature 查询只发生在 canonical owner。
* 跨面道路/building polygon 仍按 canonical face ownership split；apron 只 mirror，不重复生成 tree、landmark、structure start。
* 建筑已占用块保护、道路/建筑冲突顺序、结构 fit/offset 与当前结构 parity 文档一致。
* 计划顺序、稳定 seed 和计划 hash 在相同 fixture 上稳定；不新增真实 OSM fixture。

**回滚点**

* 每个 feature family 独立保留 legacy planner；按 family 关闭 planner 即回到 Earth/Cube 当前方法，不回退 settings 或 geometry。

**风险**

* chunk load order 导致重复/遗漏；cross-face 外立面 offset 走错 seam；异步 OSM future 触发阻塞；水流和结构计划对 terrain height 的读取时机不同。

**最小测试矩阵**

* `CubeNetFeatureProjectorTest`、`CubeNetFeatureRasterizerTest`、`CubeNetStructurePlacementTest`。
* `StreetLightPlannerTest`、building blueprint/floor-plan/style tests、`TellusProceduralTreeGeneratorTest`。
* `InlandHydrologyPlanner` 既有 artifact/regression tests；结构 parity focused tests。
* P-column/P-biome 作为不可变前置；不新增 OSM/地标数据集或渲染组合。

### 阶段 6：writer

**目标**

* 将 column/feature plan 以版本无关的写入意图交给 Minecraft 版本层。
* 保留完整预检、section 对齐、bedrock/strata/surface/water、heightmap、light invalidation、unsaved 和 block entity 生命周期。
* 让 Sphere、Cube-Net canonical 和 Cube-Net apron 使用同一 column plan；只由 owner transform 改变写入位置/方向。

**改动文件范围**

* 新增：`src/main/java/com/yucareux/tellus/worldgen/write/` 的 `TellusColumnPlan`、`TellusLayerSpan`、`TellusBlockWrite`、`TellusColumnWriter`、`TellusFeatureWriter`。
* 提取/适配：`EarthChunkGenerator.ChunkSectionWriter`、`mc1201/.../SphereTerrainMaterializer.java`、`shared/mc1211/.../SphereTerrainMaterializer.java`、各目标 `TellusChunkGeneratorBridge`。
* Cube-Net 写入边界：`mc1201/.../CubeNetChunkGenerator.java`、`shared/mc1211/.../CubeNetChunkGenerator.java`、`CubeNetApronChunkSync.java`、`CubeNetApronMutationRouter.java`、`CubeNetApronBlockEntitySync.java`。
* 先迁移 SphereTerrainMaterializer，再迁移 Cube-Net canonical column，最后迁移 apron mirror；Earth legacy writer 继续保留。

**验收**

* `mc1201` `SphereTerrainMaterializerTest` 的 height scale/legacy mapping 仍通过。
* 现有 `CubeNetChunkGeneratorTest`、`CubeNetPreloadAdapterTest` 和 column/rotated order tests 结果不变。
* fresh chunk 预检失败时不留下半个 chunk；完成写入后 heightmap、unsaved、water 和 strata 与旧 writer 一致。
* 13,824 columns 仍 0 mismatch；没有新增 writer-specific fixture。

**回滚点**

* `TellusColumnWriter` 由 `LegacyColumnWriterAdapter` 包装；任一目标版本可回退到 `EarthChunkGenerator.ChunkSectionWriter`/旧 `SphereTerrainMaterializer`。
* apron mirror 出现 owner/persistence 问题时，只回退 mirror writer，不回退 canonical writer。

**风险**

* 直接 section write 与 heightmap/light 更新顺序；BlockState identity/rotation；block entity NBT；save/reload 的 edit overlay；目标版本 `ChunkAccess` API 差异。

**最小测试矩阵**

| 组 | 既有测试/证据 | 门槛 |
|---|---|---|
| column writer | `mc1201` `SphereTerrainMaterializerTest` | 旧 mapping 与 scale 结果一致 |
| cube column | `CubeNetChunkGeneratorTest`、rotated column tests | 同状态/顺序 |
| persistence boundary | 已有 saved-edit/chest fixture 记录 | 不降低已有历史通过范围 |
| parity | 13,824 columns + 7 biome cases | 0；bare/BWh 单一已知差异 |

### 阶段 7：runtime 替换

**目标**

* 在 settings/context/provider/kernel/planner/writer 均具备可回退实现后，把生成器和运行时改为通过 `TellusRuntimeView`/runtime adapter 协作。
* 保持 Cube-Net PREPARE/STAGED/COMMIT/RELEASE、client packet 门控、same-Level seam、apron rebase、edit routing 和 Sphere Face 六维行为。
* 将具体 generator 类型检查逐个改为 capability/view 查询，而非一次性重写所有 runtime。

**改动文件范围**

* 新增：`src/main/java/.../worldgen/runtime/TellusRuntimeAdapter`、`TellusGeneratorRuntime`、capability records。
* 根共享消费者：`TellusCommon.java`、`TellusGeodeticPosition.java`、`TellusRealtimeManager.java`、`TellusRuntimePlatform.java`、`TellusWorldPresets.java`。
* DH/Voxy 集成：`DistantHorizonsIntegration.java`、`TellusLodGenerator.java`、`CubeNetLodGenerator.java`、`TellusVoxyPregenManager.java`；每次只替换一个 capability 查询。
* 1.20.1：`mc1201/.../CubeNetRuntime.java`、`SphereFaceRuntime.java`、apron sync/edit/block entity、payload/客户端 state；保持现有协议和版本门禁。
* 1.21.1：`shared/mc1211/.../CubeNetRuntime.java`、`CubeNetTransitionState.java`、payload/writer 注册边界；继续遵守 [移植评估](../cube-net-mc1211-port.md) 的服务端/客户端分层。
* 各目标 `Tellus.java`、`TellusChunkGeneratorBridge.java` 和 mixin 只接入 adapter，不复制 generator 主体。

**验收**

* `python tools/check_cube_net_acceptance.py` 仍为现有静态层全通过；现有 Cube-Net focused Gradle lane 无回归。
* 复用现有 M1/M2/M3/M5/M7 证据的判定范围；M4/M8/M10/M11 继续保留原状态，不能因 adapter 接入而标绿。
* `SphereFaceRuntimeCollisionTest`、现有 Cube-Net transition/payload/view coverage tests 结果不变。
* runtime view 切换开关关闭时，legacy Earth/Cube/Sphere 路径的 parity 与阶段 6 结果完全相同。
* P-column/P-biome 仍通过阶段门槛；runtime 只改变调度/路由，不改变 column decision。

**回滚点**

* 以 generator kind/capability 为粒度关闭 unified runtime adapter，回到 `EarthChunkGenerator`、`CubeNetRuntime`、`SphereFaceRuntime` 旧注册路径。
* payload 协议、客户端 renderer 和 edit persistence 永不在一次回退中同时删除；先恢复旧 runtime，再检查已写入世界的 overlay/index。

**风险**

* server/client 生命周期顺序、READY packet 门控和 chunk staging；loader API 差异；DH/Sodium/Iris 反射/版本门禁；旧 save 的 apron overlay；第三方集成对具体 class 的强引用。

**最小测试矩阵**

* `CubeNetTransitionPayloadTest`、`CubeNetViewCoverageTest`、`CubeNetSeamApproachTest`、`CubeNetApronChunkSyncTest`。
* `SphereFaceRuntimeCollisionTest`、`SphereClientStateTest`、现有 Sphere motion/state tests。
* `python tools/check_cube_net_acceptance.py` 与既有 `:mc1201:test --tests "com.yucareux.tellus.worldgen.CubeNet*" --offline`。
* 复用 acceptance matrix 中同一 Indigo/Sodium/Iris/DH 组合的历史记录；不新增组合，不把 M11 open 当通过。
* P-column/P-biome 两条 parity lane必须在 adapter 开关开启和关闭两种状态均保持门槛。

### 阶段 8：清理与长期维护（可选，延后）

**目标**

* 在至少一个稳定版本周期内证明 unified adapter 与 legacy 输出相同后，再删除重复逻辑和临时桥接。
* 让未来版本只新增 API adapter，不再复制 Earth/Cube generator 主体。

**改动文件范围**

* 删除/收缩已由 contract 覆盖的 generator 私有 sample/decision/writer 方法。
* 保留版本层：`mc*/src/**`、`shared/mc*/src/**` 的 Codec、payload、ChunkAccess、loader 生命周期适配。
* 更新 architecture/status 文档和 dependency check；不删除 parity evidence。

**验收**

* legacy/unified flag 已稳定关闭旧路径后，P-column 仍 `13824/0`，P-biome 仍为显式目标结果。
* `grep`/静态依赖检查显示决策层不再 import `ChunkAccess`/payload/registry；版本层没有复制 decision kernel。
* 所有既有 focused test 和 acceptance static layer 通过。

**回滚点**

* 在删除旧类前保留最后一个兼容 tag；出现问题时恢复 adapter 兼容层，而不是恢复一套复制的生成器。

**风险**

* 清理时误删隐式 side effect、测试 double 或跨版本 codec 边界；文档命令与实际 task 名称漂移。

**最小测试矩阵**

* 阶段 0–7 的完整既有矩阵，不添加新样本；只增加静态 import/dependency 检查。

## 4. 每阶段提交与发布纪律

建议每阶段拆成最多三个可回退提交：

1. **contract/adapter**：只新增中立 records/interfaces 和 legacy adapter；
2. **one consumer**：只替换一个生成器或一个 feature family；
3. **evidence/docs**：记录同一 parity/acceptance 输出，不在同一提交修复已知 bare/BWh 差异。

提交前必须检查：

* `git diff --name-only` 只包含本阶段声明的范围；本轮文档任务则只允许 `docs/architecture/tellus-migration-plan.md`。
* 证据输出带 Git revision、配置 fingerprint、测试命令和 offline/online 状态。
* 任何 parity 数字变化先停止后续阶段，生成差异报告，再决定修复或回滚。
* 版本层迁移不得顺手引入客户端 renderer、payload protocol 变更或新的外部数据依赖。

## 5. 当前建议的落地顺序

1. 先完成阶段 0 的证据冻结；
2. 阶段 1 只读 settings/runtime view；
3. 阶段 2 先把现有 `SphereColumnContext` 用到一个 Cube-Net surface consumer；
4. 阶段 3 接入 Cube-Net preload/spherical sampler，验证 `UNKNOWN` 和 fingerprint；
5. 阶段 4 先抽 `SurfaceDecision`，再抽 biome decision；单独保留 bare/BWh golden case；
6. 阶段 5 按 road/building/tree/landmark/structure 分 family 迁移；
7. 阶段 6 先 Sphere materializer，再 Cube-Net canonical/apron；
8. 阶段 7 最后切 runtime adapter，并逐个替换 `instanceof EarthChunkGenerator` 消费者；
9. 阶段 8 至少延后一个稳定版本周期。

该顺序的核心是：先统一“看见什么”（settings/context/sample），再统一“决定什么”（kernel/planner），再统一“怎么写”（writer），最后统一“什么时候切换/传输”（runtime）。这样每一步都能复用现有 13,824-column、7-case biome 和 Cube-Net acceptance 证据，而不需要扩大测试面。
