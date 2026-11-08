# Tellus Phase 4 DecisionKernel 审阅

> 审阅对象：task-40 revision 3 的纯 column decision contract。
>
> 审阅任务：task-42。
>
> 结论：**Phase 4 建立了一个 Minecraft-free、source-free、writer-free 的确定性 column decision engine。它只接受 `TellusGenerationContext`、validated `TellusCoordinateContext` 和 immutable `TellusSample`，输出 `ColumnDecision`；UNKNOWN/PARTIAL/QUEUED 都有显式状态和 semantic outputs。当前没有接入任何 generator、FeaturePlanner、Writer、runtime 或 parity path。下一轮必须先固定 layer-aware PARTIAL policy、source provenance 使用和 FeaturePlanner/Writer 的 owner/status 前置，再考虑 generator opt-in。**

## 1. 实现范围与验证

### 1.1 DecisionKernel 类型

- `src/main/java/com/yucareux/tellus/worldgen/decision/TellusGenerationContext.java`
- `src/main/java/com/yucareux/tellus/worldgen/decision/TellusColumnDecisionEngine.java`
- `src/main/java/com/yucareux/tellus/worldgen/decision/ColumnDecision.java`
- `src/main/java/com/yucareux/tellus/worldgen/decision/DecisionStatus.java`
- `src/main/java/com/yucareux/tellus/worldgen/decision/BiomeDecision.java`
- `src/main/java/com/yucareux/tellus/worldgen/decision/SurfaceDecision.java`
- `src/main/java/com/yucareux/tellus/worldgen/decision/WaterDecision.java`
- `src/main/java/com/yucareux/tellus/worldgen/decision/StrataDecision.java`
- `src/main/java/com/yucareux/tellus/worldgen/decision/SemanticMaterial.java`

### 1.2 核心边界

`TellusColumnDecisionEngine.decide`：

- 校验 context、coordinate、sample 非空；
- 校验 sample request coordinate 与 decision coordinate 相等；
- 校验 sample geometry 与 generation context geometry 相等；
- 对 QUEUED 返回 `ColumnDecision.unavailable(..., QUEUED, ...)`；
- 对 UNKNOWN 或 elevation 非有限值返回 UNKNOWN；
- 将 elevation metres 按 context world scale/sea level 换成 bounded terrain Y；
- 根据 water layer status 生成 NONE/INLAND/OCEAN/UNKNOWN；
- aggregate sample complete → AVAILABLE，否则 → PARTIAL；
- 输出 semantic surface/biome/strata，不返回 Minecraft `BlockState`、registry holder 或 chunk writer对象。

`TellusGenerationContext` 是 immutable Minecraft-free record，校验 world scale、vertical range、geometry concrete normalization 和 geometry scale 一致性。

### 1.3 编译验证

```powershell
./gradlew.bat -PtellusTargets=mc1201 :mc1201:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1211 :mc1211:compileJava --offline --no-daemon --console=plain
```

结果：mc1201 与 mc1211 `compileJava` 均 `BUILD SUCCESSFUL`（mc1201 `UP-TO-DATE`，mc1211 执行成功）。

当前未发现 DecisionKernel-specific test；纯决策行为仍需新增 focused tests，但测试不应扩大真实世界、OSM、renderer 或 parity 样本。

## 2. 纯度与输入契约

### 2.1 纯度

DecisionEngine 没有 source/backend、`TellusWorldgenSources`、preload registry、OSM、water resolver、executor、network、Minecraft registry 或 `ChunkAccess` 引用。每次决定只由输入 records 和固定规则决定：

- 无 IO、线程调度、cache mutation 或 source refresh；
- 无 world/global singleton 读取；
- semantic outputs 与 Minecraft block state 解耦；
- diagnostics 是 immutable `List<String>`，便于上层记录但不触发 side effect。

