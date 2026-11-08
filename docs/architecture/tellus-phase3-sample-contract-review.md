# Tellus Phase 3 SampleProvider Contract 审阅

> 审阅对象：task-17 revision 3，以及 task-20/21/23/26/27 最终 SampleProvider contract。
>
> 审阅任务：task-19 / task-22 / task-25 / task-28 follow-up。
>
> 结论：**task-20/21/23/26/27 已补齐 context-derived coordinate token、preload/biome-locate purpose、量化 resolution、QUEUED 状态、snapshot-only non-blocking、aggregate/layer consistency validator、concrete GeometryIdentity guard、non-invented partial sentinels 及 focused contract tests。** Contract 仍未接入既有 source/generator；source precedence 仍需由后续 adapter 实现并测试。当前主要前置是 source adapter selection/provenance tests 与不扩大测试面的 parity 对照。

## 1. 实现范围与验证

### 1.1 新增类型

- `src/main/java/com/yucareux/tellus/worldgen/sample/CoordinateToken.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/TellusSampleRequest.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/TellusSample.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/TellusSampleConsistencyValidator.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/SampleStatus.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/SamplingPurpose.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/SamplingResolution.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/GeometryIdentity.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/CacheKey.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/TellusSampleProvenance.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/TellusElevationSample.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/TellusCoverSample.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/TellusWaterSample.java`
- `src/test/java/com/yucareux/tellus/worldgen/sample/TellusSampleContractTest.java`

### 1.2 独立编译验证

```powershell
./gradlew.bat -PtellusTargets=mc1201 :mc1201:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1211 :mc1211:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1201 :mc1201:test --tests "com.yucareux.tellus.worldgen.sample.TellusSampleContractTest" --offline --no-daemon --console=plain
```

结果：mc1201/mc1211 compileJava 均 `BUILD SUCCESSFUL`；focused `TellusSampleContractTest` 为 `BUILD SUCCESSFUL`（Gradle `UP-TO-DATE`）。测试覆盖 CoordinateToken/key 维度、resolution buckets、purpose、GeometryIdentity concrete/legacy normalization、partial sentinels、validator rejection、UNKNOWN/PARTIAL/QUEUED/AVAILABLE、snapshot provenance 和 immutable records。

这证明公共 sample contract 可以穿过两个目标的编译边界；它不证明任何 source adapter、preload path、non-blocking queue、cache 或 parity consumer 已经使用 contract。

### 1.3 明确未接入

- `TellusWorldgenSources` 没有 provider implementation 或 factory adapter。
- `SphereChunkSampler`、`CubeNetPreloadAdapter`、`WaterSurfaceResolver`、`CubeNetInlandWaterResolver`、`CubeNetOceanSurfaceResolver` 和 OSM source 没有改为 `TellusSampleRequest`。
- Earth/Cube-Net/Sphere-Face generator 没有改用 provider。
- 没有改变 preload-first、hydrology/bathymetry、OSM non-blocking、synthetic sampler 或 cache behavior。
- task-20/21/23/26/27 只扩展 request/cache/status/consistency/identity contract 和纯 focused tests；没有新增 source adapter、真实 source fixture 或 parity replay。
- 没有改 decision kernel、feature planner、writer、runtime 或客户端路径。

## 2. 已覆盖的 Contract

### 2.1 显式 request 维度

`TellusSampleRequest` 将下列维度固定为不可变字段：

- 已验证的 `TellusCoordinateContext`；
- `SamplingPurpose`；
- `SamplingResolution`；
- `GeometryIdentity`；
- `CacheKey`。

构造器要求 cache key 的 geometry/purpose/resolution 与 request 一致，避免最常见的 request/key 维度错配。

### 2.2 purpose 与 resolution

`SamplingPurpose` 当前包含：

