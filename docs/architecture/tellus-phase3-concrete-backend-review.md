# Tellus Phase 3 Concrete Backend 审阅

> 审阅对象：task-32 revision 3 的 `TellusWorldgenSourcesSampleBackend`。
>
> 审阅任务：task-36。
>
> 结论：**该 backend 是一个 Earth-only、只读、薄 source bridge；Cube-Net 与 Sphere-Face 明确走 UNKNOWN，并把后续 spherical integration 留给 legacy fallback。它仍没有被任何 selector factory、generator、cache manager、runtime 或 consumer 实例化。** 当前实现适合作为 Earth adapter 的 contract fixture/候选 backend，但在接入真实 provider 前仍需解决水层单位、source resolution/coverage、settings identity 和 source exception/provenance 的 API 契约。

## 1. 实现范围与验证

### 1.1 Concrete backend

- `src/main/java/com/yucareux/tellus/worldgen/TellusWorldgenSourcesSampleBackend.java`

该类实现 root sample contract 的 `TellusSampleBackend`，并提供：

- 默认 `EarthGeneratorSettings.DEFAULT` 构造；
- `EarthGeneratorSettings` 注入构造；
- `forEarth(settings)` 工厂；
- `backendId()/providerId()`：`tellus:worldgen-sources`；
- Earth owner support check；
- projected land-cover/elevation read；
- explicit partial/unknown/queued result conversion。

### 1.2 独立验证

```powershell
./gradlew.bat -PtellusTargets=mc1201 :mc1201:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1211 :mc1211:compileJava --offline --no-daemon --console=plain
```

结果：mc1201 与 mc1211 `compileJava` 均 `BUILD SUCCESSFUL`（本轮均为 `UP-TO-DATE`）。

当前未新增 backend-specific test；现有 sample contract test 仅覆盖 injected fake/snapshot backend，不代表真实 Earth source API 已被 fixture 验证。

### 1.3 当前没有 consumer 接入

仓库中 `TellusWorldgenSourcesSampleBackend` 的引用仅在其自身声明、构造器和 `forEarth` 工厂内；没有发现：

- `OrderedSampleBackendSelector` 的真实 factory 注册；
- `TellusLegacySampleProviderAdapter` 或 `TellusSampleProvider` consumer 注册；
- `EarthChunkGenerator`、`CubeNetChunkGenerator`、`SphereFaceChunkGenerator` 调用；
- `TellusWorldgenSources` 全局 source registry 改写；
- preload/cache/hydrology/OSM/executor/network/runtime 接入；
- parity comparator 或 generator output path 改写。

因此该 backend 当前不会改变旧 Earth/Cube/Sphere 生成路径。

## 2. Earth-only support 边界

### 2.1 supports 条件

`supports(request)` 只检查：

```java
request.coordinate().owner().kind() == GeneratorOwner.EARTH_PROJECTED
```

它不会检查 purpose、resolution、geometry revision、projection ID、coverage flag 或 settings scale；这些约束在 `sample(request)` 内继续处理。因此 selector 可以用该 backend 作为 Earth owner 的候选，但真实 factory 仍应在注册/支持判断层加入 geometry/purpose/resolution capability 检查，避免 selector 选中后才返回 UNKNOWN。

### 2.2 Earth projected sampling path

对于 Earth owner，backend：

1. 从 request cache token 读取 Earth floor X/Z；
2. 拒绝超出 `int` 范围的 projected coordinates；
3. 使用 request geometry scale 作为 source scale；
4. 根据 `NON_BLOCKING` 选择 land-cover/elevation 的 `LocalOnly` 或普通 accessor；
5. 根据 `SamplingPurpose.LOD` 决定是否读取 visual cover，否则 visual class 使用 raw class；
6. 使用 raw land-cover `80` 或 no-data 作为 ocean candidate；
7. 使用注入的 Earth settings DEM selection 调用 `TellusWorldgenSources.elevation()`；
8. cover/elevation 已知时输出 PARTIAL layer，而不是虚构完整 AVAILABLE；
9. water layer 目前固定 PARTIAL，阻塞完整 water contract。

