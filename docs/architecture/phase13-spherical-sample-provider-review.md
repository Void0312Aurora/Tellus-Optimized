# Tellus Phase 13 Spherical Sample Provider 审阅

> 审阅对象：task-93 targeted backend/provider implementation与 `TellusSphericalSampleProvider` spherical routing boundary。
>
> 审阅任务：task-92。
>
> 结论：**Phase 13 提供了一个显式、注入式 spherical sample provider，用 route区分 Cube-Net 与 Sphere-Face owner，并支持 delegate-forward与 injected `SphereChunkSampler` 两种来源。owner mismatch返回 UNKNOWN且不调用 delegate；injected sampler 的 NON_BLOCKING request返回 QUEUED且不调用 sampler；blocking sampler结果映射为 immutable TellusSample。provider不调度网络、不刷新cache、不安装 generator/lifecycle hook，legacy fallback与parity baseline保持。**

## 1. 实现范围

### 1.1 Provider API

- `src/main/java/com/yucareux/tellus/worldgen/sample/TellusSphericalSampleProvider.java`
- factory入口：
  - `TellusSampleProviderFactory.forCubeNet(...)`；
  - `TellusSampleProviderFactory.forSphereFace(...)`；
  - `TellusWorldgenSourcesProviderFactory.forCubeNet(...)`；
  - `TellusWorldgenSourcesProviderFactory.forSphereFace(...)`。
- targeted test：
  - `src/test/java/com/yucareux/tellus/worldgen/sample/TellusSphericalSampleProviderTest.java`。

Provider支持两种注入形式：

1. `TellusSampleProvider delegate`：转发既有 provider result；
2. `SphereChunkSampler sampler`：将 `SurfaceData`映射为 Tellus sample。

### 1.2 Route与owner mapping

`Route`只有：

- `CUBE_NET`：拥有 `CUBE_NET_CANONICAL` 与 `CUBE_NET_APRON`；
- `SPHERE_FACE`：拥有 `SPHERE_FACE`。

`EARTH_PROJECTED`、错误 Cube/Sphere owner或其他 owner不由该 route处理。owner mismatch返回：

- `SampleStatus.UNKNOWN`；
- 原 request identity保留；
- diagnostic指出 route不拥有该 owner；
- 不调用 delegate或sampler。

这保持 canonical/apron owner identity，不把 apron误当作独立 spherical face source。

## 2. Request/result mapping

### 2.1 Delegate path

delegate path严格转发：

- provider不会复制或重写 sample status；
- UNKNOWN、QUEUED、PARTIAL、AVAILABLE均保持可观察；
- 返回 sample request必须与输入 request相同，否则抛 `IllegalArgumentException`；
- 不在 provider层把 UNKNOWN/QUEUED升级为 AVAILABLE；
- 不触发第二次 sample、retry或fallback。

### 2.2 Injected SphereChunkSampler path

blocking request调用：

```java
sampler.samplePoint(request.coordinate().geo(), request.resolution().metres())
```

然后映射：

- `SurfaceData.elevationAvailable=false` → `TellusSample.UNKNOWN`；
- finite elevation → `TellusElevationSample.AVAILABLE`；
- `coverClass`同时作为 raw/visual cover → `TellusCoverSample.AVAILABLE`；
- no water → `TellusWaterSample.dry()`；
- water → AVAILABLE water，保留 ocean flag与surface metres；
- provenance source ID为 route provider ID，revision为 `injected-spherical-v1`；
- request identity保持原对象。

该映射只表达 sampler提供的字段，不合成未提供的 geology、climate、biome registry、surface rule或strata。

### 2.3 Resolution/purpose transparency

provider将 request resolution传给 sampler；purpose不在 provider内被改写。BIOME、SURFACE、BIOME_LOCATE等 purpose仍由上游决定；provider只负责 route与sample transport。具体 feature/decision policy留给后续 stages。

## 3. UNKNOWN/QUEUED 与 nonblocking boundary

### 3.1 Unsupported owner

owner mismatch在 source call之前返回 UNKNOWN，避免错误 route访问数据源或污染 provenance。

### 3.2 Injected sampler nonblocking

当 request `nonBlocking()`时，injected sampler path直接返回：

- `SampleStatus.QUEUED`；
- provider ID；
- revision `injected-spherical-v1`；
- queued provenance/note；
- sampler call count为 0。

这表示 provider不把一个不具备 snapshot semantics的 sampler强行当作 nonblocking source。后续 preload/snapshot backend可在已有快照时提供 AVAILABLE/PARTIAL，但本 provider本身不调度填充。