- `TERRAIN_COLUMN`、`PRELOAD`、`BIOME`、`BIOME_LOCATE`、`HEIGHTMAP`、`SURFACE`；
- `LOD`、`FEATURE_PROBE`、`STRUCTURE_PROBE`、`DEBUG`。

`SamplingResolution` 当前包含 `EXACT`、`PREVIEW`、`LOD`、`NON_BLOCKING` 四类，但 metres 已改为正整数的 power-of-two bucket。`SamplingResolution.of/exact/preview/lod/nonBlocking` 会把原始 positive double 向上量化到稳定 bucket；record/cache equality 不再依赖调用者的原始浮点误差。

`NON_BLOCKING` 仍是 request 语义维度，但 task-21 明确它只表示“读取当前 snapshot、没有值时返回 UNKNOWN/PARTIAL/QUEUED”，不在 `sample` 内隐式调度刷新。

### 2.3 geometry 与 cache identity

`GeometryIdentity` 现在保存：

- geometry ID；
- base revision；
- projection ID；
- noise algorithm ID；
- world scale。

`revisionKey()`/`stableKey()` 将 projection/noise identity 与 revision/scale 合并为稳定诊断/cache projection。旧三参构造器仍保留，使用 `unknown-projection`/`unknown-noise` 作为兼容 shorthand；`GeometryIdentity.forProvider(...)` 会拒绝 unknown identity，`forRequest()` 会把 legacy shorthand 规范化为明确的 `legacy-projection-v1`/`legacy-noise-v1`，避免 unknown identity 直接进入 provider cache。

`CacheKey` 保存：

- namespace；
- 结构化 `CoordinateToken`；
- geometry；
- purpose；
- resolution；
- world seed。

`CoordinateToken.fromContext` 从 validated `TellusCoordinateContext` 派生 owner kind、face/block/chunk、Earth floor cell、spherical noise pair 和 Cube-Net canonical/apron view bits；调用者不再提供自由文本 coordinate token。`CacheKey.fromContext` 与 `TellusSampleRequest.fromContext` 集中生成 token/key，request 构造器会拒绝 key 与 context 不一致。

这为 source/cache key 保留 projection/mapping revision、world scale、sampling intent 和 seed 提供了位置。provider factory 应使用 `GeometryIdentity.forProvider` 或 `TellusSampleRequest.fromContext` 的 `forRequest` normalization；直接把三参 unknown shorthand 传入 provider request 会被 `geometry.requireConcrete()` 拒绝。

### 2.4 UNKNOWN、PARTIAL 和 provenance

`SampleStatus` 现在分为：

- `AVAILABLE`：完整可用；
- `PARTIAL`：部分层或部分 source 可用；
- `UNKNOWN`：尚不可用/失败/覆盖缺失；
- `QUEUED`：non-blocking snapshot 尚无值，provider 只报告排队/待准备状态。

`available()` 只表示 AVAILABLE/PARTIAL，`known()`/`complete()` 只对 AVAILABLE 返回 true；`partial()`、`unknown()`、`queued()` 分别保留状态，不再把 PARTIAL/QUEUED 隐式算成完整 known。`TellusSample.sampleRequired()/requireComplete()` 会拒绝 UNKNOWN、PARTIAL、QUEUED。

`TellusSample` 构造器现在自动调用 `TellusSampleConsistencyValidator`：

- AVAILABLE aggregate 要求所有 layers AVAILABLE；
- PARTIAL aggregate 至少有 PARTIAL layer，且不允许 QUEUED layer；
- UNKNOWN aggregate 要求所有 layers UNKNOWN，且不能携带 queued provenance；
- QUEUED aggregate 要求 non-blocking request、所有 layers QUEUED、queued non-snapshot provenance；
- non-blocking AVAILABLE/PARTIAL 必须标记 snapshot provenance；
- 非 QUEUED aggregate 不得携带 queued provenance。