这满足从 SampleProvider 到 DecisionKernel 的纯边界：provider 负责取得 snapshot，kernel 负责确定性解释，后续 Writer 才负责目标版本写入。

### 2.2 输入 identity gate

Engine 要求：

1. `coordinate.equals(sample.request().coordinate())`；
2. `context.geometry().equals(sample.request().geometry())`；
3. sample 已经通过 `TellusSampleConsistencyValidator`，其 aggregate/layer/provenance 状态合法；
4. generation vertical range 有效，world scale 与 geometry 一致。

因此错 owner、错 coordinate、错 geometry revision 或错 world scale 会在 decision boundary 失败，而不是继续生成一个看似有效的列。

### 2.3 Geometry normalization 边界

`TellusGenerationContext` 的 geometry 通过 `forRequest()` normalization；旧三参 geometry shorthand 可被规范化为 legacy projection/noise v1，具体 provider request 仍要求 concrete geometry。DecisionKernel 不自行推断 projection/noise，而是依赖已构造的 request/context identity。

接入真实 provider 前仍需保证 generation context 使用与 provider 相同的 concrete GeometryIdentity；否则 equality gate 会拒绝 request，这是预期的 fail-closed 行为。

## 3. UNKNOWN/PARTIAL/QUEUED policy

### 3.1 QUEUED

若 sample aggregate 为 QUEUED，engine 不使用任何 layer value，直接返回：

- `DecisionStatus.QUEUED`；
- empty terrainY；
- unknown surface/water/biome/strata；
- diagnostic `sample queued`。

这保留了 non-blocking snapshot pending 语义，不把 queued 当成地面、不写默认石头，也不让 Writer误写等待中的列。

### 3.2 UNKNOWN

若 sample aggregate 为 UNKNOWN，或 elevation metres 非有限，engine 返回 UNKNOWN decision：

- empty terrainY；
- all semantic outputs unknown；
- diagnostic `terrain elevation unavailable`。

这意味着即使 cover/water layer有部分信息，只要 elevation unavailable，当前 kernel也不生成 terrain Y。该策略简单且保守，但后续需确认 BIOME/FEATURE probe 是否需要允许“无 terrain Y 的 partial decision”单独存在，不能让所有 consumer 共用 column fallback。

### 3.3 PARTIAL

若 elevation可用但 sample aggregate不是 complete，engine仍计算 bounded terrainY，并返回：

- `DecisionStatus.PARTIAL`；
- default stone surface；
- default plains biome；
- water 根据 layer known/unknown 产生语义结果；
- stone strata；
- diagnostic `deterministic default decision-v1`。

这是“可计算 terrain column，但部分数据缺失”的显式策略。它还没有使用 cover class、climate、biome source或real surface rules，因此不能声称已完成 Earth/Cube biome parity；PARTIAL default stone/plains 不能替代现有 generator policies。

### 3.4 AVAILABLE

只有 `sample.complete()` 为 true 且 elevation/water layer满足输入校验时，才返回 AVAILABLE。当前 engine 仍输出 default stone/plains semantic outputs，因此 AVAILABLE 表示输入完整并完成 deterministic baseline，不表示 terrain/biome 规则与旧 Earth/Cube generator等价。

## 4. Decision outputs 与 FeaturePlanner/Writer 前置

### 4.1 Surface/Biome/Strata outputs

输出 records 的正确边界是 semantic：

- `SurfaceDecision`：top/filler/depth，允许 UNKNOWN；
- `WaterDecision`：UNKNOWN/NONE/INLAND/OCEAN 与 optional surfaceY，wet water必须有 Y；
- `BiomeDecision`：稳定 id + known flag，不绑定 registry；
- `StrataDecision`：semantic material layers + known flag；
- `ColumnDecision`：coordinate、status、terrainY、上述 outputs、diagnostics。

下一轮 FeaturePlanner/Writer 不应把 `BiomeDecision.id` 直接当 registry holder，也不应把 semantic material 直接当 block state；需由目标版本 adapter 做显式 mapping 并保留 unknown handling。

