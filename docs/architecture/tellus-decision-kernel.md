# Tellus 地形 Decision Kernel 与三种写入适配架构

> **审计范围**：`EarthBiomeSource`、`CubeNetBiomeSource`、`CubeNetSurfacePolicy`、`SphereTerrainMaterializer`、`EarthChunkGenerator`、`CubeNetChunkGenerator`、`SphereFaceChunkGenerator`。
>
> **目标**：把“地理数据取样 → 一列地形/水/生物群系/表面材质决定 → 特征规划 → Minecraft 块写入”拆成可组合的 kernel，同时保留现有 Earth、Cube-Net、Sphere-face 的坐标所有权、LOD/预加载、延迟细化和版本桥接行为。
>
> **本文件是设计文档，不改变当前生成行为；当前审计未修改源码。**

## 1. 结论摘要

当前实现已经有大量可复用的纯规则，但规则、缓存、Minecraft API 写入和坐标投影集中在三个 generator 中：

```text
EarthBiomeSource       ─┐
CubeNetBiomeSource      ├─ 取样/水/气候/覆盖/生物群系/洞穴规则（重复但坐标输入不同）
CubeNetSurfacePolicy   ─┘ 版本中立的表面材质规则（最接近现有 kernel）

EarthChunkGenerator ───── 高度网格、水列、坡度/凸度、biome、palette、地质、块写入、道路/建筑/树、延迟状态
CubeNetChunkGenerator ─── canonical/apron 所有权、球面采样、投影/缝合、Cube-Net writer、OSM 特征写入
SphereFaceChunkGenerator ─ 球面 face 入口、缓存、materializer 调用、base column/LOD 查询
SphereTerrainMaterializer ─ radial Y 转换、ColumnPlan、地质 strata、section 写入
```

建议引入四层组合式 kernel：

```text
TellusTerrainSampleProvider
          ↓
TellusColumnDecisionEngine + TellusGenerationContext
          ↓
ColumnDecision（无 BlockState、无 ChunkAccess、无坐标投影副作用）
          ↓
FeaturePlanner → FeaturePlacement（canonical owner + 语义块/范围）
          ↓
EarthColumnWriter / CubeNetColumnWriter / SphereFaceColumnWriter
          ↓
Minecraft 版本兼容的 BlockState/section/materializer
```

核心原则：

1. **坐标所有权先于投影**：Earth 使用 planar Earth 坐标，Cube-Net 先解析 canonical spherical face，Sphere-face 使用 face-local canonical 地址；apron 只能镜像 owner 结果。
2. **provider 只读，engine 只决定，planner 只计划，writer 才写块**。任何 `ChunkAccess`、`WorldGenLevel`、`BlockState` 和 `setBlock` 都不得进入 sample provider 或 decision engine。
3. **semantic material 优先于 `BlockState`**。Earth/Cube/Sphere 的材质决策应返回 `MaterialId`/`SurfaceProfile`，由版本和视图 writer 映射成具体方块。
4. **近景、LOD、biome 查询尽量调用同一个 column decision**；允许不同 `SampleMode`（shell/base/exact/preview），不允许各路径再复制一套覆盖/水/坡度规则。
5. **不建立新的 `TellusChunkGeneratorBase` 或 `TellusBiomeSourceBase`**。Minecraft 的 `ChunkGenerator`/`BiomeSource` 抽象仍由各入口类实现；共享逻辑通过 final service、接口和小型 value object 组合。

## 2. 现有类审计与边界

### 2.1 `EarthBiomeSource`

**现有职责**（`src/main/java/.../EarthBiomeSource.java`）：

- codec、Biome holder lookup、possible biome 集合。
- Earth planar 坐标上的 WorldCover、Köppen、DEM、水列采样。
- `resolveSurfaceBiomeAtBlock`、海洋/河流/雪/坏地/稀有变体分类。
- cave biome、deep dark、structure biome probe。
- chunk biome resolver 与 `findClosestBiome3d` 特殊查询。
- fast spawn mode、structure query ThreadLocal、snow biome cache。

**复用**：

- `BiomeClassification`、`BiomeClassificationProviders` 的 cover+气候分类表。
- `WaterSurfaceResolver` 的水列数据和精确/快速模式。
- `TellusCaveBiomeDepthPolicy` 的深度判定。
- `BadlandsRegionField`、`SnowBiomePolicy`、`RandomBiomeMixer` 和现有 deterministic noise 常量。
- Biome holder/codec 外壳仍由 `EarthBiomeSource` 保留。

**拆分**：

- 把“给定 `TellusColumnSample` 选择 surface biome”迁移到 `TellusColumnDecisionEngine` 的 `BiomeDecisionStage`；`EarthBiomeSource` 变为 `BiomeSource` API 适配器。
- 把水/DEM/cover 读取改由 `TellusTerrainSampleProvider`，不要在 `getNoiseBiome` 中直接访问静态数据源。
- `ResolvedBiomeColumn` 改为共用的 `ColumnDecision`/`ColumnSample` 缓存视图；结构查询只在 adapter 中加查询模式，而不是污染 engine 的常规决策。
- `ThreadLocal` 仅保留 API 查询递归保护（若 Minecraft 回调需要）；column cache 改为每个请求/Chunk context 的局部缓存。

### 2.2 `CubeNetBiomeSource`

**现有职责**（`src/main/java/.../CubeNetBiomeSource.java`）：