`TellusSample.sampleRequired()/requireComplete()` 会拒绝 UNKNOWN、PARTIAL、QUEUED。`TellusSampleProvenance` 现在显式保存 `snapshot` 与 `queued`，并拒绝 queued+snapshot 同时为 true。provider 可以区分已读取 snapshot、cache hit、unknown 和 queued，而不用从 note 字符串猜测。

`TellusSample.partial()` 现在使用显式 partial sentinels：elevation `NaN`、cover `Integer.MIN_VALUE`、water dry/absent sentinel；它不再制造 `0.0` elevation。需要真实 partial value 的 source adapter 应使用 layer-specific `partial(...)` overload，并在 provenance/note 中说明来源。

## 3. 兼容边界审阅

### 3.1 必须保持的 source precedence

Contract 当前没有绑定具体 source 顺序，因此下一轮 provider implementation 必须明确且测试固定以下顺序：

1. **Cube-Net preload package 优先。** 先查 `TerrainPreloadPackageRegistry.SettingsView`/`CubeNetPreloadAdapter`；settings fingerprint、world scale、resolution 或 coverage 不匹配即视为 miss。
2. **live spherical/planar source 作为 fallback。** preload miss 后才走既有 elevation、land-cover、land-mask、geology、Köppen/ocean climate source；不能用 preload miss 伪造 terrain。
3. **Cube-Net hydrology/bathymetry 保持现有合并顺序。** inland water planner 与 ocean bathymetry adapter 的 existing precedence 不能被 aggregate provider 重排。
4. **OSM/feature source 独立且 non-blocking。** OSM cache miss/未完成不阻塞 terrain sample，也不把空结果升级为真实 geometry；feature planner 仍处理 feature-specific status。
5. **synthetic sampler 仍是显式 property-only。** `tellus.sphere.syntheticSampler` 未开启时不能被 provider 当作 geographic fallback。
6. **failure/coverage unavailable 保持 UNKNOWN 或 PARTIAL。** 不将 source failure、极区 Earth compatibility coverage false、preload load pending 或 OSM pending 转换成 dry/land/sea 的 AVAILABLE 默认。

`TellusSampleProvenance.sourceId/sourceRevision/note` 应记录实际命中的 precedence 分支，例如 preload、live spherical、legacy Earth 或 unknown；不能只写一个恒定 provider ID。

### 3.2 non-blocking 语义已固定为 snapshot-only

`TellusSampleProvider` 现在明确：sample 只读取 provider 当前 snapshot；对 `NON_BLOCKING` request，返回 AVAILABLE/PARTIAL/UNKNOWN/QUEUED，sample 内不隐式调度 refresh/load。这样 contract 的 pure/side-effect-free 语义与既有 source scheduling 被分层：

- registry refresh、OSM query 排队、terrain tile load 和 hydrology cache 更新属于独立 prepare/prefetch/scheduler 层；
- provider sample 调用线程不等待远程数据、不 join future、不触发隐式 IO；
- source ready 后，后续同一 request 可读取新的 snapshot；
- queued/pending 结果通过 status/provenance 表达，不伪造 AVAILABLE dry/land。

下一轮 adapter 可以包装现有 non-blocking source，但必须把调度与 snapshot read 分开；不能在 server tick 或 provider sample 中恢复隐式等待。

### 3.3 aggregate/layer status consistency 已由 task-23/26 固定

`TellusSampleConsistencyValidator` 已在 `TellusSample` record constructor 中运行，验证 aggregate 与 elevation/cover/water layer statuses，以及 non-blocking snapshot/queued provenance。AVAILABLE、PARTIAL、UNKNOWN、QUEUED 的组合规则不再由 provider 自行猜测。

Task-26 将 `TellusSample.partial()` 的 convenience result 改为 explicit non-invented sentinels：elevation `NaN`、cover `Integer.MIN_VALUE`、water dry/absent sentinel。source adapter 若有真实 partial value，应传入 layer-specific overload，而不是把 sentinel 当 terrain value。

