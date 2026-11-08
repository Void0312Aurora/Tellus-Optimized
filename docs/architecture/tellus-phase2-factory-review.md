# Tellus Phase 2 Factory 审阅

> 审阅对象：task-14 revision 3 的 `TellusCoordinateContextFactory`。
>
> 审阅任务：task-16。
>
> 结论：**task-14 将 Phase 2 从“可构造类型”推进到“受控、纯坐标 factory”，并保持旧生成路径完全未接入。** Factory 能从 spherical block、非 Cube owner、Cube-Net canonical face point 或 planar/apron view 生成经过一致性校验的 context；它不采样、不访问 generator/registry、不改 cache，也没有接入旧 generator、SampleProvider、writer、runtime 或 parity runner。下一轮 provider 接入前仍需补 factory-focused tests，并把低层 owner/context record 构造器限制为受控入口。

## 1. 实现范围与验证

### 1.1 新增 factory

- `src/main/java/com/yucareux/tellus/worldgen/global/TellusCoordinateContextFactory.java`

Factory 提供以下纯入口：

- `fromSphericalBlock(model, block)`：以 spherical block cell center 求 GeoPoint，构造 Sphere owner；
- `fromGeo(model, geo, owner)`：为 Earth/Sphere 非 Cube owner 从同一个 GeoPoint 派生 Earth compatibility/noise views；Cube-Net owner 强制使用 layout-aware overload；
- `fromCubeNetPlanar(model, layout, apronResolver, planarX, planarZ)`：区分 canonical interior、单 cut-edge apron、direct planar edge、vertex、mixed/unresolved strip；
- `fromCubeNetCanonical(model, layout, facePoint)`：构造 canonical Cube-Net interior context。

### 1.2 Factory 行为边界

Factory 注释和实现均保持以下边界：

- 仅做确定性坐标转换和一致性检查；
- 不采样 terrain、biome、climate、water、OSM 或 geology；
- 不加载 registry、generator、chunk 或外部文件；
- 不修改 cache、preload index、hydrology state 或 runtime state；
- 旧 generator 可以未来逐个 opt-in，但当前没有 consumer 依赖 factory。

### 1.3 验证命令

本轮独立执行：

```powershell
./gradlew.bat -PtellusTargets=mc1201 :mc1201:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1211 :mc1211:compileJava --offline --no-daemon --console=plain
```

结果：两目标均 `BUILD SUCCESSFUL`；mc1201 为 `UP-TO-DATE`，mc1211 执行编译成功。

编译证明公共类型、factory 与两个版本的现有 API 边界成立，不证明 factory 已被 generator、provider 或 parity consumer 使用。

### 1.4 明确未改变的旧路径

- Earth、Cube-Net、Sphere-Face generator 主循环仍未调用 factory。
- `SphereColumnContext` 仍是旧 spherical sample column 容器，没有隐式转换。
- `SphereChunkSampler`、`TellusWorldgenSources`、preload、water、climate、OSM source 未改为 context 输入。
- `EarthProjection`、`SphereWorldModel`、`CubeNetLayout`、`CubeNetApronResolver` 和 seam transfer 行为未改写。
- 没有 SampleProvider、decision kernel、feature planner、writer、runtime lifecycle 或 consumer registry 接入。
- 没有新增真实世界、renderer、OSM 数据或 parity case。

## 2. Factory 已覆盖的契约

### 2.1 同一 GeoPoint 派生 Earth/noise views

`fromGeo` 从一个 canonical `GeoPoint` 派生：

- `EarthCompatibilityPoint.fromGeoPoint(geo, model.worldScale)`；
- `SphericalNoisePoint.fromGeoPoint(geo)`。

对 Earth owner，factory 复核 supplied owner point 与 expected Earth point 相等；对 Sphere owner，factory 使用 `model.blockForGeo(geo)` 复核 block identity。Cube-Net owner 不允许走无 layout 的入口，避免将 planar view 当成 canonical owner。

### 2.2 spherical block owner

`fromSphericalBlock`：

