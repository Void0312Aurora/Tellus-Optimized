# Tellus Phase 2 Coordinate Context / Owner 审阅

> 审阅对象：task-11 Phase 2 coordinate context/owner 类型。
>
> 审阅任务：task-13。
>
> 结论：**Phase 2 已建立清晰的坐标语义拆分和 canonical/apron owner 类型，但当前仍是未接入执行路径的类型契约。** 它可以作为下一轮 SampleProvider 的静态输入边界；在接入 provider 前，需要补充 context factory、跨字段一致性校验和 focused contract tests，避免公开 record 构造器接收互相矛盾的 Geo/Earth/noise/owner 值。

## 1. 实现范围与验证

### 1.1 新增类型

公共层新增：

- `src/main/java/com/yucareux/tellus/worldgen/global/TellusCoordinateContext.java`
- `src/main/java/com/yucareux/tellus/worldgen/global/ImmutableTellusCoordinateContext.java`
- `src/main/java/com/yucareux/tellus/worldgen/global/EarthCompatibilityPoint.java`
- `src/main/java/com/yucareux/tellus/worldgen/global/SphericalNoisePoint.java`
- `src/main/java/com/yucareux/tellus/worldgen/global/GeneratorOwner.java`
- `src/main/java/com/yucareux/tellus/worldgen/global/GeneratorOwnerView.java`
- `src/main/java/com/yucareux/tellus/worldgen/global/EarthOwnerView.java`
- `src/main/java/com/yucareux/tellus/worldgen/global/SphericalOwnerView.java`
- `src/main/java/com/yucareux/tellus/worldgen/global/CubeNetCanonicalOwnerView.java`
- `src/main/java/com/yucareux/tellus/worldgen/global/CubeNetApronOwnerView.java`

已有 `SphereColumnContext` 未被删除，也未接入新的 context；它仍是当前 spherical sample column 的旧数据容器。

### 1.2 验证命令

本轮独立执行：

```powershell
./gradlew.bat -PtellusTargets=mc1201 :mc1201:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1211 :mc1211:compileJava --offline --no-daemon --console=plain
```

结果：两目标均 `BUILD SUCCESSFUL`；mc1201 任务为 `UP-TO-DATE`，mc1211 任务执行成功。

这证明新类型能够在 1.20.1/1.21.1 的公共编译边界中成立；它不证明任何生成器、SampleProvider、decision kernel、writer 或 runtime consumer 已使用这些类型。

### 1.3 明确未接入

- Earth、Cube-Net、Sphere-Face generator 主循环没有改用 `TellusCoordinateContext`。
- `SphereChunkSampler`、`TellusWorldgenSources`、preload、water、climate、OSM source 没有新增 context 参数。
- 没有 SampleProvider、factory、consumer registry、writer 或 runtime lifecycle 修改。
- 没有改变 `EarthProjection`、`SphereWorldModel`、`CubeNetLayout`、`CubeNetApronResolver` 或 seam transfer 行为。
- 没有 parity comparator、真实世界、渲染器、OSM 数据或 acceptance matrix 扩展。

## 2. 已覆盖的坐标契约

### 2.1 四类语义被明确分离

`TellusCoordinateContext` 将一个物理请求拆成：

1. `geo()`：`SphereWorldModel.GeoPoint`，供地理数据源和 spherical physical position 使用；
2. `earth()`：`EarthCompatibilityPoint`，供 EarthProjection/Earth raster/classifier 兼容输入；
3. `sphericalNoise()`：`SphericalNoisePoint`，供跨日期线、跨 face 连续的确定性噪声；
4. `owner()`：`GeneratorOwnerView`，声明数据的权威 owner。

Cube-Net view 坐标通过 optional `facePoint()` 与 `planarPoint()` 提供。接口注释明确 planar coordinates 只是 view，不会把 planar Cube-Net cell 变成 canonical owner。

### 2.2 Earth compatibility point

`EarthCompatibilityPoint` 的有效边界较清楚：