仍需注意：

- PARTIAL 允许 PARTIAL + UNKNOWN layers，但禁止 QUEUED layer；decision kernel 仍应逐 layer 读取；
- layer record 的 value/sentinel 规则仍需配合 adapter tests 覆盖；
- aggregate consistency 已固定，但 provider provenance/source precedence 尚未实现。

### 3.4 coordinate token 已改为 context-derived

`CoordinateToken.fromContext` 从 validated `TellusCoordinateContext` 派生 owner kind 与 canonical owner identity、face/block/chunk、Earth floor cell、spherical noise pair 和 Cube-Net canonical/apron view bits。`CacheKey.fromContext` 与 `TellusSampleRequest.fromContext` 集中生成 request key；request constructor 会拒绝 cache token 与 coordinate context 不一致。

这消除了调用者自由拼接字符串导致的跨 owner collision、proxy/canonical 混用和重复 key 风险。仍需保持：

- `GeometryIdentity.revision` 编码 Earth projection mode、Cube mapping revision、noise algorithm 等影响 source identity 的版本；
- apron token 包含 canonical target owner，同时将 proxy view bits 作为 view identity，不创建第二 terrain owner；
- extreme coordinate range 在 context/factory 层被拒绝，而非由 key 生成静默饱和。

### 3.5 resolution 已规范化，执行策略边界仍需固定

`SamplingResolution` 现在以正整数 power-of-two bucket 参与 record equality/cache key；`of(kind, requestedMetres)` 向上量化 raw double，避免 `10.0` 与 `10.0000000001` 产生重复 key。provider/preload supportsResolution 可以直接按 canonical bucket 比较。

仍需由 geometry/provider adapter contract 固定：

- `EXACT`、`PREVIEW`、`LOD` 的 source resolution 语义；
- `NON_BLOCKING` 作为 resolution kind 与 snapshot-only semantics 的关系；
- preload package supportsResolution 的 bucket 映射；
- provider geometry factory 必须使用真实 projectionId/noiseAlgorithmId，不得用三参兼容构造器的 unknown shorthand。

`NON_BLOCKING` 不应触发 scheduling；若未来需要调度，应使用独立 prepare/prefetch API，而不是改变 resolution key。

### 3.6 purpose enum 已覆盖 provider 入口

`SamplingPurpose` 现在显式包含 `PRELOAD` 与 `BIOME_LOCATE`，与 `TERRAIN_COLUMN`、`BIOME`、`HEIGHTMAP`、`SURFACE`、`LOD`、`FEATURE_PROBE`、`STRUCTURE_PROBE`、`DEBUG` 并列。Earth/Cube/Sphere adapter 不应再用同一个 purpose 名称表达不同 source precedence。

仍需在 adapter tests 中固定每个 purpose 对 preload/live source、resolution 和 provenance 的映射；enum 的存在本身不自动实现 precedence。

## 4. Provider implementation 前置条件

### 4.1 先定义 adapter，不接 generator

下一步应新增 provider adapter/factory，但先保持 generator legacy path：

- Earth adapter：把 Earth planar context 映射到现有 `TellusWorldgenSources` sources；
- Sphere adapter：把 spherical owner/context 映射到 `SphereChunkSampler`；
- Cube-Net adapter：显式执行 preload-first，再调用 `cubeNetSurfaceSampler`/inland water/ocean bathymetry；
- all adapters：把 source ID/revision/cache hit/unknown reason 写入 provenance。

adapter 不应在这一阶段替换 `EarthChunkGenerator`、`CubeNetChunkGenerator`、`SphereFaceChunkGenerator` 调用；先用 test doubles 比较 legacy 与 provider request/source selection。

### 4.2 request/cache factory 必须集中

下一步应继续使用 `TellusSampleRequest.fromContext`/`CacheKey.fromContext`，禁止 provider consumer 手动拼 `CoordinateToken`/`CacheKey`：