- Cube-Net planar quart 坐标到 canonical `SphereBlockAddress` 的解析、apron/singularity gate。
- `SphereChunkSampler.SurfaceData` 采样、地理点到 Earth 投影。
- spherical ocean/inland water、Köppen、visual cover、snow、badlands、rare variants、cave biome。
- Cube-Net settings codec、fallback biome、结构查询模式。

**复用**：

- `SphereWorldModel`、`CubeNetLayout`、`CubeNetApronResolver`、`SphereSingularityPolicy` 的地址/所有权判定。
- `SphereChunkSampler`/`SphereColumnContext` 作为球面列输入。
- 与 Earth 相同的 `BiomeClassification*`、`TellusCaveBiomeDepthPolicy`、`BadlandsRegionField`、`SnowBiomePolicy`、`RandomBiomeMixer`。

**拆分**：

- 将 `getNoiseBiome` 中的“canonical surface → climate/cover → biome → cave”提取为 engine 调用；source 只负责 quart 坐标、fallback、structure probe。
- 不让 engine 依赖 `CubeNetBiomeSource` 本身；通过 `TellusGenerationContext.coordinateAdapter` 和 provider 取得 Earth-equivalent 坐标。
- `inlandWaterSampler` 与 `ClimateSampler` 变成 provider 的可选能力/策略，missing data 仍按现有 fallback 合同处理。

### 2.3 `CubeNetSurfacePolicy`

这是当前最适合成为共享 kernel 的部分：

- `resolve` 已经接受 geodetic point、`SurfaceData`、scale、seed，返回版本中立 `Material`/depth/slope/form。
- `resolveEarthEquivalent` 已经把 Earth visual cover、climate group、zone height、shoreline、biome surface 和 Earth 坐标接入同一套规则。
- `Material`、`Shoreline`、`BiomeSurface`、`Decision` 都是较好的语义 value object。

**建议**：

- 保留阈值和分支顺序，迁移为 `TellusColumnDecisionEngine.SurfaceStage` 的默认实现；短期可由 engine 委托 `CubeNetSurfacePolicy`，避免行为漂移。
- `CubeNetSurfacePolicy.Decision` 逐步兼容/替换为新的 `ColumnDecision.SurfaceProfile`；保留旧 record 的转换适配，先不直接重命名所有调用点。
- `overtureSandAt` 这种非阻塞查询仍只能返回已知命中/未命中/未知；不要把 cache miss 当作硬否定。
- `Decision` 不应携带 `BlockState`，现状已经满足这一点；Earth 的 `SurfacePalette` 应转为同等语义结构。

### 2.4 `SphereTerrainMaterializer`

**现有职责**：

- elevation meters → radial block Y（含 terrestrial/oceanic height scale）。
- `SphereColumnContext` → `ColumnPlan`，包括 surface/water Y、top/filler、depth、strata。
- fresh chunk 校验、`ChunkSectionWriter` 逐列写入、heightmap/unsaved 标记。
- spherical geology → `StrataPolicy.Column`。
- LOD layer 展开和 `CubeNetSurfacePolicy` decision resolver。

**复用**：

- `height` 的 radial 转换和垂直范围验证。
- `StrataPolicy.sphericalColumn`、`StrataMaterials`。
- section writer 的“先 plan、再写”的两阶段原则。

**拆分**：

1. `SphereColumnPlanCompiler`：只把 `ColumnDecision + SphereColumnContext` 编译为 radial `ColumnPlan`，不触碰 chunk。
2. `SphereFaceColumnWriter`：将 plan 写入 face chunk；可继续内部使用当前 `ChunkSectionWriter`，但 writer 不再属于 `EarthChunkGenerator`。
3. `MaterialStateResolver`：`ColumnDecision.MaterialId` → 当前 Minecraft 版本的 `BlockState`。
4. `SphereTerrainMaterializer` 暂作为 facade，转发到 compiler/writer，保持已有调用兼容。

`fill` 的 fresh chunk 检查、heightmap prime 和 unsaved 标记属于 writer 生命周期，不应进入共享 decision engine。

### 2.5 `EarthChunkGenerator`

这是需要拆分最多的类，当前约 15K 行并混合以下职责：

- 生成入口与异步/延迟 refinement 调度。
- 24×24 height grid、preload/local/memory-only/exact 取样和缓存。
- 水列 normalize、shoreline bank ramp、异常地形修复。
- raw/visual/surface cover、坡度/凸度、biome cache。
- full chunk / thin shell / LOD surface palette、badlands bands、snow、strata。
- OSM roads、buildings、street lights、landmarks、trees。
- structures/carvers/flood guards/structure exclusions。
- section direct writer、profiling、prepared state 和 realtime patches。

**保留在 generator 的职责**：

- Minecraft `ChunkGenerator` 生命周期、结构/carver 回调、异步调度、prepared-state 失效和网络刷新。
- Earth-specific chunk bounds、terrain shell bedrock skin、vanilla structure retarget/filter。
- `ChunkAccess`/`WorldGenLevel` 的最终写入协调。

**拆分到 kernel/服务的职责**：

