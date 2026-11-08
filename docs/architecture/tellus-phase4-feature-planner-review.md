# Tellus Phase 4 FeaturePlanner 审阅

> 审阅对象：task-43 revision 3 的 FeaturePlanner/FeaturePlan contract。
>
> 审阅任务：task-45。
>
> 结论：**Phase 4 FeaturePlanner 建立了纯、owner-aware、semantic placement 边界，并明确 Cube-Net apron 只能镜像 canonical owner。UNKNOWN/QUEUED 不产生 placements，EMPTY 表示无计划，AVAILABLE 必须带至少一个 placement。当前没有接入任何 generator、source/provider、Writer、runtime 或 parity path。下一步必须先固定 PARTIAL/AVAILABLE 消费策略、owner/provenance 传递和 Writer semantic-to-block 前置，再考虑 FeaturePlanner family opt-in。**

## 1. 实现范围与验证

### 1.1 FeaturePlanner 类型

- `src/main/java/com/yucareux/tellus/worldgen/feature/TellusFeaturePlanner.java`
- `src/main/java/com/yucareux/tellus/worldgen/feature/DefaultTellusFeaturePlanner.java`
- `src/main/java/com/yucareux/tellus/worldgen/feature/FeaturePlan.java`
- `src/main/java/com/yucareux/tellus/worldgen/feature/FeaturePlanStatus.java`
- `src/main/java/com/yucareux/tellus/worldgen/feature/FeaturePlacement.java`
- `src/main/java/com/yucareux/tellus/worldgen/feature/FeatureKind.java`
- `src/main/java/com/yucareux/tellus/worldgen/feature/PlacementOperation.java`
- `src/main/java/com/yucareux/tellus/worldgen/feature/PlacementConflictPolicy.java`

### 1.2 编译验证

```powershell
./gradlew.bat -PtellusTargets=mc1201 :mc1201:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1211 :mc1211:compileJava --offline --no-daemon --console=plain
```

结果：mc1201 与 mc1211 `compileJava` 均 `BUILD SUCCESSFUL`（mc1201 `UP-TO-DATE`，mc1211 执行成功）。

当前没有发现 FeaturePlanner-specific focused test；应在后续补纯 owner/status/placement contract tests，不扩大真实世界、OSM、renderer 或 parity 样本。

### 1.3 明确未接入

FeaturePlanner 当前没有引用/调用：

- Earth/Cube-Net/Sphere-Face generator 主循环；
- `TellusWorldgenSources`、SampleProvider、preload、OSM、hydrology 或 DecisionKernel source path；
- `ChunkAccess`、`BlockState`、registry、heightmap、light 或 block entity；
- FeaturePlanner family registry、runtime lifecycle、payload、DH/Voxy、client renderer；
- writer、world save、cache mutation 或 parity comparator/generator output。

它只能由显式调用者把已有 `ColumnDecision`/`TellusSample` 传入，不会自动改变旧生成行为。

## 2. Planner 输入与纯度

### 2.1 纯 planner boundary

`TellusFeaturePlanner.plan` 接受：

- `TellusGenerationContext`；
- validated `TellusCoordinateContext`；
- `ColumnDecision`；
- immutable `TellusSample`。

接口没有 source/backend、executor、IO、Minecraft API 或 global singleton；纯 planner 应只根据输入生成 immutable `FeaturePlan`。缺失信息不得由 planner 自行调用 provider、旧 generator、OSM 或 source registry。

### 2.2 Default planner behavior

`DefaultTellusFeaturePlanner` 是确定性 baseline：

- UNKNOWN column 或 UNKNOWN sample → `FeaturePlan.UNKNOWN`，无 placement；
- QUEUED column 或 QUEUED sample → `FeaturePlan.QUEUED`，无 placement；
- 缺 terrain anchor → `FeaturePlan.EMPTY`；
- canonical owner → `FeaturePlacement.canonical`，`SET + OWNER_ONLY`；
- Cube-Net apron owner → `FeaturePlacement.apronMirror`，`MIRROR + MIRROR_OWNER`；
- placement 使用 semantic material、feature kind/id、relative offsets、height 和 metadata，不直接写 block。

