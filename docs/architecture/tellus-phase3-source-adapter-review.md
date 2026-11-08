# Tellus Phase 3 Source Adapter 审阅

> 审阅对象：task-29 revision 3 的 injectable source/backend adapter contract。
>
> 审阅任务：task-31。
>
> 结论：**task-29 建立了可注入、可排序、snapshot-only 的 source adapter 边界，但没有连接任何旧 source、generator、cache、executor、network 或 consumer。** `OrderedSampleBackendSelector` 把 precedence 明确为 list order，`SnapshotSampleBackend` 只读取注入函数，`TellusLegacySampleProviderAdapter` 保留 backend 返回的 status/provenance 并验证 request identity。下一步仍需为 Earth/Sphere/Cube 分别绑定真实 source adapter，并以既有 source tests 和 parity comparator 验证顺序。

## 1. 实现范围与验证

### 1.1 新增 source/backend 类型

- `src/main/java/com/yucareux/tellus/worldgen/sample/TellusSourceBackend.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/TellusSampleBackend.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/TellusSourceBackendSelector.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/TellusSampleBackendSelector.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/OrderedSampleBackendSelector.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/SnapshotSampleBackend.java`
- `src/main/java/com/yucareux/tellus/worldgen/sample/TellusLegacySampleProviderAdapter.java`

task-29 同时加入 source adapter focused cases 到现有 `src/test/java/com/yucareux/tellus/worldgen/sample/TellusSampleContractTest.java`，覆盖 first-match、request identity、UNKNOWN/PARTIAL/QUEUED/provenance 保留和 non-blocking no-scheduling。

### 1.2 验证命令

```powershell
./gradlew.bat -PtellusTargets=mc1201 :mc1201:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1211 :mc1211:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1201 :mc1201:test --tests "com.yucareux.tellus.worldgen.sample.TellusSampleContractTest" --offline --no-daemon --console=plain
```

最终重跑结果：mc1201/mc1211 `compileJava` 均 `BUILD SUCCESSFUL`；focused `TellusSampleContractTest` `BUILD SUCCESSFUL`（Gradle `UP-TO-DATE`）。

这证明 typed adapter contract、两个目标的公共编译边界和纯注入测试成立；它不证明真实 `TellusWorldgenSources`、preload package、hydrology、bathymetry、OSM 或任何 generator 已使用 adapter。

## 2. 已覆盖的 adapter 契约

### 2.1 source/backend SPI

`TellusSourceBackend` 定义：

- `backendId()`：诊断/namespace identity；
- `supports(TellusSampleRequest)`：是否拥有该 request；
- `sample(TellusSampleRequest)`：读取一个 immutable `TellusSample`。

`TellusSampleBackend` 是兼容名称的子接口，不新增行为。接口没有 source implementation、Minecraft 类型、cache API、executor、网络 API 或 generator 引用，因此可以在 root `src/main` 中保持版本无关。

### 2.2 precedence selector

`TellusSourceBackendSelector` 只负责按 request 选择一个 backend；`OrderedSampleBackendSelector` 将传入 list 的顺序定义为 source precedence，并返回第一个 `supports` 为 true 的 backend：

- list 顺序稳定且被 defensive copy；
- null backend 在构造时拒绝；
- 无 backend 支持时返回 null；
- selector 本身不调用 `sample`，不触发 source side effect。

这使 preload-first、live fallback、hydrology/bathymetry 或 feature source 顺序可以由上层 factory 明确配置，而不是隐藏在 adapter 的 if/else 中。

### 2.3 snapshot backend

`SnapshotSampleBackend` 是测试/preload snapshot bridge：

- 注入 `backendId`、support predicate 和 snapshot function；
- `sample` 只调用注入 function，不安排 refresh、不等待 future、不访问外部 IO；
- `fixed` helper 可返回 immutable fixture sample；
- function 返回 null 时立即失败，而不是伪造 UNKNOWN。

该类是一个有界 fake/bridge，不代表真实 preload package 已接入。