- `sampleSurfaceHeight*`、cover、visual cover、water column 的读取和 fallback：`EarthTerrainSampleProvider`。
- `resolveColumnHeights`、slope/convexity/shoreline/biome/surface palette：`TellusColumnDecisionEngine`。
- `SurfacePalette`、`ColumnHeights` 等结果：`ColumnDecision` 的 Earth adapter view。
- `ChunkSectionWriter`：移到共享 writer 包（或先复制成 package-level final service），不再以 `EarthChunkGenerator` 私有嵌套类作为 Cube/Sphere 依赖。
- roads/buildings/trees 的“查数据、算占用、生成语义放置”：`FeaturePlanner`；Earth generator 只负责调用 planner 和提交 writer。
- 当前 `ChunkGenerationContext`/`ChunkDecorationContext`：迁移为 `TellusGenerationContext` 的 request snapshot + Earth-specific `EarthChunkSnapshot`，避免和新全局 context 同名。

### 2.6 `CubeNetChunkGenerator`

**保留**：

- `CubeNetAddressAdapter`、layout/apron/singularity ownership、canonical chunk 与 derived apron 的生命周期。
- `SphereFaceChunkGenerator[]` 的 face 资源管理和 world seed 设置。
- structures/carvers 的 spherical policy、apron mirror、seam rotation。
- Minecraft generator 回调和 `ChunkAccess` 写入协调。

**迁移/复用**：

- `fillFromCanonical` 和 apron path 都通过 `TellusColumnDecisionEngine` 得到同一 `ColumnDecision`，再交给 `CubeNetColumnWriter`。
- `applyCubeNetShorelineBankRamp` 变为 decision 前的 `ColumnAdjustmentStage`，或者 planner 的地形修正阶段；不能在 canonical/apron 两条路径各算一次。
- `applyCubeNetLandmarkGround` 是 input adjustment，放在 provider/context 的 `surface modifiers` 链；它仍需在 materialization 前生效。
- `applyCubeNetInlandFlow`、badlands bands、cave/carver 属于 writer/post-plan pass；保持 canonical owner only。
- `CubeNetFeatureRasterizer`、`CubeNetFeatureProjector`、`CubeNetRoadVerticalPolicy` 复用到 `FeaturePlanner`/Cube writer，不把投影逻辑复制到每种特征。
- `cubeNetSurfaceDecisionResolver` 应删除为临时 adapter：改为 engine 直接接收 canonical sample、Earth-equivalent climate/biome/shoreline context。

### 2.7 `SphereFaceChunkGenerator`

**保留**：

- face 编号、`SphereFaceTopology`、singularity policy、face address gate。
- sampler/terrain cache、Minecraft `fillFromNoise`/base height/base column 回调。
- face-specific writer 的入口和异步 executor 调度。

**迁移/复用**：

- `terrainGrid` 只负责获得 256 个 `SphereColumnContext`，逐列调用 engine。
- `SphereTerrainMaterializer` 作为 radial compiler/writer facade；surface decision 不再直接由 materializer 默认调用 `CubeNetSurfacePolicy.resolve`，而是由 shared engine 产出。
- `sampleFeatures` 只交给 planner，若 Sphere-face 暂无特征则返回空计划，不引入新的 generator 继承层。

## 3. 核心接口设计（概念 Java API）

以下为设计草案，名称和字段可按当前 source set 的 Java/MC 版本调整；不要求一次性创建所有类。

### 3.1 `TellusTerrainSampleProvider`

provider 是**只读、可缓存、无 Minecraft 写入**的地理输入边界。它可以有 Earth 和 spherical 实现，但不要求继承共同抽象类。

```java
public interface TellusTerrainSampleProvider {
    TellusColumnSample sampleColumn(
        TellusGenerationContext context,
        TellusColumnAddress address,
        TellusSampleRequest request
    );

    default TellusNeighbourhood sampleNeighbourhood(
        TellusGenerationContext context,
        TellusColumnAddress address,
        TellusSampleRequest request
    ) { return TellusNeighbourhood.missing(); }

    default TellusFeatureInputs sampleFeatures(
        TellusGenerationContext context,
        TellusArea area,
        OsmQueryMode mode
    ) { return TellusFeatureInputs.empty(); }
}
```

`TellusColumnAddress` 是坐标所有权的显式 sealed/接口值，而不是一对裸 `int x,z`：

```java
sealed interface TellusColumnAddress
    permits EarthColumnAddress, SphereColumnAddress, CubeNetColumnAddress {}

record EarthColumnAddress(int blockX, int blockZ) implements TellusColumnAddress {}
record SphereColumnAddress(SphereWorldModel.SphereBlockAddress value)
    implements TellusColumnAddress {}
record CubeNetColumnAddress(int planarX, int planarZ) implements TellusColumnAddress {}
```

`TellusColumnSample` 至少包含：

- `address`、可选 `GeoPoint`、`elevationMeters`/`terrainY`。
- raw cover、visual cover、effective surface cover。
- water kind（none/ocean/inland）、terrain/water elevation。
- geology/lithology/map unit（可选）。
- `TellusNeighbourhood`（gradient、relief、convexity 的输入或已算值）。
- Köppen/climate sample（可选，不应强制 provider 在所有模式下载）。
- availability/provenance：exact/preloaded/local-only/memory-only/preview、cache miss/fallback、source revision。

建议 provider 实现：