它没有读取 biome/cover/climate/road/building/tree/landmark数据，因此是 planner contract baseline，不是实际 feature family实现。

### 2.3 FeaturePlan status invariants

`FeaturePlan` 构造器固定：

- UNKNOWN/QUEUED 不得含 placements；
- EMPTY 不得含 placements；
- AVAILABLE 必须含至少一个 placement；
- placements 和 diagnostics immutable copy。

这为下游 Writer 提供明确 gate：只有 AVAILABLE 才可尝试写 placement；UNKNOWN/QUEUED 不得写入；EMPTY 表示确定性无计划而不是错误。

## 3. Owner 与 apron mirror contract

### 3.1 Canonical placement

`FeaturePlacement.canonical`：

- owner 与 canonicalOwner 都取 `context.owner().kind()`；
- `apronMirror=false`；
- operation 可为 SET/FILL/REPLACE_AIR 等 semantic operation；
- downstream writer 只在该 canonical owner上执行 placement；
- conflict policy由 planner明确提供，default planner使用 `OWNER_ONLY`。

canonical placement 不应跨 owner 写入，也不应把 planar Cube-Net position 当第二 terrain authority。

### 3.2 Apron mirror

`FeaturePlacement.apronMirror`：

- 仅接受 `CubeNetApronOwnerView` context；非-apron context立即抛异常；
- owner为 `CUBE_NET_APRON`；canonicalOwner为 apron 的 canonical owner kind；
- operation固定为 `MIRROR`；
- default planner使用 `MIRROR_OWNER` conflict policy；
- metadata包含 `source=canonical-owner`，保留镜像来源诊断。

这保证 apron 是 canonical owner 的 derived view，不会让 feature planner在 apron上重新查询、重新随机或创建第二份 feature source。Writer 仍需使用 apron projection/seam transform 将相对 placement映射到目标 view。

### 3.3 Owner invariants 的待补测试

当前 `FeaturePlacement` 验证了 owner/canonicalOwner、mirror operation 和 apron context类型，但下一轮应补：

- canonical owner 与 `ColumnDecision.coordinate` owner一致；
- apron placement canonicalOwner 与 `CubeNetApronOwnerView.canonicalOwner()`一致；
- featureId/metadata/source revision在mirror中保持；
- 同一 canonical feature不会因多个 apron view重复规划；
- cross-face seam transform 不在 planner 中提前写入 block坐标。

## 4. UNKNOWN/QUEUED/PARTIAL policy

### 4.1 UNKNOWN/QUEUED

当前 default planner对 UNKNOWN/QUEUED严格 no-placement：

- 不返回 default feature；
- 不猜 terrain anchor；
- 不触发 source retry、future join或fallback；
- diagnostics明确记录 unknown/queued。

这与 DecisionKernel contract一致，保护未准备的 snapshot/column 不被 Writer误写。

### 4.2 PARTIAL/AVAILABLE

当前 default planner对 PARTIAL 没有单独分支：只要 column不是 UNKNOWN/QUEUED且有 terrainY，就生成 AVAILABLE placement；这意味着 PARTIAL column 会被当作可规划 anchor。该行为适合作为最小 baseline，但接入真实 feature families 前必须明确：

- 哪些 feature允许 PARTIAL terrainY；
- roads/buildings/trees/landmarks是否要求 cover/biome/water complete；
- PARTIAL water UNKNOWN是否阻止水边/桥/道路计划；
- partial feature plan是否标记可重算、临时或保守 conflict policy；
- FeaturePlan是否需要增加 `PARTIAL` status，还是以 AVAILABLE + diagnostics表达“可计划但数据不完整”。