### 2.4 legacy provider adapter

`TellusLegacySampleProviderAdapter`：

1. 校验 request 非 null；
2. 通过 selector 选择一个 backend；
3. 没有 backend 时返回 `TellusSample.unknown(request, "no legacy backend selected")`；
4. 调用 backend sample；
5. 要求返回 sample 的 `request` 与输入 request 相等，否则抛出明确 `IllegalArgumentException`；
6. 原样返回 UNKNOWN/PARTIAL/QUEUED/AVAILABLE 和 provenance，不做隐式 fallback、status 降级或 scheduling。

这一边界防止 backend 把另一个坐标/geometry/purpose/cache key 的结果混入当前 request，同时保留 source status 的可见性。

## 3. 旧 source/generator/cache/consumer 接入审阅

### 3.1 当前明确未接入

task-29 未改动或调用：

- `TellusWorldgenSources`；
- `SphereChunkSampler`；
- `TerrainPreloadPackageRegistry`、`TerrainPreloadView`、`CubeNetPreloadAdapter`；
- `WaterSurfaceResolver`、`CubeNetInlandWaterResolver`、`CubeNetOceanSurfaceResolver`；
- OSM road/building/water/infrastructure sources；
- `EarthChunkGenerator`、`CubeNetChunkGenerator`、`SphereFaceChunkGenerator`；
- `TellusCommon`、realtime、DH、Voxy、managed terrain；
- existing cache managers、executors、network/payload、runtime lifecycle 或 client path。

没有新增 backend factory、generator registry、global singleton、cache mutation、source refresh 或 consumer replacement。selector/adapter 只有在被调用者显式实例化后才存在，不会改变旧路径。

### 3.2 legacy 仍是唯一行为权威

在真实 source adapter 接入前：

- Earth 继续通过原有 planar/source methods；
- Sphere 继续通过 `SphereChunkSampler` 等现有 path；
- Cube-Net 继续遵守 preload-first、spherical fallback、inland/ocean water 和 existing unknown semantics；
- OSM non-blocking、synthetic sampler property-only、cache fingerprint 和 world settings 语义保持原样。

`TellusLegacySampleProviderAdapter` 的名字表示未来可包裹旧 source，不表示当前已经调用 legacy source。

## 4. Source precedence contract

### 4.1 必须由上层 factory 固定的顺序

`OrderedSampleBackendSelector` 只保证 list order，不预装具体 source precedence。真实 provider factory 必须按 generator family、purpose 和 resolution 明确构造 list：

1. **Cube-Net preload snapshot**：先检查 settings fingerprint、world scale、resolution、coverage 和 owner applicability；命中才返回 snapshot。
2. **live spherical/planar terrain snapshot**：preload miss 后调用既有 elevation、land-cover、land-mask、geology、climate source adapter；miss/failure 保持 UNKNOWN/PARTIAL。
3. **water layers**：inland hydrology、river/lake planner 与 ocean bathymetry 遵循既有合并顺序，不能被 aggregate selector 任意重排。
4. **biome/biome-locate**：使用与 terrain sample 相同的 coordinate/geometry identity；不能因为 biome request 而重新创建另一 owner 或另一 projection。
5. **feature/structure probes**：OSM/landmark/structure source 独立于 terrain column；cache miss、pending 或 unsupported 不产生虚构 geometry。
6. **synthetic backend**：只在现有显式 property/fixture mode 下出现，不能作为生产 geographic fallback。
7. **no match**：adapter 返回 UNKNOWN，并保留 note/provenance；不自动尝试一个未声明的 default backend。

### 4.2 provenance 要求

backend 返回的 `TellusSampleProvenance` 必须记录实际 source/stage：

- preload hit/miss、live Earth/Sphere/Cube source、hydrology/bathymetry、OSM pending 或 unknown；
- source revision/settings fingerprint；
- snapshot/cache hit/queued 状态；
- 不以 adapter 固定 provider ID 覆盖 backend source ID；
- unknown/partial/queued 的 note 保留原始原因。