### 4.2 FeaturePlanner 前置

FeaturePlanner 接入前必须明确：

- 只接受 AVAILABLE/PARTIAL 哪些 decision status；QUEUED/UNKNOWN 是否直接跳过；
- partial terrainY 可用于 road/building/tree/landmark anchor 的条件；
- water UNKNOWN 是否禁止结构/feature placement；
- canonical/apron owner 只允许 canonical owner规划，apron只镜像；
- planner 是否需要 biome/climate/cover layers，而当前 default biome/surface 不能满足；
- diagnostics/provenance 如何进入 plan，而不触发二次 source query。

FeaturePlanner 不应在缺数据时自行调用 provider 或旧 generator；缺失信息必须返回明确 no-plan/partial-plan，并保持 owner identity。

### 4.3 Writer 前置

Writer 接入前必须明确：

- UNKNOWN/QUEUED decision 不写 chunk；
- PARTIAL decision 是否允许写入，若允许必须有可回滚、可重算标记；
- AVAILABLE terrainY、surface depth、water Y 与 dimension minY/maxY 的边界；
- semantic material→BlockState mapping、heightmap、light、unsaved 和 block entity 生命周期；
- Cube-Net canonical/apron owner、rotation/seam transform 和 write ownership；
- writer 不能重新采样或修改 decision。

当前 DecisionKernel 不持有 ChunkAccess/registry，因此这些职责仍应留在版本层 writer，不应为方便写入而把 Minecraft API引入 kernel。

## 5. 当前缺口与风险

### 5.1 default decision 不是 parity 实现

`defaultStone()`、`defaultBiome()` 和 `StrataDecision.stone()` 是 deterministic baseline，不复用 `CubeNetSurfacePolicy`、EarthBiomeSource、MountainSurfaceRules、StrataPolicy 或水域完整规则。它们只能作为 contract 验证输出，不能被标作 Earth/Cube parity完成。

### 5.2 PARTIAL water policy 仍需细化

当前 water layer known 才产生 wet/dry，否则 unknown；elevation partial但water available时可得到 water Y。后续需确认 water surface Y 是否与 terrain Y同一高度语义、sea-level/vertical scale 是否一致，以及 PARTIAL decision 是否允许 Writer写水。

### 5.3 Elevation bounding/overflow

`height` 使用 `Math.round(metres / worldScale) + seaLevel` 后 clamp 到 context range，并将 long cast为 int。context range当前是 int，正常配置安全；仍需测试极端 finite elevation、超大 worldScale、小 worldScale、负海拔和 minY/maxY边界，避免 clamp掩盖 source unit错误。

### 5.4 Provenance/diagnostics 未进入 output

ColumnDecision diagnostics 目前只有固定 default/unknown/queued文本，没有复制 sample provenance source/revision/cacheHit/snapshot信息。DecisionKernel保持纯度是正确的，但上层审计/rollback需要显式带入 provenance summary或 decision trace，不应让诊断只能依赖固定文本。

### 5.5 Feature/biome rules尚未接入

当前 engine不读取 cover raw/visual class、Köppen、elevation zones、slope、geology、hydrology planner或structure toggles。因此它不能替代 Earth/Cube biome decision，也不能直接支持FeaturePlanner全部 feature family。

## 6. 无 generator 接入与回滚边界

### 6.1 当前明确未接入

- Earth/Cube-Net/Sphere-Face generator主循环；
- `TellusWorldgenSources`、provider factory、preload、OSM、water/hydrology source；
- FeaturePlanner、Writer、ChunkAccess、BlockState、registry；
- `TellusCommon`、runtime、payload、client renderer、DH/Voxy；
- parity comparator / generator output path。

DecisionEngine只能由显式调用者构造和调用；删除它不会改变旧生成行为。

### 6.2 回滚点