```java
TellusSampleRequest.fromContext(
    TellusCoordinateContext context,
    SamplingPurpose purpose,
    SamplingResolution resolution,
    GeometryIdentity geometry,
    long worldSeed
);
```

factory 已从 context/owner 生成稳定 token，加入 owner/projection/noise/view identity，并拒绝 cache token 与 context 不一致。接入前仍需拒绝：

- Cube-Net apron 没有 canonical target；
- non-Cube owner 携带 Cube-Net face/planar points；
- Earth compatibility point projection mode/world scale 与 GeometryIdentity 不一致；
- unsupported extreme coordinate range；
- resolution/geometry values 不符合 source package contract。

### 4.3 provider 输出必须是 layer-aware

Provider adapter 不能只看 aggregate `TellusSample.status()`。建议 decision/kernel 侧使用：

- elevation status/value；
- cover status/raw/visual class；
- water status/hasWater/ocean/surface；
- provenance source/stage/cache state；
- aggregate status仅作快速诊断。

`UNKNOWN` 应保持可序列化/可诊断 note，不能在 `unknown()` 后自动调用 dry/land fallback。任何 fallback 都应是 decision policy 的显式规则，并保留原始 status。

### 4.4 non-blocking adapter contract

在复用现有 source 前，固定以下行为：

- sample 调用只读取当前 snapshot，不在调用线程等待远程数据或 join future；
- cache miss/pending 由独立 prepare/prefetch scheduler 处理，sample 返回 UNKNOWN/PARTIAL/QUEUED；
- refresh/load 的 side effect 不改变当前 request 的 cache key；
- same request 在 source ready 后可重试并得到 AVAILABLE；
- provenance 标记 snapshot/cacheHit/queued/sourceRevision；
- provider 不把 pending result 写成 AVAILABLE dry/land。

## 5. Provider 前最小测试矩阵

不扩大测试面，复用现有 source/geometry/parity 证据：

| 类别 | 既有测试/证据 | Contract/Provider 门槛 |
|---|---|---|
| request/cache | 新增纯 request/key tests | CoordinateToken/context、GeometryIdentity projection/noise、purpose/resolution/worldSeed 维度不可错配 |
| status/layers | 新增 validator/layer status tests | UNKNOWN/PARTIAL/QUEUED/AVAILABLE 不压平，aggregate/layer/provenance 合法 |
| purpose/resolution | 新增 enum/bucket tests | PRELOAD/BIOME_LOCATE 显式存在，resolution power-of-two quantization 稳定 |
| Earth source | `TellusWorldgenSourcesTest`、`EarthProjectionTest` | Earth projection mode/coverage/fallback 保持 |
| spherical source | `SphereChunkSamplerTest`、`SphereWorldModelTest` | Geo/owner/noise 同源 |
| Cube-Net preload | `CubeNetPreloadAdapterTest`、`CubeNetInlandWaterResolverTest` | preload-first、fingerprint/resolution miss、UNKNOWN |
| water/ocean | `CubeNetOceanSurfaceResolverTest`、`WaterSurfaceResolverTest` | hydrology/bathymetry precedence 不变 |
| OSM/non-blocking | 现有 OSM source/cache tests | miss/pending 不阻塞、不造 geometry |
| source registry | `TellusWorldgenSourcesTest` | synthetic sampler 仍 property-only |
| parity | Cube/Sphere `13,824 / 0 mismatch` | provider opt-in 与 legacy 对照均 0 mismatch |
| biome | Earth/Cube `7 cases / 1 mismatch bare/BWh` | 不新增 mismatch，不在 provider 中修复 bare/BWh |

### 5.1 不扩大测试面的执行顺序