- 先验证 model chart 可解析该 face/block；
- 使用 `Math.floorDiv` 派生 chunk X/Z；
- 在 block center（`x + 0.5`, `z + 0.5`）取得 exact surface point；
- 通过 `SphericalOwnerView` 保持 block/chunk face 和 floor-div 一致；
- 再通过 `fromGeo` 验证 GeoPoint 与 block owner 对应。

该入口明确是 cell-center 请求，不应被 SampleProvider 当成任意连续 block corner 或 LOD cell center，除非 provider contract 明确采用同一 anchor。

### 2.3 Cube-Net canonical

`fromCubeNetCanonical` 和 planar interior 分支：

- 校验 layout face size 与 spherical model face blocks 相等；
- 要求 canonical face point 严格落在 `(0, faceSize)` interior，避免直接 edge ownership ambiguity；
- 使用 `layout.toPlanar(facePoint)` 生成 planar point；
- 以 `floor(facePoint.u/v)` 生成 block address，再用 floor-div 生成 chunk address；
- 通过 `model.exactSurfacePoint` 生成 GeoPoint；
- 最后复核 Earth/noise view、canonical owner block 与 `model.blockForGeo(geo)` 一致，且 planar point 与 face point 可逆对应。

这将 canonical face point 的有界语义和 apron 的无界语义分开，符合 Cube-Net 直接边界/角点的现有拓扑策略。

### 2.4 Cube-Net planar/apron

`fromCubeNetPlanar` 的判断顺序是：

1. finite planar input；
2. `layout.fromPlanar` 成功则走 canonical interior；
3. 否则查询 `CubeNetApronResolver.resolve`；
4. 以 planar floor 派生 source chunk，并查询 `resolveChunk`；
5. block/chunk projection provenance 不一致则返回 empty；
6. 用 seam/layout 验证 source side、target face、edge coordinate、penetration、mapped target point；
7. 构造 canonical target owner，再包装为 `CubeNetApronOwnerView`；
8. 使用 canonical target GeoPoint，保留当前 planar point 作为 proxy view。

因此 direct planar neighbor、net edge、cube vertex、mixed chunk 或 unresolved strip 可以明确得到 `Optional.empty()`，而不是被猜测成某个面或第二 owner。apron 不发起第二份 terrain query。

## 3. Provider 接入兼容边界

### 3.1 必须保持的 source/owner 规则

1. **Factory 只建立坐标，不建立 sample。** provider 仍是 elevation/cover/land-mask/geology/climate/water 的唯一采样层。
2. **canonical owner 唯一。** apron context 必须通过 `canonicalOwner()` 回溯 canonical source；provider 不因 proxy point 重复查询或产生第二 cache key。
3. **UNKNOWN/unavailable 保留。** Mercator polar coverage false、preload miss、异步 OSM 未完成、hydrology/bathymetry unavailable 不转换成 dry/land/sea 默认。
4. **source precedence 不变。** Cube-Net preload fingerprint/resolution、spherical source、inland hydrology/ocean bathymetry、OSM non-blocking 和 synthetic sampler property 语义均不变。
5. **projection/noise identity 参与 key。** provider/cache key 需保留 world scale、Earth projection mode、spherical noise algorithm、generator geometry identity 和 sampling resolution；不能只用 numeric X/Z。
6. **sampling purpose 显式传递。** context 本身不猜 native/preload/LOD/biome-locate/structure-probe；provider request 必须显式携带 purpose 和 resolution。
7. **不把 factory 接到旧路径。** provider 接入必须先经过 factory contract tests；旧 generator 路径保留 legacy source adapter 作为可回退实现。

### 3.2 当前未接入的 runtime/feature 边界

Factory 不负责：

- Cube-Net PREPARE/STAGED/COMMIT/RELEASE、seam movement、apron ticket 或 payload；
- `TellusCommon`、`TellusRealtimeManager`、DH、Voxy、managed terrain；
- road/building/tree/landmark/structure planner；
- column/feature writer、BlockState、heightmap、light 或 block entity；
- client renderer、Sodium、Distant Horizons 或 Sphere-Face grouped routing。