1. **Kernel-only rollback**：移除 decision package；provider/source/generator路径不变。
2. **Provider→kernel rollback**：保留 SampleProvider结果，但停止把它送入 kernel，恢复旧 decision policies。
3. **Partial policy rollback**：若 PARTIAL输出改变列/feature结果，只关闭 PARTIAL decision消费，不回滚 UNKNOWN/QUEUED contract。
4. **Writer rollback**：若后续 writer mapping有误，停用 writer adapter，保留 semantic decisions与legacy writer。
5. **Parity rollback**：任何 `13,824` 或 biome case变化立即停用 kernel opt-in，输出 sample/decision diff，回到legacy path。

## 7. 最小测试矩阵与 parity 门槛

不扩大测试面，复用现有 sample/geometry/source/parity证据并新增纯 unit tests：

| 类别 | 测试/证据 | 门槛 |
|---|---|---|
| identity | 新增 decision contract tests | coordinate/geometry mismatch 明确抛错 |
| queued | sample contract + decision tests | QUEUED 不生成 terrainY/semantic fallback |
| unknown | sample contract + decision tests | elevation unknown/nonfinite 全输出 UNKNOWN |
| partial | 新增 layer-aware decision tests | status PARTIAL；不把 partial 当 complete |
| available | 新增 deterministic baseline tests | bounded terrainY、fixed semantic defaults稳定 |
| water | `WaterSurfaceResolverTest` + decision tests | wet water必须有 surfaceY；unknown水不伪造 |
| range | `ExperimentalHeightBoundsTest` 等现有边界 | minY/maxY clamp与overflow明确 |
| provider input | provider factory/source tests | request geometry/provenance/status不丢失 |
| FeaturePlanner precondition | 后续纯 plan contract tests | UNKNOWN/QUEUED/owner rules明确 |
| Writer precondition | 后续 semantic→block adapter tests | 不在kernel引入Minecraft API |
| parity | Cube/Sphere `13,824 / 0 mismatch` | kernel未接入时无变化；未来opt-in双路径0 mismatch |
| biome | Earth/Cube `7 cases / 1 mismatch bare/BWh` | 不新增mismatch，不在kernel baseline修复bare/BWh |

### 7.1 接入顺序

1. 纯 decision identity/status/range tests；
2. 用既有 provider sample fixtures/test doubles验证 UNKNOWN/PARTIAL/QUEUED/AVAILABLE；
3. 固定 water/partial policy和diagnostics/provenance trace；
4. 让FeaturePlanner消费semantic decision，但不接generator；
5. 让版本层Writer消费ColumnDecision，保留legacy writer；
6. 对照同一 parity comparator；
7. 所有门槛稳定后，才允许一个generator family opt-in。

## 8. Parity 门槛保持

本次 DecisionKernel 审阅不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

当前 DecisionEngine 没有 generator/provider consumer，因此没有新的 parity结果。其 default stone/plains baseline 不构成 Earth/Cube biome parity；未来接入必须与legacy decision/output使用同一 comparator。任何新增 mismatch都是迁移闸门失败，不能以PARTIAL降级、default semantic output或Writer mapping解释为通过。

## 9. 审阅结论

Task-40 DecisionKernel contract 的纯度和输入 identity gate正确：它不读取 source、不写Minecraft，只把 validated sample解释成 semantic column decision；UNKNOWN/PARTIAL/QUEUED有显式状态，geometry/coordinate mismatch快速失败。当前输出仍是 deterministic baseline（stone/plains/stone strata），不能替代既有 Earth/Cube surface/biome policies。

下一步应先固定 layer-aware PARTIAL、water/height units、provenance trace 和 range tests，再定义FeaturePlanner接受条件与Writer semantic mapping。保持generator、provider runtime和legacy path未接入，直到同一 `13,824 / 0` 与 `7 / 1 bare/BWh` comparator通过。