- `EarthTerrainSampleProvider`：包装 `TellusWorldgenSources`、`TerrainPreloadPackageRegistry`、`WaterSurfaceResolver`、`TerrainHeightTransform`；合并当前 Earth 的多种 sample mode。
- `SphericalTerrainSampleProvider`：包装 `SphereChunkSampler`，输入/输出保留 `SphereColumnContext`；Cube-Net 通过 `CubeNetAddressAdapter` 负责地址转换，provider 不猜 planar ownership。

### 3.2 `TellusGenerationContext`

context 是不可变的**生成请求上下文**；generator 可以持有长期配置，但每个 chunk/LOD/refinement 请求应创建带局部 cache 的 context view。

```java
public record TellusGenerationContext(
    long worldSeed,
    double worldScale,
    int seaLevel,
    int minY,
    int maxYExclusive,
    TellusCoordinateAdapter coordinates,
    TellusSampleMode sampleMode,
    TellusGenerationToggles toggles,
    TellusGenerationCaches caches,
    TellusOwnershipPolicy ownership,
    TellusFeaturePolicy features,
    TellusBlockSemantics semantics
) {
    public TellusGenerationContext {
        if (!(worldScale > 0.0) || !Double.isFinite(worldScale))
            throw new IllegalArgumentException("worldScale");
        if (maxYExclusive <= minY) throw new IllegalArgumentException("vertical range");
    }
}
```

必须放进 context 的东西：

- world seed、scale、sea/min/max Y、terrestrial/oceanic vertical scale。
- 当前模式：`EXACT_CHUNK`、`BASE_TERRAIN`、`TERRAIN_SHELL`、`LOD_PREVIEW`、`STRUCTURE_PROBE`。
- 取样策略、fallback/非阻塞策略、数据 revision/预加载 view。
- 坐标适配器和 owner policy（Earth/Cube/Sphere 不用 `instanceof` 猜）。
- roads/buildings/trees/caves/ore/geology/landmarks 等开关。
- request-local caches：column、neighbour、water、biome、feature；禁止放跨请求可变结果。

不要把 `ChunkAccess`、`WorldGenRegion`、`RandomState` 或 `Holder<Biome>` 注册表查找器放进共享 context；这些属于 MC adapter 或外层 generator。Biome registry 可由 `TellusBiomeResolver` 注入 engine，但 decision kernel 本身应优先携带 `BiomeKey`/语义分类。

### 3.3 `TellusColumnDecisionEngine`

engine 不写块，不调用 Minecraft API；同一输入（context+sample+neighbourhood+seed）必须产生同一结果。

```java
public final class TellusColumnDecisionEngine {
    private final List<ColumnDecisionStage> stages;

    public ColumnDecision decide(
        TellusGenerationContext context,
        TellusColumnSample sample,
        TellusNeighbourhood neighbourhood
    ) {
        ColumnDecision decision = ColumnDecision.initial(sample);
        for (ColumnDecisionStage stage : stages) {
            decision = stage.apply(context, sample, neighbourhood, decision);
            if (decision.terminal()) break;
        }
        return decision.validated(context);
    }
}

@FunctionalInterface
interface ColumnDecisionStage {
    ColumnDecision apply(
        TellusGenerationContext context,
        TellusColumnSample sample,
        TellusNeighbourhood neighbourhood,
        ColumnDecision current
    );
}
```

建议固定阶段顺序：

1. **Availability/coordinate stage**：检查 elevation、vertical range、singularity/ownership；失败返回显式 `fallbackReason`，不能默默生成全空气。
2. **Terrain height stage**：Earth 使用 `TerrainHeightTransform`，Sphere/Cube 使用 radial `SphereTerrainMaterializer.height` 等价策略。
3. **Water stage**：统一 terrain/water Y、ocean/inland、mangrove、shoreline bank/ramp 输入。
4. **Neighbourhood stage**：slope diff、gradient、relief/convexity、snow/mountain zone。
5. **Biome stage**：cover + visual cover + Köppen + shoreline/rare biome + cave eligibility；复用两种现有 BiomeSource 的分类规则。
6. **Surface stage**：委托 `CubeNetSurfacePolicy` 或其 Earth-equivalent 适配，生成 semantic top/filler/depth/form。
7. **Geology stage**：生成 `StrataPlan`，只引用 `StrataPolicy`，不写块。
8. **Overlay stage**：landmark flatten、building foundation、roads/water flow、snow retention 等有明确优先级的列修正。
9. **Diagnostics stage**：记录 provenance、fallback、data misses 和 rule ids，供 shell/refinement 判断是否需要重算。

Earth 与 Cube-Net 应共享阶段实现；差异通过 provider/context 的 `CoordinateAdapter`、`ClimateResolver`、`WaterResolver`、`BiomeResolver` 注入，而不是复制一套大方法。

### 3.4 `ColumnDecision`

`ColumnDecision` 是唯一的 terrain column 语义输出，禁止持有 `BlockState` 或 `ChunkAccess`：

```java
public record ColumnDecision(
    TellusColumnAddress address,
    int terrainY,
    int waterY,
    WaterKind waterKind,
    boolean hasWater,
    BiomeKey surfaceBiome,
    BiomeKey caveBiomeHint,
    SurfaceProfile surface,
    StrataPlan strata,
    TerrainForm form,
    EnumSet<ColumnFlag> flags,
    DecisionConfidence confidence,
    List<DecisionDiagnostic> diagnostics
) {
    public record SurfaceProfile(
        MaterialId top,
        MaterialId underwaterTop,
        MaterialId filler,
        int depth,
        boolean landformSurface,
        boolean useStrata
    ) {}
}
```