这种实现保留了 Earth existing source accessors 和 settings/DEM selection，没有复制 land-cover/elevation 算法。

### 2.3 Cube-Net 与 Sphere-Face fallback

对于 `CUBE_NET_CANONICAL`、`CUBE_NET_APRON`、`SPHERE_FACE`，`sample` 返回 UNKNOWN，并在 note 中标明：

- 当前 `TellusWorldgenSources` bridge 不支持该 owner；
- spherical/Cube-Net integration 仍由 legacy fallback 管理。

这不是把 Cube/Sphere 误判为 dry/land/ocean，也不会让 Earth projected X/Z 被错误用于 spherical owner。真实 selector 需要让该 backend 只作为 Earth candidate；Cube/Sphere 应由后续 spherical/preload backend 或旧路径处理。

## 3. Source API contract blockers

### 3.1 Water layer 单位未闭合

当前 `WaterSurfaceResolver`/相关 water path 输出 projected block Y，而 sample contract 的 `TellusWaterSample.surfaceMetres` 要求 metres。backend 明确不做重解释，固定返回 `TellusWaterSample.partial()`，并在 note 中说明 water conversion 未暴露。

因此该 backend 不能返回完整 AVAILABLE terrain sample，也不能将 projected Y 直接写入 metres。接入 provider 前必须由 source-owned API 明确：

- projected Y → metres 的垂直 scale/sea-level/height-offset conversion；
- ocean/inland/lake authority 的 precedence；
- `enableWater`、DEM selection、water snapshot resolution；
- no-data、pending、coverage unavailable 的 status/provenance。

### 3.2 source resolution 与 `SamplingResolution` 映射未闭合

backend 将 quantized `request.resolution().metres()` 直接传给 existing cover/elevation accessors，但 `SamplingResolution.Kind`（EXACT/PREVIEW/LOD/NON_BLOCKING）并没有完整映射到各 source API 的 resolution semantics：

- `LOD` 只影响 visual cover 分支，未声明 DEM/cover source resolution contract；
- `PRELOAD`、`BIOME`、`BIOME_LOCATE`、`HEIGHTMAP`、`SURFACE` 目前共享同一 accessors；
- `NON_BLOCKING` 选择 LocalOnly，但 source API 是否仅读 snapshot、是否会产生 cache side effect 仍需 source contract 证明；
- power-of-two bucket 与 existing package/native resolution compatibility 还未由 adapter 验证。

真实 adapter factory 必须建立 purpose/kind/resolution 到 source API 的显式映射表，不能只传 metres。

### 3.3 geometry/settings identity 仅部分校验

backend 只比较：

```java
request.geometry().worldScale() == settings.worldScale()
```

它没有验证：

- `GeometryIdentity.projectionId()` 与当前 `EarthProjection.projectionModeId()`；
- `noiseAlgorithmId` 是否适用于 Earth path；
- geometry `id/revision` 与 `EarthGeneratorSettings`/source dataset revision；
- request coordinate 的 `earth.projectionModeId()`/worldScale 与 geometry identity；
- `withinMercatorCoverage` 是否允许当前 Earth source。

`Double.compare` 的 exact scale 检查能避免明显错配，但 provider factory 仍应使用 concrete GeometryIdentity，并在 Earth source adapter 层拒绝 projection/revision/coverage 不一致，而不是等 sample 返回 generic UNKNOWN。

### 3.4 non-blocking result 与 source side effects

`NON_BLOCKING` 分支调用 `sampleCoverClassLocalOnly` 与 `sampleResolvedPreviewElevationMetersLocalOnly`，随后返回 `QUEUED`，water unavailable。这个分层方向正确：不把 partial local snapshot 误报为完整 result。

但 source API 是否绝对不调度、不刷新、不修改 cache 需要 source-owned contract 证明。backend 本身没有 scheduler，但调用 existing LocalOnly accessor 仍应在 adapter tests 中验证：