这使 task-14 可在不改变既有生成运行时的情况下完成坐标契约收敛。

## 4. 发现的待修正项

### P1：低层 record 构造器仍可绕过 factory

`ImmutableTellusCoordinateContext`、`CubeNetCanonicalOwnerView` 和 `CubeNetApronOwnerView` 的公开构造器仍可被调用者直接使用。Factory 路径已完成 Geo/Earth/noise/owner/projection 的联合校验，但直接构造仍可能绕过这些检查。

在 SampleProvider 或 feature planner 接入前，需选择一种明确契约：

- 将低层构造器收紧为 package-private，并只公开 factory；或
- 保留 public records，但文档/API 明确只允许已验证的 low-level callers，并增加 negative contract tests；或
- 增加不可伪造的 validated marker/builder，避免任意 records 被当作 provider context。

不能让不同 consumer 自己分别 new Geo/Earth/noise/owner 再拼 context。

### P2：`fromCubeNetPlanar` 的连续输入范围仍依赖 layout/runtime 约束

Factory 只检查 planar X/Z finite，`floorChunk` 内部将 floor 后坐标转为 int。正常 Cube-Net layout/world border 范围内没有问题，但极端大 finite double 可能在 double→int 转换时饱和，造成错误 chunk provenance。

在 provider 暴露连续输入前，应增加可接受 block/chunk range 校验，或把 `floorChunk` 改为 long-range intermediate 后明确拒绝超出 Minecraft/chunk API 可表达范围的输入。该边界不能由 provider 静默 clamp。

### P2：Factory 的 sampling anchor 需要写入 provider contract

`fromSphericalBlock` 使用 block center；`fromCubeNetCanonical` 使用 face-local continuous point，再以 floor 得到 block；`fromCubeNetPlanar` 的 apron context 使用 proxy planar point，但 GeoPoint 使用 canonical target face point。provider 必须知道：

- native block sample 使用哪一个 anchor；
- apron sample 的 coordinate view 是 proxy，但 data owner 是 canonical；
- LOD/preload 不应复用 native block-center 假设；
- face boundary/edge/vertex 的 empty 结果如何传播。

如果未明确 anchor，后续 sample provider 可能用同一个 context 产生不同的 source grid/cache key。

### P2：Factory 仍未表达 sampling purpose/resolution/geometry view

这是有意保留的分层，但 provider API 必须补上 `SamplingPurpose`、`resolutionMeters` 和必要的 geometry identity。不要把这些字段临时塞入 `TellusCoordinateContext` 的 coordinate records，也不要从 owner kind 猜测。

### P2：当前只有 compile evidence，没有 factory unit tests

应补充纯 contract tests，至少覆盖：

- `fromGeo` Earth owner、Sphere owner 与 mismatch rejection；
- `fromSphericalBlock` center anchor、floor-div chunk 和 block/Geo inverse；
- canonical face point interior、edge/vertex rejection；
- canonical planar ↔ face mapping；
- direct planar edge/neighbor、vertex、mixed chunk、unresolved strip 返回 empty；
- apron block/chunk/source seam provenance；
- proxy context canonical owner/GeoPoint/planar relation；
- model/layout face size mismatch；
- finite/extreme planar input；
- `SphereColumnContext` legacy path 不变。

## 5. Provider 前置测试矩阵

不扩大测试面，复用已有 geometry/source/parity evidence：

| 类别 | 既有测试/证据 | Factory/provider 接入门槛 |
|---|---|---|
| Earth projection | `EarthProjectionTest`、`GlobalMapModelTest` | projection mode、Mercator coverage、floor cell 不变 |
| spherical geometry | `SphereWorldModelTest`、`SphereFaceTopologyTest`、`SphereChunkSamplerTest` | face/block/chunk/Geo identity 不变 |
| Cube-Net topology | `CubeNetLayoutTest`、`CubeNetAddressAdapterTest`、`CubeNetCrossFaceConsistencyTest`、`CubeNetBoundaryContractTest` | interior/edge/apron/corner owner 规则不变 |
| factory contract | 新增纯 factory tests | 所有 mismatch/empty/provenance 门槛通过 |
| source compatibility | `TellusWorldgenSourcesTest`、`CubeNetPreloadAdapterTest`、`CubeNetInlandWaterResolverTest` | UNKNOWN、preload-first、non-blocking 不变 |
| parity | Cube/Sphere `13,824 / 0 mismatch` | factory opt-in/legacy 两路径均 0 mismatch |
| biome | Earth/Cube `7 cases / 1 mismatch bare/BWh` | 不新增 mismatch，不在 factory 中修复 bare/BWh |