`MaterialId` 对应现有 `CubeNetSurfacePolicy.Material` 与 Earth `SurfacePalette` 的语义集合，例如 `GRASS`、`DIRT`、`SAND`、`SANDSTONE`、`RED_SAND`、`TERRACOTTA`、`SNOW`、`POWDER_SNOW`、`STONE`、`GRAVEL`、各种 rock/ice/mud。Earth 当前 `SurfacePalette` 的 `top/underwaterTop/filler/depth/strata` 可一对一迁移。

重要字段语义：

- `terrainY` 是地表实体列顶，`waterY` 是水面顶；无水时 `waterY == terrainY`。
- `surface.depth` 只描述表层 material 写入范围；地下实体/bedrock/strata 由 writer 根据 context 再展开。
- `confidence` 至少区分 `EXACT`、`PRELOADED`、`APPROXIMATE`、`FALLBACK`、`UNAVAILABLE`。
- `flags` 包含 `OCEAN_SUPPORT`、`SHORELINE`、`SNOW_RETAINED`、`BADLANDS_BANDS`、`LANDMARK_FLATTENED`、`SHELL_APPROXIMATE`、`NEEDS_REFINEMENT` 等。
- `diagnostics` 用 rule id 和来源描述，不记录高频字符串日志；可在 profiling/debug 模式启用。

`BiomeSource` 适配器可以从 `ColumnDecision.surfaceBiome` 返回 holder；`getNoiseBiome` 的洞穴 Y 解析只读取 decision 的 terrain/water Y 和 cave policy。

## 4. FeaturePlanner 与 FeaturePlacement

### 4.1 边界与职责

Feature planner 负责“什么 feature 应存在、owner 是谁、占用哪些 canonical cells、每个 cell 的语义写入”；不直接调用 `setBlock`。它可以分成若干小 planner，通过组合注册：

```java
public interface FeaturePlanner {
    FeaturePlacementPlan plan(
        TellusGenerationContext context,
        TellusFeatureInputs inputs,
        ColumnDecisionView columns
    );
}

public record FeaturePlacementPlan(
    List<FeaturePlacement> placements,
    List<FeatureSuppression> suppressions,
    boolean complete,
    List<DecisionDiagnostic> diagnostics
) {}
```

`FeaturePlacement` 应表达 canonical owner、目标坐标、Y 范围和冲突策略：

```java
public record FeaturePlacement(
    FeatureId id,
    FeatureKind kind,
    TellusOwnerKey owner,
    TellusPlacementTarget target,
    int minY,
    int maxY,
    MaterialId material,
    PlacementOperation operation,
    int priority,
    ConflictPolicy conflictPolicy,
    FeatureOrientation orientation,
    FeatureProvenance provenance
) {}
```

- `TellusOwnerKey`：Earth chunk/cell、Sphere face chunk、Cube-Net canonical chunk；apron 不得成为新的 owner。
- `TellusPlacementTarget`：Earth block、sphere face cell、geodetic projected cell 或 canonical feature footprint；由 writer/coordinate adapter 转本地块坐标。
- `Operation`：`SET`、`FILL_SPAN`、`CLEAR`、`REPLACE_IF_AIR`、`MIRROR`、`MARK_POST_PROCESSING`。
- `priority` 示例：foundation/terrain flatten < road deck < building body < roof/detail < landmark authoritative；实际优先级由当前行为表固定。
- `ConflictPolicy`：`OWNER_ONLY`、`REPLACE_AIR`、`REPLACE_TERRAIN`、`PRESERVE_STRUCTURE`、`MIRROR_ONLY`。

### 4.2 现有 feature 复用

- **Trees**：复用 `TellusProceduralTreeGenerator.anchorForCell` 和 `plan`；planner 只输出 anchor/tree plan/预期 surface，Earth/Cube writer 调用现有 block placement 实现或按 plan 展开。当前 Earth `PreparedTreePlacement` 可迁移为公共 placement snapshot。
- **Roads/street lights**：复用 `TellusOsmRoadSource`、`RoadSurfaceStyle`、`StreetLightPlanner`；Cube-Net 继续使用 `CubeNetFeatureRasterizer`，Earth 使用现有 planar rasterizer/road overlay。街灯必须以 canonical owner 为准，apron 仅 mirror。
- **Buildings**：复用 `TellusBuildingProfiles`、`TellusBuildingBlueprints`、`TellusBuildingInteriors`、`TellusBuildingMaterials`、`TellusBuildingFacade`、`ArnisBuildingRules`。`PreparedChunkBuildings` 的 footprint、foundation flatten、spans、tree suppression 应变成 planner 结果，而不是 Earth/Cube 各自重新推导。
- **Landmarks**：复用 `TellusLandmarks` 的 placement/column spans；先作为 terrain adjustment，再作为 feature placement，保持当前 landmark wins over trees/lamps 的顺序。
- **Snow/badlands/flow/geology**：snow cover、badlands bands、Cube inland flow 和 geological strata 属于不同类型的 planner/pass，不能塞进道路/建筑 planner。

### 4.3 planner 不应吞掉的职责