- no future join；
- no source refresh；
- no network/IO；
- cache miss remains QUEUED/UNKNOWN/PARTIAL；
- later snapshot retry can produce AVAILABLE/PARTIAL；
- provenance distinguishes local-only read from queued result。

### 3.5 exception/provenance contract

backend catches every `RuntimeException` from cover/elevation and returns UNKNOWN with only exception simple class. 这保留了旧生成路径不抛出新异常的边界，但 source-specific diagnosis 仍不足：

- source ID/revision/settings fingerprint 未进入 unknown provenance；
- coverage miss、sentinel、invalid settings、cache failure、source IO failure 被压成同一 note 形状；
- exception message 被省略，后续难以区分 fallback/bug/data miss；
- unknown result 没有标明是 cover/elevation/water 哪一层失败。

真实 adapter 应使用稳定错误分类/来源 ID，避免把外部 exception class 当作长期 provenance schema；同时保留 unknown，不把错误转成 dry/land/ocean AVAILABLE。

### 3.6 Layer aggregation 语义

Earth source cover/elevation layer 已知时均标记 PARTIAL，water 固定 PARTIAL；aggregate 只有 `anyKnown` 时返回 PARTIAL，否则 UNKNOWN。该逻辑符合“不伪造完整 terrain”原则，但 source adapter 前置需明确：

- partial elevation + partial cover + partial water 是否可直接给 decision kernel；
- 只 cover known、elevation unknown 时允许哪些 biome/feature consumers；
- water unavailable 是否阻止 surface/biome decision，还是由 policy 使用 UNKNOWN；
- aggregate provenance 是否标记 source stages separately。

不能让后续 kernel 把 aggregate PARTIAL 当成所有 layer 可读。

## 4. Legacy fallback 与接入边界

### 4.1 当前 fallback

- Earth source API 支持的 projected owner：backend 可作为 future Earth backend candidate；
- Cube-Net canonical/apron：backend 返回 UNKNOWN，旧 Cube-Net preload/spherical/legacy path 继续权威；
- Sphere-Face：backend 返回 UNKNOWN，旧 `SphereChunkSampler`/face path 继续权威；
- Earth water conversion 未闭合：旧 Earth water path 继续权威，backend 不覆盖 water state；
- no backend/unsupported owner：selector/legacy adapter 保留 UNKNOWN，不隐式尝试未声明 backend。

### 4.2 当前不能做的接入

在下列契约闭合前，不应将该 backend 加入真实 `OrderedSampleBackendSelector` 或替换 generator：

1. Water metres conversion 与 ocean/inland precedence；
2. Earth projection/coverage/geometry revision matching；
3. source resolution/purpose mapping；
4. LocalOnly non-blocking side-effect proof；
5. source-specific provenance/error classification；
6. per-layer partial decision policy；
7. Earth source fixture 与 legacy output comparison。

### 4.3 建议的薄 adapter 分层

建议保留该 backend 为 Earth-only backend，再在其外层增加 factory/validator：

- request gate：owner kind、geometry concrete identity、projection mode、world scale、Mercator coverage；
- purpose/resolution map：明确 terrain/biome/preload/LOD/heightmap/feature 的 source API；
- source calls：继续调用 existing `TellusWorldgenSources` accessors，不复制算法；
- water adapter：单独闭合 projected Y→metres 和 water authority；
- provenance adapter：记录 source ID/revision/stage/cache/coverage/error category；
- legacy bridge：unsupported owner 返回 UNKNOWN 并交回旧 path。

## 5. 最小测试矩阵

不扩大测试面，复用现有 fixture/source/parity 证据：