1. 先跑 request/key/status layer tests，不启动 Minecraft，不访问外部 source；
2. 用现有 synthetic sampler/test doubles 验证 Earth/Sphere/Cube adapter source selection；
3. 用 preload/OSM test doubles 验证 pending/miss/UNKNOWN/non-blocking；
4. 重跑现有 source compatibility tests；
5. 重跑同一 parity comparator；
6. 只有 legacy/provider sample 对照稳定后，才允许一个 generator family 接入 provider。

## 6. 未接入边界与回滚

### 6.1 当前可承诺范围

- request 通过 `fromContext` 明确携带 coordinate、purpose、resolution、geometry、context-derived cache key；
- CoordinateToken 包含 owner/canonical block/chunk、Earth floor、noise pair 和 Cube-Net view identity；
- resolution 使用稳定 power-of-two bucket；purpose 显式包含 PRELOAD/BIOME_LOCATE；
- aggregate/layer sample 具备 immutable status/value/provenance，QUEUED 与 snapshot/queued provenance 可区分；
- provider sample 是 snapshot-only，不隐式调度 refresh/load；
- 未改变现有 source、preload、OSM、hydrology、generator 或 parity 路径；
- 删除 sample package 不需要世界、Codec、cache 或存档迁移。

### 6.2 当前不可承诺范围

- provider 尚未绑定任何 Earth/Sphere/Cube source；
- source precedence、preload/live fallback、hydrology/bathymetry 和 OSM provenance 仍待 adapter 固化与测试；
- GeometryIdentity concrete guard 已存在，但 provider geometry factory 仍需统一使用 `forProvider`/`forRequest`；
- 没有 provider-specific parity replay。

### 6.3 回滚点

1. **Contract-only 回滚**：移除 sample package；所有旧 source/generator 路径不变。
2. **Adapter 回滚**：按 Earth/Sphere/Cube family 禁用 provider，切回 `TellusWorldgenSources`/`SphereChunkSampler`/`CubeNetPreloadAdapter` legacy entry point。
3. **Preload/non-blocking 回滚**：保留 registry/index/OSM/hydrology state，不删除已生成 cache；停止独立 prepare/prefetch scheduler 或 provider adapter read path，不在 sample 内恢复隐式 scheduling。
4. **Cache-key 回滚**：若新 token/resolution key 产生碰撞或重复加载，旧 key namespace 继续可读，新 provider key 停用；不得覆盖旧 package。
5. **Parity 回滚**：任何 13,824-column 或 7-biome 数字变化先停止 provider 接入，输出 layer/source/preference diff，回到 legacy path。

## 7. Parity 门槛保持

本次 SampleProvider contract 审阅不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

当前 package 没有 source consumer，因此没有新的 parity 结果。Provider adapter 接入后必须在 provider 与 legacy 两条路径使用同一 comparator；任何新增 mismatch 都是迁移闸门失败，不能以 `UNKNOWN` 降级、采样重排、cache miss 或 resolution 改名解释为通过。

## 8. 审阅结论

Task-17/20/21/23/26/27 已建立可供 provider adapter 使用的公共 contract：请求维度、context-derived CoordinateToken、purpose、量化 resolution、projection/noise-aware GeometryIdentity、concrete request guard、UNKNOWN/PARTIAL/QUEUED/AVAILABLE layer status、snapshot/queued provenance、aggregate consistency validator、explicit partial sentinels 和 focused contract tests 都有明确类型与验证。task-21 将 sample 固定为 snapshot-only，调度移出 sample 边界；但 source precedence、preload/live adapter、hydrology/OSM provenance 仍需由 Earth/Sphere/Cube adapter 逐项复用并测试。

进入 SampleProvider 实现前，应统一使用 concrete GeometryIdentity factory/request normalization，并用同一 comparator 对照 provider 与 legacy source。当前纯 contract tests 已通过；下一道门槛是 adapter source-selection/provenance tests，之后才能在不扩大测试面的前提下接入一个 generator family，并保持 `13,824 / 0`、`7 / 1 bare/BWh` parity 门槛。