- 使用当前 `EarthProjection.projectionModeId()`；
- 保存连续 block X/Z、world scale、projection mode ID；
- 保存 `withinMercatorCoverage`，可区分极区被 clamp 后的兼容投影和实际 Mercator coverage；
- 提供 `floorBlockX()`/`floorBlockZ()`，使用 floor 而不是 Java truncation。

`fromGeoPoint` 没有缓存、generator 访问或写盘副作用，适合作为纯 coordinate derivation。

### 2.3 Spherical noise point

`SphericalNoisePoint` 将 unit ECEF-like direction 与整数噪声坐标分开保存：

- `unitX/unitY/unitZ` 保留物理方向；
- `noiseX/noiseZ` 保留当前 spherical-noise-v1-round 算法的整数输出；
- `algorithmId` 显式版本化，避免未来更改 rounding/scale 后静默改变 worldgen；
- unit vector、有限值和非空 algorithm ID 有构造校验。

其 derivation 与现有 Cube-Net surface policy 的 ECEF-like coordinate 形式一致，且 elevation 不参与水平 noise identity。

### 2.4 owner 类型

`GeneratorOwnerView` 是 sealed interface，当前 owner 枚举为：

- `EARTH_PROJECTED`：Earth projected compatibility owner；
- `SPHERE_FACE`：一张 canonical spherical face；
- `CUBE_NET_CANONICAL`：Cube-Net canonical owner；
- `CUBE_NET_APRON`：canonical owner 的 derived planar/apron view。

已有 constructor invariants：

- `SphericalOwnerView` 检查 face 范围、block/chunk face 一致性和 floor-div chunk 对应关系；
- `CubeNetCanonicalOwnerView` 检查 face 范围、block/chunk 一致性、face index 对应关系；
- `CubeNetApronOwnerView` 检查 target face/block/chunk projection 指向 canonical owner，并检查 block/chunk projection 的 floor-div 一致性；
- `EarthOwnerView` 至少保证 Earth compatibility point 非空。

### 2.5 immutable context 的 owner/view 组合门控

`ImmutableTellusCoordinateContext` 进行以下组合限制：

- canonical Cube-Net owner 必须同时携带 face point 和 planar point，且与 owner 保存的对应点相等；
- apron owner 必须同时携带 face point 和 planar point，face point 对应 canonical owner，planar point 对应 proxy point；
- Earth/Sphere owner 不得携带 Cube-Net face/planar points；
- optional 值被标准化为不可变 `Optional`。

这使 owner kind 与 Cube-Net view point 的明显错配在构造时失败，而不会等到 writer 或 renderer 才暴露。

## 3. 兼容边界审阅

### 3.1 必须保持的契约

1. **Geo/Earth/noise/view 不可互换。** `EarthCompatibilityPoint` 不能传入 cubed-sphere owner/seam API；`SphericalNoisePoint` 不能当成 Earth raster cell；Cube-Net planar point 不能作为 terrain authority。
2. **owner 是数据权威，不是当前玩家位置。** apron owner 必须始终通过 `canonicalOwner()` 回溯 canonical source；apron 不得生成第二份 terrain/feature/sample authority。
3. **Earth 极区 coverage 必须传播。** 当 `withinMercatorCoverage == false` 时，SampleProvider 应保留 unavailable/unknown 或走现有 spherical source fallback，不能把 clamped Earth block Z 当成精确 Earth grid。
4. **projection mode/algorithm ID 是稳定身份。** provider/cache key 不能只使用 numeric coordinates；至少要保留 projection mode、noise algorithm 和 world scale，避免模式切换后复用旧数据。
5. **负坐标始终 floor-div。** block→chunk、continuous→cell 和 owner consistency 必须复用 `Math.floor`/`Math.floorDiv`，不能被 provider 的 int cast 改成 truncation。
6. **corner exclusion 仍是显式拓扑结果。** context factory 不得为无唯一 face continuation 的 cube vertex 猜测一个 owner。
7. **面边界 ownership 由既有 layout/half-open policy 决定。** `CubeNetLayout.fromPlanar` 对直接边界的拒绝不能由 context 自行改成“最近面”。
8. **不改变现有 source precedence。** context 只携带 coordinates/owner，不改变 preload-first、memory-only、local-only、non-blocking、hydrology/bathymetry 或 OSM cache-miss 语义。
9. **不把 `SphereColumnContext` 与新 context 静默混用。** 过渡 adapter 必须明确是旧 column sample 包装，不能在缺少 source sample 时构造伪造 context。
10. **不接入 runtime。** 当前类型不应安装 tick、chunk callback、payload、ticket、teleport 或 editor sync。