`TellusLegacySampleProviderAdapter` 目前原样透传 provenance，符合这一 boundary；真实 factory 仍需实现 source-specific provenance。

### 4.3 non-blocking boundary

task-29 adapter contract 继续遵守 task-21：

- selector/support/sample 不同步调度 refresh、网络、IO 或 future；
- `NON_BLOCKING` request 只能读取当前 snapshot；
- pending result 用 QUEUED/UNKNOWN/PARTIAL 表示；
- prepare/prefetch scheduler 如需存在，应在 provider 外部显式注入；
- backend sample 必须返回与输入 request 相同的 immutable request。

### 4.4 adapter boundary 的剩余风险

- `OrderedSampleBackendSelector` 不拒绝重复 `backendId`，同一 source 可能被错误注册两次；factory 应检查 ID 唯一性或在构造时明确允许 alias。
- `supports` predicate 由调用者注入，contract 没有静态证明它是纯函数；真实 factory 应使用无副作用 capability checks，不在 supports 中触发 IO。
- `SnapshotSampleBackend.fixed` 可对宽 predicate 返回同一个 sample；adapter 的 request equality guard 能拦截不同 request，但不能证明 sample provenance 与 selected backend ID 一致。
- 无 backend 时 unknown note 固定为 `no legacy backend selected`；真实 factory 应写入 purpose/geometry/coverage 原因，而不是泛化成 source failure。
- selector 只选一个 backend，不做 layer merge；water/cover/elevation 的跨 source aggregate 规则仍需由更高层 adapter 明确实现。

## 5. Source adapter 接入前置

### 5.1 先建真实 source adapter，仍不接 generator

建议下一阶段按 backend family 增加薄 adapter：

- `CubeNetPreloadSnapshotBackend`：只读 preload snapshot，校验 fingerprint/resolution/coverage；
- `SphericalTerrainSnapshotBackend`：包裹 `SphereChunkSampler`/`TellusWorldgenSources` 的已准备结果；
- `EarthCompatibilitySnapshotBackend`：包裹 Earth planar source，传播 Mercator coverage；
- `HydrologySnapshotBackend`/`OceanSnapshotBackend`：只读已计划 water state；
- `OsmFeatureSnapshotBackend`：保留 non-blocking pending/unknown，不产生 geometry fallback。

这些 adapter 应通过 factory 注入 `OrderedSampleBackendSelector`，但不要在同一阶段替换 generator 主循环或全局 source registry。

### 5.2 request/identity gate

所有真实 adapter 进入 selector 前必须：

- 使用 `TellusSampleRequest.fromContext` 生成 request/cache key；
- 使用 concrete `GeometryIdentity.forProvider` 或明确的 `forRequest` normalization；
- 只接受匹配 geometry/purpose/resolution/owner 的 request；
- 返回 sample 时保持 request identity 相等；
- 不把 Cube-Net apron proxy 当第二 terrain owner；
- 不把 Earth polar compatibility clamp 当 AVAILABLE geographic coverage；
- 不把 preload miss、OSM pending、hydrology unavailable 或 source failure 当 dry/land/sea AVAILABLE。

### 5.3 最小 adapter 测试矩阵

不扩大测试面，使用现有 fixtures/test doubles：

| 类别 | 既有/新增测试 | 门槛 |
|---|---|---|
| selector | `TellusSampleContractTest` first-match cases | list order deterministic；后端未被提前调用 |
| identity | `TellusSampleContractTest` request mismatch cases | 不同 context/request 的 sample 被拒绝 |
| status | existing sample contract tests | UNKNOWN/PARTIAL/QUEUED/AVAILABLE/provenance 原样透传 |
| non-blocking | `nonBlockingBackendReadDoesNotScheduleOrFallback` | sample 不调度、不 fallback |
| preload | `CubeNetPreloadAdapterTest` | fingerprint/resolution miss 保持 UNKNOWN/miss |
| source | `TellusWorldgenSourcesTest`、`SphereChunkSamplerTest` | legacy source result 不变 |
| water | `CubeNetInlandWaterResolverTest`、`CubeNetOceanSurfaceResolverTest` | water precedence 不变 |
| OSM | 既有 OSM/cache tests | pending/miss 不阻塞、不造 geometry |
| parity | Cube/Sphere `13,824 / 0 mismatch` | provider opt-in 与 legacy 对照均 0 mismatch |
| biome | Earth/Cube `7 cases / 1 mismatch bare/BWh` | 不新增 mismatch，不由 adapter 修复 bare/BWh |