- Vanilla `StructureStart` 生成、retarget、collision filter、carvers 仍由各 generator 的 Minecraft lifecycle adapter 管理。
- planner 不能决定 chunk/apron 是否已加载、是否可写、是否需要网络刷新；这是 writer/generator 的生命周期责任。
- provider cache miss 只能生成 `incomplete/needsRefinement` placement plan；不能把一次 no-data 当作永久无 feature。

## 5. 三种 writer/materializer 适配

定义一个最小 writer 协议，不要求三个实现共享父类：

```java
public interface TellusColumnWriter {
    void beginChunk(TellusGenerationContext context, ChunkWriteTarget target);
    void writeBody(ColumnDecision decision);
    void writeSurface(ColumnDecision decision);
    void writeWater(ColumnDecision decision);
    void writeStrata(ColumnDecision decision);
    void writeFeature(FeaturePlacement placement);
    WriteResult finish();
}
```

这是协议/能力边界，不是 Minecraft generator 基类。具体实现可以是 final class，按版本 source set 提供构造器。

### 5.1 Earth 适配：`EarthColumnWriter`

实现策略：

1. 接收 `EarthColumnAddress` 和 16×16 `ColumnDecision[]`。
2. 复用/抽出当前 `EarthChunkGenerator.ChunkSectionWriter`，执行 solid sections、stone/deepslate、surface span、water span、bedrock、strata。
3. `EarthGenerationMode` 决定 full chunk、thin shell 或 refinement patch；同一 decision 可重复编译，但 patch 必须带 generation stamp 和旧/新 surface 比较。
4. 当前 `applySurface`/`applyThinShellSurface` 的 palette → block 逻辑改为 `MaterialStateResolver` + `SurfaceWritePolicy`；badlands bands/snow cover 作为 writer post-pass。
5. writer 不调用 biome source 重新取 biome；需要的 biome/cover/slope 已在 decision snapshot 中。
6. writer finish 后负责 section recalc、heightmap prime、unsaved；延迟 manager 仍由 Earth generator 持有。

Earth-specific helpers 可先以组合形式保留：`EarthTerrainShellWriter`、`EarthSurfaceWriter`、`EarthFeatureWriter`，不要再次增长 `EarthChunkGenerator`。

### 5.2 Cube-Net 适配：`CubeNetColumnWriter`

实现策略：

1. 先由 `CubeNetAddressAdapter` 把 planar chunk 解析为 canonical face chunk；通过 `SphereColumnContext`/`ColumnDecision` 逐列写 canonical result。
2. `CubeNetSurfacePolicy.resolveEarthEquivalent` 的结果统一来自 decision engine；不要让 `fillFromCanonical` 和 apron path 各调用不同 palette。
3. 通过 `SphereTerrainMaterializer` 的 radial height/strata compiler 生成 body plan，但写入操作交给 Cube writer，以便处理 Cube-Net 的 face-local → planar target。
4. canonical chunk 才运行 feature planner、structures、carvers、landmark/road/building/tree owner；apron chunk 只接收 canonical block/feature mirror，并执行 seam rotation/target local mapping。
5. `applyCubeNetShorelineBankRamp`、landmark flatten 等属于 planning 前的 column modifier；canonical/apron 都读取同一修改后的 decisions。
6. `applyCubeNetInlandFlow`、badlands bands 和 spherical carver 是 canonical post-pass；apron 复制 owner 结果，不再重新采样/再放置。
7. `CubeNetFeatureRasterizer` 继续负责跨 face 的地理几何 raster；writer 只负责把 `FeaturePlacement` 送到当前 chunk，避免经度平面假设。

### 5.3 Sphere-face 适配：`SphereFaceColumnWriter` / `SphereTerrainMaterializer`

实现策略：

1. `SphereFaceChunkGenerator.terrainGrid` 提供 `SphereColumnContext`；engine 输出 `ColumnDecision`。
2. `SphereColumnPlanCompiler` 把 decision 的 semantic surface/strata 转为当前 `ColumnPlan`，使用现有 radial `height`、water Y 和 geology policy。
3. `SphereFaceColumnWriter` 使用 section writer 写 bedrock/body/surface/water，保留 fresh chunk、heightmap、unsaved 约束。
4. `SphereTerrainMaterializer` 暂保留 public facade：`plan`/`fill` 内部转调 engine/compiler/writer。这样可先兼容 `SphereFaceChunkGenerator` 和 Cube-Net LOD 查询，再逐步去掉 materializer 对 `CubeNetSurfacePolicy` 的隐式默认调用。
5. Sphere-face 当前没有 feature decoration 时，planner 返回空 plan；后续增加 feature 仍使用同一 canonical face owner，不在 generator 里引入单独 feature 继承层。

### 5.4 `BlockState` 与版本适配

`MaterialId` 到 `BlockState` 的映射是 Minecraft/版本差异边界：

```java
public interface TellusMaterialStateResolver {
    BlockState resolve(MaterialId material, TellusGenerationContext context);
}
```

- Earth/Cube/Sphere 的 semantic material 集合可以共享。
- `TellusBlockReferences`、`MinecraftVersionCompat`、snow/door/liquid 属性等仍在各版本适配层。
- decision kernel 不应 import `net.minecraft.world.level.block.Blocks`。
- `BlockState` 的方向/液体 level/双层方块属性由 `FeaturePlacement.orientation` 和 writer 解析。

## 6. 明确复用与拆分清单