### 3.2 发现的待修正项

#### P1：公共 immutable record 构造器允许跨字段物理点不一致

`ImmutableTellusCoordinateContext` 的构造器只检查每个字段非空，以及 owner 与 Cube-Net face/planar point 的组合关系。它没有验证：

- `earth` 是否由同一个 `geo` 和同一个 projection mode/world scale 推导；
- `sphericalNoise` 是否由同一个 `geo` 和对应 algorithm ID 推导；
- Earth owner 的 point 是否与 `earth` 相同；
- Sphere/Cube owner 的 block address 是否与 `geo` 的 face/position 相符。

因此调用者可以构造一个 `geo=A`、`earth=B`、`sphericalNoise=C` 的“类型正确但物理不一致” context。Phase 2 当前未接入任何 consumer，所以不会改变行为；SampleProvider 接入前应增加受控 factory，例如 `fromGeoPoint(...)`/按 owner kind 分派的 builder，或增加显式一致性校验。原始 record constructor 应只作为经过校验的低层入口。

#### P1：Cube-Net canonical owner 对 face/planar/block 关系的校验仍不完整

`CubeNetCanonicalOwnerView` 检查了 face index 与 block face、block/chunk floor-div 关系，但只要求 face point 的 `u/v >= 0`，没有要求其落在 `[0, faceSize]`，也没有验证 `planarPoint == layout.toPlanar(facePoint)`。这允许越过 face 范围的 face point 或与其不对应的 planar point 进入 canonical owner。

由于 `CubeNetLayout.FacePoint` 本身允许无界坐标以支持 apron 几何，canonical owner 必须额外使用 face size 做有界校验；apron owner 则保留无界/derived 语义。两者不能共享同一个“非负即可”的校验。

#### P1：apron proxy point 与 projection source 的关系没有被完全表达

`CubeNetApronOwnerView` 校验 block/chunk projection 的 target 指向 canonical owner，但 owner record 目前没有保留/核验 source planar cell、source face point 或其与 `proxyPoint` 的转换关系。`ImmutableTellusCoordinateContext` 只检查 `planarPoint == proxyPoint`，无法证明 proxy point 确实来自 `blockProjection`/`chunkProjection` 的 source side。

在接入 feature mirror 或 SampleProvider 前，应让 context factory 统一从 `CubeNetApronResolver` 的 projection 结果构造 proxy point，或为 owner view 增加可验证的 source/view relation。不能允许任意 proxy point 与一个合法 target projection 组合。

#### P2：EarthOwnerView 没有明确 block/chunk owner 粒度

`EarthOwnerView` 只有 `EarthCompatibilityPoint`。对于仅按地理点采样的 Earth path 这足够；对于后续 feature planning、编辑 ownership、写入去重或 chunk-local cache，它无法直接表达 Earth block/cell/chunk owner。

这不应在 Phase 2 立即扩大接口。进入 FeaturePlanner 前应决定：Earth projected owner 是否由 `EarthCompatibilityPoint.floorBlockX/Z` 派生，还是需要一个明确的 Earth cell/chunk view。决定前不要让 planner 把连续 Earth point 当成唯一写入 key。

#### P2：context 未携带采样目的、分辨率和明确 geometry identity