### 5.4 接入顺序

1. 只测 injected backend/selector，不启动 Minecraft、不访问外部 source；
2. 以 test doubles 模拟 preload hit/miss、live fallback、water pending、OSM pending；
3. 接入单个真实 snapshot adapter，但继续由 legacy generator 调用；
4. 对照同一 request 的 provider/legacy sample、status、provenance 和 cache key；
5. 重跑 source compatibility tests；
6. 重跑相同 parity comparator；
7. 所有门槛稳定后，才允许一个 generator family opt-in。

## 6. 回滚与未接入边界

### 6.1 当前可承诺范围

- source/backend/selector/provider adapter 可纯注入、可单元测试；
- precedence 由明确 list order 表达；
- request identity mismatch 会被拒绝；
- UNKNOWN/PARTIAL/QUEUED/AVAILABLE 和 provenance 原样透传；
- non-blocking sample 不隐式调度或 fallback；
- 删除 adapter/selector 不需要世界、Codec、cache、存档或 runtime migration。

### 6.2 当前不可承诺范围

- 没有真实 Tellus source adapter；
- 没有 preload/live/hydrology/OSM 的实际 selector factory；
- 没有 generator、cache、executor、network、runtime 或 consumer 接入；
- 没有 source-specific provenance/revision/fingerprint implementation；
- 没有 provider-driven parity replay。

### 6.3 回滚点

1. **Contract-only 回滚**：移除 backend/selector/legacy adapter；旧 source/generator 路径不变。
2. **Injected adapter 回滚**：禁用单个 source backend，恢复 selector 旧列表/legacy source，不删除 preload/cache/OSM/hydrology state。
3. **Precedence 回滚**：若 list order 造成 source selection 变化，恢复旧 list；不修改 source data 或 cache package。
4. **Identity 回滚**：若 request token/geometry mismatch 暴露旧数据问题，停用 provider adapter，保留 legacy request/key path；不得覆盖旧 cache。
5. **Parity 回滚**：任何 `13,824` 或 biome case 数字变化先停止 provider 接入，输出 source/precedence/status diff，回到 legacy path。

## 7. Parity 门槛保持

本次 source adapter 审阅不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

当前 task-29 只有 injected backend/test-double consumer，没有真实 source/generator consumer，因此没有新的 parity 结果。真实 adapter 接入后必须在 provider 与 legacy 两条路径使用同一 comparator；任何新增 mismatch 都是迁移闸门失败，不能以 precedence 重排、UNKNOWN 降级、preload miss 或 cache key 变化解释为通过。

## 8. 审阅结论

Task-29 的 adapter boundary 方向正确：它把 source precedence、snapshot read、request identity 和 status/provenance 透传从具体 source 实现中抽离，同时保持旧 source/generator/cache/consumer 完全未接入。`OrderedSampleBackendSelector` 是显式 first-match precedence；`TellusLegacySampleProviderAdapter` 是 request-checked bridge；`SnapshotSampleBackend` 是纯注入 fixture/snapshot boundary。

下一步应增加真实但只读的 Earth/Sphere/Cube source snapshot adapters，固定 preload-first/live fallback/hydrology/OSM precedence 和 source-specific provenance，再用同一 parity comparator 对照 legacy。只有这些 adapter tests 通过，才能在不扩大测试面的前提下让一个 generator family opt-in，并继续保持 `13,824 / 0`、`7 / 1 bare/BWh` parity 门槛。