### 3.3 Delegate nonblocking

delegate path不重新解释 nonblocking：delegate返回什么，provider保留什么。测试验证 NON_BLOCKING request得到 queued delegate result，并保留 queued provenance；provider不另行 scheduling。

### 3.4 Unknown source data

sampler无 elevation时返回 UNKNOWN，而不是默认地形、海平面或 stone。delegate unknown同样保持 UNKNOWN。上游 ColumnPipeline/DecisionEngine负责 early stop。

## 4. Strict guards与side-effect boundary

provider构造器验证 provider ID、route和注入对象非空。sample调用验证 request非空，并执行：

1. owner route gate；
2. nonblocking gate（sampler path）；
3. sampler/delegate invocation；
4. result request identity/field mapping。

它不执行：

- 网络请求或隐式下载；
- cache refresh/invalidation；
- executor/future scheduling；
- source registry mutation；
- generator registration、attach、tick、detach；
- ChunkAccess/BlockState/Writer操作；
- FeaturePlanner/DecisionKernel调用。

## 5. Test evidence

Lead 已串行执行 concrete sampler/provider targeted test并报告 `BUILD SUCCESSFUL`。`TellusSphericalSampleProviderTest`覆盖：

- Cube-Net与Sphere-Face factory forward owned request；
- provider ID与request identity保留；
- owner mismatch返回 UNKNOWN且不调用 injected provider；
- delegate UNKNOWN/QUEUED result可观察且无 scheduling；
- injected SphereChunkSampler deterministic SurfaceData mapping；
- water/ocean与surface metres映射；
- sampler nonblocking返回 QUEUED且 sampler调用次数为 0；
- delegate返回错误 request时显式抛错。

该 targeted evidence证明 route/mapping/nonblocking contract，不代表 Earth/Cube biome或完整 spherical generator output parity已经完成。本 review不额外启动测试。

## 6. Legacy、rollback与未接入

### 6.1 Legacy fallback

- provider是显式 factory/provider composition，不自动替换 CubeNetBiomeSource、SphereFace generator或旧 SphereChunkSampler路径；
- owner mismatch/UNKNOWN/QUEUED不会自动调用 legacy writer；
- caller可在 provider result后按既有 policy选择 legacy source或保持 untouched；
- provider不改旧 cache/save/payload/runtime。

### 6.2 Rollback points

1. **Route rollback**：停止 `forCubeNet`/`forSphereFace`显式注入，恢复 legacy provider。
2. **Delegate rollback**：delegate request identity/status异常时禁用 wrapper，直接使用原 provider。
3. **Sampler rollback**：SurfaceData mapping异常时返回 UNKNOWN或停用 sampler path，不合成默认值。
4. **Nonblocking rollback**：snapshot语义未满足时保持 QUEUED，禁止改为 blocking/network call。
5. **Owner rollback**：route owner mismatch保持 UNKNOWN，不扩大 supports范围。
6. **Parity rollback**：任何 spherical opt-in造成 comparator变化，停止 provider consumer，输出 request/sample/decision diff并回到 legacy。

## 7. Generator/lifecycle/default boundary

该 provider没有：

- generator constructor/codec registration；
- runtime registry/factory/explicit registration调用；
- lifecycle bridge/Common/server/world/chunk hook；
- writer/storage/save/reload；
- FeaturePlanner/ColumnPipeline自动接入；
- default global singleton或自动启用。

provider可被上层显式传入 `TellusColumnPipeline`，但该组合不改变 default generator behavior。

## 8. Parity 门槛保持

本次 spherical sample provider review不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

Provider mapping与route tests没有 generator/storage/parity output接入；UNKNOWN/QUEUED/nonblocking policy不是 biome mismatch修复。任何未来 consumer opt-in必须与 legacy path使用同一 comparator，新增 mismatch立即回滚。

## 9. 审阅结论

Task-93 concrete spherical sampler/provider boundary正确：route严格区分 Cube-Net canonical/apron 与 Sphere-Face owner，delegate path保留 request/status，sampler path进行有限 SurfaceData mapping；UNKNOWN/QUEUED可观察，nonblocking sampler不调用底层 sampler且不调度隐式工作。无 generator/lifecycle/default接入，legacy与rollback保持。

Task-92 review完成；后续若与 Phase 10 plan pipeline、Phase 12 target writer或显式 runtime registration串联，必须保留 route ownership、request identity、snapshot/nonblocking语义、status preservation及 `13,824 / 0`、`7 / 1 bare/BWh` parity门槛。