`TellusCoordinateContext` 携带 Geo/Earth/noise/owner/view coordinates，但没有 sampling purpose、resolution meters、world scale 的统一顶层字段。world scale 可从 `EarthCompatibilityPoint` 或外部 settings view 获得，Cube-Net/Sphere path 则需要调用者额外携带 geometry/model。

这对纯坐标类型没有问题，但 SampleProvider 必须显式接受 `SamplingPurpose` 与 resolution，不能从 owner kind 或某个坐标数值猜测 native/preload/LOD/biome-locate/structure-probe。若 provider cache key 需要 geometry ID，应由 settings/runtime view 明确提供并参与 key。

#### P2：没有 context factory/consumer tests

当前 compile 通过，但没有新增测试覆盖：

- 四类坐标由同一 GeoPoint 推导且日期线连续；
- Earth Mercator polar coverage flag 与 legacy projection mode；
- owner block/chunk floor-div 关系；
- canonical/apron face/planar owner 组合；
- mismatched Geo/Earth/noise fields 被拒绝；
- canonical owner 有界 face point 与 apron 无界 point 的差异；
- `SphereColumnContext` 旧路径不会被静默改写。

这些测试应是纯 unit/fixture tests，不增加真实世界、renderer、OSM 或 parity 样本。

## 4. SampleProvider 接入前置条件

### 4.1 先建立受控 context factory

SampleProvider 接入前建议增加 `TellusCoordinateContextFactory`（名称可调整），按输入 view 分派：

- Earth：由 canonical GeoPoint + settings projection mode/world scale 推导 `EarthCompatibilityPoint`，由 `EarthOwnerView` 固定 owner；
- Sphere-Face：由 face/block/chunk address 与 GeoPoint 共同构造 `SphericalOwnerView`，不生成 Cube-Net face/planar points；
- Cube-Net canonical：由 `CubeNetLayout`/`SphereWorldModel`/address adapter 同时生成 canonical face point、planar point、block/chunk owner；
- Cube-Net apron：由 `CubeNetApronResolver` projection 生成 proxy point 与 canonical owner，不重新查询或创建第二 owner。

factory 应把 context 的 Geo、Earth、noise、owner、view fields 一次性生成，禁止调用者分别 new 四个 record 再拼接。

### 4.2 provider API 必须显式传递 purpose/resolution

建议 provider 边界形状为：

```java
TellusSampleProvider.Sample sample(
   TellusCoordinateContext context,
   SamplingPurpose purpose,
   double resolutionMeters
);
```

或将 purpose/resolution 放进不可变 `TellusSamplingRequest`。关键契约：

- `context` 只读，provider 不修改 owner 或坐标；
- `SamplingPurpose` 明确 native、preload、LOD、biome-locate、structure-probe；
- resolution 参与 source selection/cache key；
- `EarthCompatibilityPoint.withinMercatorCoverage` 影响 Earth-grid source 是否可用；
- cache miss、未完成、失败、极区 coverage unavailable 保持 `UNKNOWN`/unavailable；
- 不把 apron context 转成新的 canonical query；通过 `canonicalOwner()` 读取已规划/已采样的 owner 数据。

### 4.3 provider source precedence 不变

下一轮 provider 必须继续遵守现有实现：

1. Cube-Net preload package 优先且 fingerprint/resolution 不匹配即 miss；
2. spherical elevation/land-cover/land-mask/geology/climate 仍由 `TellusWorldgenSources` 后端提供；
3. Cube-Net inland hydrology 与 ocean bathymetry 仍按现有 adapter 合并；
4. OSM 查询继续 non-blocking；cache miss 不发明 feature；
5. synthetic sampler 仍只由显式 system property 启用；
6. source failure/unavailable 不被转换成 dry/land/sea 的隐式默认。

### 4.4 provider 前最小测试矩阵

不扩大测试面，复用已有测试/证据：