### 5.1 不扩大测试面的执行顺序

1. 先运行 factory-only tests，不启动 Minecraft、不加载外部 OSM/DEM、不接 provider；
2. 在既有 synthetic/test doubles 上接一个 provider adapter，比较 legacy 与 factory-derived context 的 source request；
3. 重跑原有 source compatibility tests；
4. 重跑同一 parity comparator；
5. 只有上述门槛稳定，才允许逐个 generator family 接入 SampleProvider。

## 6. 未接入边界与回滚

### 6.1 当前可以承诺的范围

- factory 是纯 deterministic coordinate/provenance validator；
- Cube-Net canonical/apron owner 不因 planar input 创建第二 owner；
- 非 Cube owner 的 Earth/noise views来自同一 GeoPoint；
- 直接边界、corner、mixed/unresolved apron 输入可返回明确 empty；
- 删除/禁用 factory 不需要世界、Codec、cache 或存档迁移；
- 旧生成路径、旧 sample source 和旧 parity runner 保持行为权威。

### 6.2 当前不能承诺的范围

- factory 尚未被 generator/provider/feature/writer/runtime consumer 使用；
- 没有 factory-specific unit test evidence；
- 没有证明 factory-derived context 下 13,824 columns 或 7 biome cases 已重放；
- 没有提供 SamplingPurpose/resolution API；
- 没有改变或验证 Cube-Net client/apron visual/runtime behavior；
- 没有完成 Sphere-Face grouped routing 或 Earth feature ownership。

### 6.3 回滚点

1. **Factory-only 回滚**：移除/禁用 `TellusCoordinateContextFactory`；旧 `SphereColumnContext`、EarthProjection、CubeNetLayout 和 source entry points 不变。
2. **Provider adapter 回滚**：按 generator family 关闭 factory-derived request，切回 legacy sample provider；保留 cache/index/hydrology/OSM 状态。
3. **Apron 回滚**：proxy provenance 或 canonical mirror 出现问题时只禁用 apron factory branch，canonical interior/Sphere/Earth branch 保持旧路径。
4. **Parity 回滚**：任何 13,824-column 或 7-biome 数字变化先停止 provider 接入，输出字段级 diff，回到 legacy source path。
5. **Runtime 回滚**：factory 不持有 lifecycle state；不需要释放 ticket/callback/payload。未来 provider/runtime 接入仍需独立 legacy flag 和 detach 顺序。

## 7. Parity 门槛保持

本次 factory 审阅不改变现有 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

Factory 当前没有 consumer，因此没有新的 parity 结果。进入 provider 后必须在 factory-derived 与 legacy 两条路径复用相同 comparator；任何新增 mismatch 都是迁移闸门失败，不能以“坐标类型更严格”或“采样重排”解释为通过。

## 8. 审阅结论

Task-14 factory 已把 Phase 2 的关键一致性从调用者责任提升为纯、可验证的 coordinate construction：Geo/Earth/noise 同源、Cube-Net layout/model 匹配、canonical/apron provenance 和 edge/vertex/mixed strip policy 都在 factory 边界表达。更重要的是，它仍未接入旧 generator/sample/runtime 路径，因此没有改变现有生成行为。

下一步应先补 factory contract tests，并固定 sampling anchor、purpose、resolution 和 geometry/cache key 约定，再接入 SampleProvider。接入过程中必须保持 legacy fallback、UNKNOWN/preload/source precedence、canonical owner/apron mirror，以及 `13,824 / 0`、`7 / 1 bare/BWh` parity 门槛。