| 现有类/组件 | 直接复用 | 需要拆出/适配 | 不应做的事 |
|---|---|---|---|
| `CubeNetSurfacePolicy` | 阈值、Material、shoreline/mountain 规则 | 转成 engine SurfaceStage，Decision → ColumnDecision adapter | 不直接写块、不读取 ChunkAccess |
| `SphereChunkSampler` | spherical sample、TerrainGrid、FeatureData | 包装成 `SphericalTerrainSampleProvider` | 不决定 biome/palette，不拥有 chunk |
| `SphereColumnContext` | canonical point/address/surface value object | 可扩展 provenance/metrics view | 不携带 BlockState |
| `WaterSurfaceResolver` | water column、fast/exact、ocean/inland | provider stage/WaterDecisionAdapter | 不在 writer 中二次猜水 |
| `BiomeClassification*` | cover+Köppen 分类 | `BiomeDecisionStage` | 不绑定 Earth/Cube 的坐标入口 |
| `MountainSurfaceRules`、`BadlandsTerrainPolicy`、`SnowBiomePolicy` | 地形形态和材料规则 | surface/landform stages | 不接 Minecraft 写入 |
| `StrataPolicy` | Earth/spherical geology strata | `StrataPlan` 编译器 | 不直接获取 chunk section |
| `EarthBiomeSource` | codec、holder lookup、BiomeSource API | 调 engine/provider，保留 structure probe adapter | 不持有 terrain writer |
| `CubeNetBiomeSource` | codec、fallback、address/fallback gate | 调 engine/provider，保留 spherical quart adapter | 不重复 surface material 规则 |
| `SphereTerrainMaterializer` | radial Y、ColumnPlan、LOD layers、strata | compiler/writer facade | 不隐式成为全局 policy registry |
| `EarthChunkGenerator` | lifecycle、延迟 refinement、structures/carvers、Earth shell | Earth provider/engine/writer/planners | 不承载所有 policy 和 feature 算法 |
| `CubeNetChunkGenerator` | canonical/apron owner、seam mirror、face lifecycle | Cube provider/writer/planner | 不从 apron 重新生成 owner features |
| `SphereFaceChunkGenerator` | face lifecycle、cache、base column API | Sphere provider/writer | 不复制 Cube/Earth decision 分支 |
| `CubeNetFeatureRasterizer` | canonical face rasterization | FeaturePlanner 的 Cube adapter | 不写 WorldGenLevel |
| `TellusProceduralTreeGenerator` | anchor/plan/placement 规则 | Tree planner + writer adapter | 不由每个 view 自行生成新 anchor |
| `StreetLightPlanner` | road-aware lamp plan | FeaturePlanner road pass | 不决定 canonical ownership |
| 建筑 blueprint/interior/material 类 | blueprint、foundation、interior、palette | Building planner + writer | 不在 Cube/Earth 复制 blueprint 逻辑 |
| `ChunkSectionWriter` | 高性能 section direct write | shared writer service | 不作为 generator 的私有嵌套依赖 |

## 7. 避免继承大基类的结构

不要实现：

```text
TellusChunkGeneratorBase
 ├─ EarthChunkGenerator
 ├─ CubeNetChunkGenerator
 └─ SphereFaceChunkGenerator
```

原因：三个入口的 Minecraft 生命周期、坐标所有权、结构/apron 规则和版本 bridge 不同；继承会把可选功能、protected helper 和状态缓存重新耦合，最终形成比当前更大的基类。

采用组合：

```text
EarthChunkGenerator
  ├─ EarthTerrainSampleProvider
  ├─ TellusColumnDecisionEngine
  ├─ EarthColumnWriter
  ├─ FeaturePlanner[]
  └─ EarthLifecycle/Refinement services

CubeNetChunkGenerator
  ├─ SphericalTerrainSampleProvider
  ├─ CubeNetCoordinateAdapter + OwnershipPolicy
  ├─ TellusColumnDecisionEngine
  ├─ CubeNetColumnWriter
  ├─ FeaturePlanner[]
  └─ CanonicalOwner/Mirror services

SphereFaceChunkGenerator
  ├─ SphericalTerrainSampleProvider
  ├─ SphereFaceCoordinateAdapter
  ├─ TellusColumnDecisionEngine
  ├─ SphereFaceColumnWriter (via SphereTerrainMaterializer facade)
  └─ FeaturePlanner[]
```

共享的是接口、final service、record、规则 stage，而不是共享 generator 父类。若需要复用调用序列，使用 `TellusColumnPipeline` final service；若需要版本差异，注入 `TellusMaterialStateResolver` 和版本-specific `ChunkWriteTarget`。

## 8. 迁移顺序（保持当前行为）

### Phase 0：只加语义类型与 golden probes

- 加 `MaterialId`、`ColumnDecision`、`TellusColumnSample`、`TellusGenerationContext` 的最小 records。
- 为 Earth 与 Cube-Net 现有 surface 规则增加 probe：相同输入比较 top/filler/depth/form/biome/water。
- 不改 generator 的实际写入路径；增加旧 `SurfacePalette`/`CubeNetSurfacePolicy.Decision` ↔ `ColumnDecision` converter。

### Phase 1：提取 provider

- `EarthTerrainSampleProvider` 先包装已有 `sampleSurfaceHeight*`、cover、visual cover、水列和 preload；保留旧方法委托 provider。
- `SphericalTerrainSampleProvider` 包装 `SphereChunkSampler` + `SphereColumnContext`。
- provider 输出 provenance/miss，允许 shell/refinement 继续使用现有 warmup ticket。