不能让不同 feature family各自解释 PARTIAL；应在统一 planner policy中固定。

### 4.3 Sample status 与 ColumnDecision status 双 gate

Planner同时接收 sample和column，因此必须固定两者冲突规则：

- sample UNKNOWN/QUEUED即使 column PARTIAL/AVAILABLE，也应阻止依赖该 layer的 feature；
- column UNKNOWN/QUEUED即使 sample有部分已知层，也不产生 placement；
- sample PARTIAL + column PARTIAL的 feature eligibility需按 family policy；
- sample provenance/source revision应进入 plan diagnostics或 metadata，不触发二次采样。

Default planner目前只实现 UNKNOWN/QUEUED gate，真实 planner factory需补 layer-aware eligibility。

## 5. Writer 前置

### 5.1 Semantic placement mapping

FeaturePlanner输出：

- `SemanticMaterial`；
- relative offsets/height；
- `PlacementOperation`；
- `PlacementConflictPolicy`；
- owner/canonicalOwner；
- metadata。

Writer接入前必须定义：

- semantic material → target-version BlockState/registry mapping；
- SET/FILL/REPLACE_AIR/MIRROR的具体写入顺序；
- owner-only/preserve-existing/replace-terrain/mirror-owner conflict语义；
- heightmap/light/unsaved/block-entity生命周期；
- placement offset与chunk bounds、cross-face seam transform；
- duplicate featureId/idempotency和save/reload行为。

Planner不能直接生成 `BlockPos`、`BlockState` 或写 ChunkAccess；Writer不能重新调用 provider或改变 FeaturePlan。

### 5.2 Status gate

Writer必须：

- 跳过 UNKNOWN/QUEUED/EMPTY plan；
- 对 AVAILABLE plan逐 placement验证 owner/canonicalOwner和 conflict policy；
- 对 PARTIAL policy（若未来增加）遵守可回滚/可重算标记；
- apron MIRROR placement只能使用 canonical source/projection，不允许重新规划。

### 5.3 FeaturePlan provenance

当前 `FeaturePlan`只有 diagnostics，没有 sample provenance字段。Writer/审计需要知道 feature来自哪个 source revision/cache snapshot时，后续可将 provenance summary复制到 diagnostics/metadata；不要在 planner中保留可变 source对象或引入全局 cache引用。

## 6. 当前缺口与风险

### 6.1 Baseline planner不是实际 feature parity

Default planner只生成单个 material placement，未接入道路、建筑、树、地标、OSM、hydrology、结构过滤、Arnis规则或现有 feature classes。它只能证明 contract/status/owner方向，不能声称完成现有 Earth/Cube feature parity。

### 6.2 Apron mirror需要 projection-aware writer

Planner只标记 MIRROR和canonical owner，不携带具体 seam rotation/block projection。这样保持了纯度，但Writer必须从 coordinate owner/apron resolver取得 projection；不能把 offsetX/Z直接当目标面 block坐标。

### 6.3 Conflict policy尚未与既有写入规则对齐

`OWNER_ONLY`、`PRESERVE_EXISTING`、`REPLACE_TERRAIN`、`MIRROR_OWNER`是新 semantic枚举；它们尚未与现有 building/road/tree/structure写入优先级、编辑保护、OSM collision和apron edit overlay进行逐项对照。接入具体 feature family前需做 policy mapping，不能直接把 default `OWNER_ONLY`扩展到全部 feature。

### 6.4 Feature ids/metadata缺少稳定 schema

featureId仅要求非空；metadata为 immutable map但没有 schema、revision、seed、source identity约束。未来跨 chunk planning、replay/save/reload需要稳定 feature ID、seed、source revision和owner key，否则同 feature可能重复生成或无法回滚。

## 7. 无 generator 接入与回滚边界

### 7.1 当前明确未接入