| 类别 | 既有测试/证据 | 目的 |
|---|---|---|
| Earth projection | `EarthProjectionTest`、`GlobalMapModelTest` | projection mode、round-trip、floor cell |
| spherical geometry | `SphereWorldModelTest`、`SphereFaceTopologyTest`、`SphereChunkSamplerTest` | Geo/face/address 关系 |
| Cube-Net view | `CubeNetLayoutTest`、`CubeNetAddressAdapterTest`、`CubeNetCrossFaceConsistencyTest`、`CubeNetBoundaryContractTest` | face/planar/seam/owner |
| owner/context | 新增纯 context factory contract tests | mismatch rejection、canonical/apron invariant |
| source compatibility | `TellusWorldgenSourcesTest`、`CubeNetPreloadAdapterTest`、`CubeNetInlandWaterResolverTest` | source precedence/UNKNOWN |
| parity | 现有 Cube/Sphere `13,824 / 0 mismatch` | 不改变 canonical columns |
| biome | 现有 Earth/Cube `7 cases / 1 mismatch bare/BWh` | 不引入第二个 mismatch |

## 5. 未接入边界与回滚

### 5.1 当前可以承诺的范围

- 类型层可以表达 Geo/Earth/noise/owner/Cube-Net view 的不同语义；
- owner constructors 对明显的 block/chunk/face/apron target 错配进行早期失败；
- immutable context 对 owner kind 与 optional Cube-Net view 的明显错配进行早期失败；
- 编译层不依赖新的 Minecraft runtime lifecycle；
- 删除这些未引用类型即可回滚，不需要世界、Codec、cache 或存档迁移。

### 5.2 当前不能承诺的范围

- 尚未证明 Geo/Earth/noise 三个值一定来自同一物理点；
- 尚未证明 canonical face point 与 planar point 的完整布局映射；
- 尚未证明 apron proxy point 来自 projection source；
- 尚未证明 Earth owner 的 block/chunk 粒度；
- 尚未接入 SampleProvider、LOD、biome、feature planner、writer 或 runtime；
- 尚未证明 13,824 columns 或 7 biome cases 通过新的 context 路径（因为没有 consumer）。

### 5.3 回滚点

1. **类型层回滚**：删除/禁用 `TellusCoordinateContext`、owner views 和 immutable implementation；现有 `SphereColumnContext`、EarthProjection、CubeNetLayout 等旧路径保持不变。
2. **factory 回滚**：若下一轮 context factory 已接入但 provider 尚未替换，按 generator family 禁用 factory，继续使用旧 source entry points。
3. **provider 回滚**：若 provider 已接入，保留 legacy source adapter 和旧 cache/source precedence；不要删除 preload index、hydrology cache 或 OSM state。
4. **apron 回滚**：proxy context 出现 owner/mirror 问题时只回退 apron adapter，不回退 canonical coordinate/terrain path。
5. **evidence 回滚**：任何 parity 变化先停止后续迁移，输出字段级 diff，并回到旧 context/source path；不能以“类型重构”作为通过理由。

## 6. 与既有 parity 基线的关系

本审阅不改变既有 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知为 `bare/BWh`。

Phase 2 没有接入 sampling/decision/writer，因此没有新的 parity 结果。进入 SampleProvider 后，必须在 context factory 开启和 legacy path 两种模式下复用同一比较器；任何新增 mismatch 都必须先回滚或单独定位。

## 7. 审阅结论

Phase 2 的核心方向正确：它把 GeoPoint、Earth compatibility grid、spherical noise、view coordinate 和 canonical/apron owner 分成了明确类型，并把常见 block/chunk/owner 错配尽早变成异常。它目前仍是“可编译类型契约”，不是已接入的坐标转换管线。

进入 SampleProvider 前必须先补 context factory 和 focused contract tests，至少解决跨字段物理一致性、canonical face 有界性、apron proxy 来源关系，并明确 Earth owner 的 cell/chunk 粒度。完成后才能安全地把 context 传给 SampleProvider，同时保留 UNKNOWN、preload/source precedence、canonical owner/apron mirror 和既有 `13,824/0`、`7/1 bare/BWh` parity 门槛。