### Phase 2：提取 decision engine

- 先迁移 `CubeNetSurfacePolicy`（行为最清晰），再迁移 Earth `selectBaseSurfacePalette`/coverDriven palette。
- 迁移水列/坡度/凸度/shoreline/biome stage；保留 source/generator adapter 以验证 parity。
- Earth `fillChunkTerrainMetricsAndBiomes` 改为填充 `ColumnDecision[]`，biome cache 可从 decision 派生。

### Phase 3：抽出 writer

- 先将 `EarthChunkGenerator.ChunkSectionWriter` 移到独立 final service；Earth writer 委托它。
- `SphereTerrainMaterializer` 增加 `ColumnPlanCompiler`/`SphereFaceColumnWriter`，保留 facade。
- Cube canonical/apron 两路径都调用 Cube writer；apron 只做 owner mirror。

### Phase 4：提取 feature planner

- 先树（anchor/plan）、道路/街灯（已有 planner）、landmark，再建筑（blueprint/foundation/interior）。
- 让 planner 输出 immutable placement snapshot；Earth deferred detail 和 Cube canonical decoration 都消费相同 snapshot。
- 保留 vanilla structures/carvers 为各 generator lifecycle，避免一次迁移范围过大。

### Phase 5：删除重复规则与旧嵌套上下文

- 删除 Earth/Cube duplicate `select*SurfacePalette`、`cubeNetSurfaceDecisionResolver` 和重复 water/shoreline decision 分支。
- `EarthChunkGenerator.ChunkGenerationContext` 改名/迁移为 Earth-specific snapshot，避免与 `TellusGenerationContext` 混淆。
- 最后再考虑把 `SphereTerrainMaterializer` 的 facade 方法标记 deprecated；不改变 codec ABI。

## 9. 必须保持的行为不变量

1. **缺数据不变成成功空气**：elevation unavailable、singularity excluded、vertical range invalid 必须是显式 fallback/error/等待状态。
2. **canonical owner 唯一写入**：Cube-Net apron、seam、derived view 不生成第二次 biome/feature/structure。
3. **Earth/Cube surface parity**：同一 Earth-equivalent cover/climate/zone/slope/shoreline 输入，`ColumnDecision.surface` 与现有 Earth palette/Cube `resolveEarthEquivalent` 相同。
4. **LOD/near parity**：LOD 与 chunk 对同一列使用相同语义 material、snow、strata；只允许 sample resolution 和 writer detail 不同。
5. **随机可复现**：所有 noise/feature seed 由 world seed + canonical address/feature id 组成；不得使用 chunk view 坐标导致 face seam 漂移。
6. **水列一致**：terrainY、水面 Y、ocean/inland、mangrove 和 shoreline adjustment 由一个 decision snapshot 驱动。
7. **写入顺序明确**：body/bedrock → water → surface/strata → foundation/road/building → vegetation/details → mirror/post-processing；priority/conflict policy 可审计。
8. **版本隔离**：shared kernel 不依赖具体 MC release 的 bridge；`BlockState`、注册表 holder、section APIs 只在 adapter/writer。
9. **线程安全**：context 和 decisions immutable；缓存按 request 或显式 concurrent service 管理；禁止将 chunk-local mutable arrays 跨异步 refinement 共享而不 clone。

## 10. 验证矩阵

- **Column parity**：Earth、Cube canonical、Sphere face 在代表点比较 `terrainY/waterY/biome/surface/material/depth/strata`。
- **Fallback parity**：preloaded、local-only、memory-only、non-blocking cache miss、exact reload 后的 decision transition。
- **Seam/ownership**：六 face 边界、vertex quarantine、apron rotation、date line、canonical/apron feature 单写验证。
- **Vertical bounds**：minY/maxY、ocean floor support、terrain shell bedrock skin/curtain、water top。
- **Feature determinism**：树 anchor、road raster、building footprint、street light、landmark 与 world seed/feature id 的重复生成。
- **Writer correctness**：section recalc、heightmap prime、fluid post-processing、chunk unsaved、deferred refinement generation stamp。
- **No inheritance leakage**：每个 generator 只依赖各自 lifecycle adapter + shared final services；编译检查 shared 包不 import release-specific `ChunkGenerator` bridge。

## 11. 最小可落地版本

若一次性拆分过大，先实现以下最小核：

1. `TellusTerrainSampleProvider.sampleColumn`（Earth + spherical 两实现）。
2. `TellusGenerationContext`（seed/scale/vertical range/sample mode/coordinates/cache）。
3. `TellusColumnDecisionEngine.decide`，内部先委托现有 `CubeNetSurfacePolicy` 和 Earth palette converter。
4. `ColumnDecision` 含 `terrainY/waterY/waterKind/biome/surface/strata/flags/confidence`。
5. `EarthColumnWriter`、`CubeNetColumnWriter`、`SphereFaceColumnWriter` 三个独立 final adapter。
6. `FeaturePlanner` 先只覆盖 tree/road/building/landmark 的 canonical ownership 和 placement snapshot，实际 block details 可继续由现有 writer helper 展开。

这样可以先证明“同一列决定被三种坐标 view 消费”，再逐步转移大类中的细节；不会通过新建大基类把 Earth/Cube/Sphere 的生命周期和坐标特例重新耦合。