- Earth/Cube-Net/Sphere-Face generator主循环；
- SampleProvider/source backend/preload/OSM/hydrology；
- DecisionKernel consumer registry；
- Writer/ChunkAccess/BlockState/registry/heightmap/light；
- runtime lifecycle、payload、client renderer、DH/Voxy；
- parity comparator / generator output path。

### 7.2 回滚点

1. **Planner-only rollback**：移除 feature package；旧 generator/feature/source路径不变。
2. **Status policy rollback**：若 PARTIAL policy导致新 placement，关闭 PARTIAL planner消费，恢复 UNKNOWN/QUEUED no-plan；不改旧 feature rules。
3. **Owner/apron rollback**：mirror projection异常时禁用 apron planner branch，只保留 canonical/legacy feature path。
4. **Writer rollback**：semantic mapping或conflict policy异常时停用 Writer adapter，保留 FeaturePlan diagnostics与legacy writer。
5. **Parity rollback**：任何 `13,824` 或 biome case变化先停用 planner opt-in，输出 decision/plan diff，回到 legacy path。

## 8. 最小测试矩阵与 parity 门槛

不扩大测试面，新增纯 contract tests并复用现有 geometry/sample/source/parity证据：

| 类别 | 测试/证据 | 门槛 |
|---|---|---|
| plan invariants | 新增 FeaturePlan/FeaturePlacement tests | status与placements组合严格有效 |
| unknown/queued | decision/sample fixtures | no placements、无fallback写入 |
| partial | 新增 layer-aware planner tests | PARTIAL eligibility统一，不压平为complete |
| canonical owner | context/owner fixtures | placement owner与canonical owner一致 |
| apron mirror | CubeNet apron context/feature tests | MIRROR operation、MIRROR_OWNER policy、canonical source保留 |
| conflict policy | semantic policy tests | owner/preserve/replace/mirror mapping稳定 |
| writer precondition | 后续 semantic writer tests | 不把 planner直接绑定Minecraft API |
| source provenance | provider sample fixtures | no second source query，diagnostics可追踪 |
| parity | Cube/Sphere `13,824 / 0 mismatch` | planner未接入时无变化；未来opt-in双路径0 mismatch |
| biome | Earth/Cube `7 cases / 1 mismatch bare/BWh` | 不新增mismatch，不在planner baseline修复bare/BWh |

### 8.1 接入顺序

1. 纯 FeaturePlan/Placement invariant tests；
2. UNKNOWN/QUEUED/PARTIAL/AVAILABLE fixtures；
3. canonical/apron owner mirror tests；
4. 固定 FeaturePlanner family eligibility和conflict policy；
5. 实现版本层 Writer semantic mapping，保留legacy writer；
6. 对照同一 parity comparator；
7. 所有门槛稳定后，才允许一个 generator family opt-in。

## 9. Parity 门槛保持

本次 FeaturePlanner 审阅不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

当前 FeaturePlanner没有接入generator/provider/writer，因此没有新的 parity结果。Default planner的single semantic placement不构成Earth/Cube feature parity；未来接入必须与legacy feature/column output使用同一 comparator。任何新增 mismatch都是迁移闸门失败，不能以PARTIAL默认placement、apron mirror或Writer mapping解释为通过。

## 10. 审阅结论

Task-43 FeaturePlanner contract 的方向正确：它纯消费 generation/coordinate/decision/sample，输出 immutable semantic FeaturePlan；UNKNOWN/QUEUED严格无placement；canonical owner生成正常 placement，Cube-Net apron生成 MIRROR placement并保留canonical owner来源。它没有接入generator、source、Writer或runtime，因此当前不改变旧生成行为。

下一步应先固定 PARTIAL/AVAILABLE feature eligibility、sample/column双gate、feature provenance、conflict policy 与 projection-aware Writer mapping，再让一个feature family在legacy对照下opt-in。保持同一 `13,824 / 0` 与 `7 / 1 bare/BWh` parity门槛。