| 类别 | 测试/证据 | 门槛 |
|---|---|---|
| Earth support | 新增 backend unit tests（Earth owner） | supports 仅 Earth；scale/range/owner mismatch 不伪造结果 |
| Cube fallback | 新增 backend unit tests | canonical/apron 返回 UNKNOWN，保留明确 fallback note |
| Sphere fallback | 新增 backend unit tests | sphere owner 返回 UNKNOWN，不调用 Earth source |
| source calls | `TellusWorldgenSourcesTest`、`EarthProjectionTest` | existing cover/elevation accessor 结果不变 |
| resolution | 新增 purpose/resolution mapping tests | quantized bucket/kind 映射显式且稳定 |
| water | `WaterSurfaceResolverTest`/Earth water fixtures | 未完成 metres conversion 前保持 PARTIAL，不返回 fake AVAILABLE |
| non-blocking | source/local-only doubles | no scheduling/IO/join；QUEUED/UNKNOWN 透传 |
| provenance | 新增 source error/sentinel tests | source stage/revision/error category 可诊断 |
| parity | Cube/Sphere `13,824 / 0 mismatch` | backend 未接入 legacy path；未来 opt-in 双路径均 0 mismatch |
| biome | Earth/Cube `7 cases / 1 mismatch bare/BWh` | 不新增 mismatch，不由 backend 修复 bare/BWh |

## 6. 未接入、回滚与 parity

### 6.1 当前可承诺范围

- Earth projected owner 的只读 cover/elevation bridge 已有明确实现边界；
- Cube-Net/Sphere-Face 返回 UNKNOWN，不误报为 Earth terrain；
- non-blocking 路径返回 QUEUED，不把 local-only/pending 结果写成 AVAILABLE；
- partial sentinel/status/provenance 仍可被上层观察；
- 删除 concrete backend 不需要 world/Codec/cache/save/runtime migration；
- 旧 Earth/Cube/Sphere source/generator/cache/consumer 保持行为权威。

### 6.2 当前不可承诺范围

- 完整 water sample（metres）或 water authority precedence；
- Cube-Net/Sphere-Face concrete sampling；
- purpose/resolution 到 existing source API 的完整映射；
- source-specific error/provenance/revision/fingerprint；
- backend 已被 selector/generator/cache/consumer 使用；
- backend-specific parity replay。

### 6.3 回滚点

1. **Backend-only 回滚**：移除/禁用 `TellusWorldgenSourcesSampleBackend`；旧 source/generator path 不变。
2. **Earth adapter 回滚**：若 Earth partial/status/provenance 改变结果，selector 不注册该 backend，继续 legacy Earth source calls。
3. **Water rollback**：water conversion 出现单位/海平面问题时只禁用 water adapter，不回退 cover/elevation legacy behavior。
4. **Fallback rollback**：Cube/Sphere UNKNOWN branch 出现错误 selector routing 时，移除 backend from precedence list；保留旧 Cube/Sphere backend。
5. **Parity rollback**：任何 `13,824` 或 biome case 数字变化先停止 backend opt-in，输出 layer/source/precedence diff，回到 legacy path。

## 7. Parity 门槛保持

本次 concrete backend 审阅不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

当前 concrete backend 没有 selector/generator/cache/consumer 接入，因此没有新的 parity 结果。未来 Earth opt-in 必须与 legacy Earth path 使用同一 comparator；Cube/Sphere 仍以 UNKNOWN/fallback 为当前 contract，不得把 fallback 结果纳入 Earth parity。任何新增 mismatch 都是迁移闸门失败，不能用 partial/UNKNOWN、resolution 映射或 water conversion 未完成解释为通过。

## 8. 审阅结论

`TellusWorldgenSourcesSampleBackend` 的最小边界合理：它复用已有 Earth cover/elevation APIs，只支持 Earth projected owner，Cube/Sphere 返回明确 UNKNOWN，non-blocking 返回 QUEUED，water 单位未闭合时保持 PARTIAL。它没有接入旧 source registry、generator、cache、runtime 或 consumer，因此当前不会改变世界生成行为。

下一步应先闭合 source API blockers（water metres conversion、purpose/resolution mapping、projection/coverage/revision gate、LocalOnly side-effect proof 和 source-specific provenance），再用 Earth fixture 对照 legacy。Cube/Sphere 在拥有 spherical/preload backend 前继续走 UNKNOWN/legacy fallback；所有 opt-in 都必须通过同一 `13,824 / 0` 与 `7 / 1 bare/BWh` parity 门槛。
