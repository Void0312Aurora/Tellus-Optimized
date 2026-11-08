# Tellus Phase 12 mc1211 Column Pipeline 审阅

> 审阅对象：task-89 的 Minecraft 1.21.1 canonical column pipeline、adapter、writer与 mapper。
>
> 审阅任务：task-90。
>
> 结论：**mc1211 pipeline 与 mc1201 保持同一 opt-in、canonical-only、no-write-before-target 边界，但采用 shared `TellusColumnPipeline`/`TellusColumnPlan` 作为 sample→decision→feature-plan orchestration。只有 plan status通过后才创建 sink-backed writer；UNKNOWN/QUEUED plan 在目标触碰前返回；apron、坐标不一致和 disabled 均显式 no-write/invalid。mc1211 mapper/adapter 仅位于 target module，未注册 generator/lifecycle或改变默认 legacy path。**

## 1. 实现范围

### 1.1 mc1211 target types

- `shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/write/TellusMinecraft1211CanonicalPipeline.java`
- `shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/write/TellusMinecraft1211CanonicalColumnAdapter.java`
- `shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/write/TellusMinecraft1211ColumnWriter.java`
- `shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/write/TellusMinecraft1211SemanticMapper.java`
- `shared/mc1211/src/test/java/com/yucareux/tellus/worldgen/write/TellusMinecraft1211WriteAdapterTest.java`

### 1.2 Shared plan dependency

`TellusMinecraft1211CanonicalPipeline`支持两种构造方式：

1. 注入 `TellusSampleProvider`、`TellusColumnDecisionEngine`、`TellusFeaturePlanner`，内部组合 `TellusColumnPipeline.compose(...)`；
2. 直接注入已组合的 `TellusColumnPipeline`。

两者都接收：

- `boolean enabled`；
- `TellusColumnMaterializer`；
- `SemanticMapper<BlockState>`。

这与 Phase 10 shared plan-only extraction保持一致：mc1211 target adapter不复制 sample/decision/feature orchestration规则。

## 2. API 与 mc1201 边界差异

### 2.1 Pipeline orchestration差异

mc1201旧 pipeline直接持有并依次调用：

- SampleProvider；
- DecisionEngine；
- FeaturePlanner；
- 再按 sample/decision/feature status gate进入 writer。

mc1211 pipeline将上述三阶段委托给共享 `TellusColumnPipeline`，读取 `TellusColumnPlan`：

```java
TellusColumnPlan plan = columnPipeline.plan(generation, coordinate, request);
```

然后：

- plan UNKNOWN → `WriteResult.unknown(...)`；
- plan QUEUED → `WriteResult.queued(...)`；
- known plan必须有 decision，否则 fail-fast；
- AVAILABLE/PARTIAL decision才创建 `TellusMinecraft1211ColumnWriter`。

这使 status aggregation、early stop和diagnostics由 shared contract统一，mc1211只负责 target mapping/storage boundary。

### 2.2 Target API isolation

mc1211 only imports target APIs in target package：

- `BlockPos`；
- `BlockState`；
- `ChunkAccess`；
- `Blocks`。

root/shared plan、decision、feature、sample、write contracts不依赖 1.21.1 `ChunkAccess`或`BlockState`。mc1201与mc1211各有独立 mapper/writer/adapter，不共享目标版本 Minecraft types。

### 2.3 Semantic mapper

`TellusMinecraft1211SemanticMapper`与mc1201保持相同 semantic mapping shape：

- known `SemanticMaterial` → 对应 `Blocks.*.defaultBlockState()`；
- UNKNOWN material显式抛 `IllegalArgumentException`；
- mapper不读取 chunk/world/registry/source；
- mapper不承担 status fallback或 lifecycle。

版本差异留在 target mapper，而非 shared decision/plan contract。

## 3. Guards 与 no-write-before-target

### 3.1 Pipeline guards

`TellusMinecraft1211CanonicalPipeline.write(...)`按顺序执行：

1. `enabled=false` → `NO_WRITE_INVALID`，立即返回；
2. 校验 context、generation、coordinate、request；
3. apron target → `NO_WRITE_INVALID`；
4. context coordinate与实际 coordinate不一致，或 request coordinate不一致 → `NO_WRITE_INVALID`；
5. 调用 shared column pipeline；
6. plan UNKNOWN → `NO_WRITE_UNKNOWN`；
7. plan QUEUED → `NO_WRITE_QUEUED`；
8. known plan无 decision → `IllegalStateException`；
9. 只有此后创建 `TellusMinecraft1211ColumnWriter(target, materializer)`并写入。

因此 target在 disabled、coordinate invalid、apron、sample/decision/feature UNKNOWN/QUEUED时保持 untouched；pipeline没有自行 fallback到stone或legacy writer。

### 3.2 Adapter guards

`TellusMinecraft1211CanonicalColumnAdapter`继续执行 shared writer guard：

- decision UNKNOWN/QUEUED → no-write；
- apron context → invalid；
- PARTIAL + `NO_WRITE` → `NO_WRITE_PARTIAL`；
- persistence identity与 decision coordinate校验；
- terrainY为空或 top material UNKNOWN → partial rejection；
- 所有 guard通过后才 require target、构造 BlockPos、调用 mapper、`setBlockState`。

### 3.3 Canonical-only scope

支持 Earth、Cube-Net canonical、Sphere canonical owner的 X/Z映射；Cube-Net apron不写。当前只写一个 canonical semantic top cell：

- surface top material；
- terrainY；
- target BlockPos；
- `setBlockState(position, state, false)`。

filler/strata/water/heightmap/light/block entity/save/reload仍未接入。

## 4. Status parity

mc1211与shared/mc1201 pipeline保持以下 status semantics：

| 输入/阶段 | mc1211结果 | 目标触碰 |
|---|---|---|
| pipeline disabled | `NO_WRITE_INVALID` | 不触碰 |
| sample UNKNOWN / plan UNKNOWN | `NO_WRITE_UNKNOWN` | 不触碰 |
| sample QUEUED / plan QUEUED | `NO_WRITE_QUEUED` | 不触碰 |
| coordinate/apron invalid | `NO_WRITE_INVALID` | 不触碰 |
| PARTIAL + `NO_WRITE` | `NO_WRITE_PARTIAL` | 不触碰 |
| AVAILABLE known top | `WRITTEN` | 仅top cell |
| PARTIAL + known layers | `PARTIAL` | 仅已证明top cell |

`TellusColumnPlan`保证 decision/feature status不会被压平为 AVAILABLE；mc1211只消费 plan status，不重新定义 status policy。

## 5. No feature writes、no lifecycle、no default

### 5.1 Feature isolation

mc1211 pipeline从 plan取 decision，不执行 FeaturePlan placements：

- 不调用 feature writer；
- 不应用 `PlacementOperation`或`PlacementConflictPolicy`；
- 不写 road/building/tree/landmark；
- 不执行 canonical/apron mirror；
- FeaturePlan只作为 shared plan stage的 status gate与语义结果。

### 5.2 Lifecycle boundary

mc1211 pipeline/adapter/writer未接入：

- runtime registry/factory/registration helper；
- lifecycle bridge attach/tick/detach；
- TellusCommon、loader/server/world/chunk hooks；
- generator constructor/codec、provider registry；
- save/reload、payload、renderer、DH/Voxy。

pipeline实例存在不等于 generator启用；只有显式 caller `write(...)` 且 enabled=true才会触碰 target。

### 5.3 Default legacy

- 旧 mc1211 Earth/Cube-Net/Sphere generator保持默认；
- pipeline不自动创建、注册或 attach runtime；
- disabled pipeline不会自动回调 legacy writer，避免双写；
- consumer可根据 `WriteResult`显式选择 untouched或legacy fallback；
- 删除mc1211 pipeline不改变旧 source/cache/save/runtime。

## 6. Task-89 targeted test evidence

Lead 已串行执行并报告 mc1211 compile + targeted test `BUILD SUCCESSFUL`。`TellusMinecraft1211WriteAdapterTest`覆盖：

- known `SemanticMaterial.STONE` → `Blocks.STONE.defaultBlockState()`；
- disabled pipeline在 null inputs下提前返回 `NO_WRITE_INVALID`；
- enabled pipeline的 UNKNOWN sample提前返回 `NO_WRITE_UNKNOWN`；
- UNKNOWN路径不触碰 target，并保持 pipeline enabled state；
- 1.21.1 target mapper/adapter使用独立 target API。

该 targeted evidence验证 mc1211 guard/status/mapper boundary，不等于完整 terrain column、save/reload、Common lifecycle或 parity output已接入。本 review不额外启动测试。

## 7. Rollback

1. **Pipeline-off**：`enabled=false`或停止显式 caller，所有 target write停止。
2. **Shared-plan rollback**：若 shared plan status aggregation异常，停用mc1211 pipeline consumer，保留旧 provider/decision/writer。
3. **Writer rollback**：mapper/ChunkAccess/top-cell问题只停用mc1211 writer，回到legacy writer。
4. **Status rollback**：UNKNOWN/QUEUED/PARTIAL语义发生变化时恢复 no-write gate，禁止升级为AVAILABLE。
5. **Feature rollback**：feature placements尚未写入；未来 feature sink异常只回滚 feature branch，不影响 canonical column legacy path。
6. **Lifecycle rollback**：pipeline不注册 lifecycle；若未来显式注册失败，停止 registration/bridge consumer，不修改默认 generator。
7. **Parity rollback**：任何 mc1211 opt-in引发 comparator变化，停止该 consumer，输出 sample/plan/decision/write diff并回到legacy。

## 8. Parity 门槛保持

本次 mc1211 column pipeline review不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

mc1211 pipeline是显式 opt-in、top-cell scope，没有 generator registration或完整 parity output；`BUILD SUCCESSFUL`只证明 compile/targeted contract，不代表 column/biome parity通过。mc1201/mc1211必须继续使用同一 comparator，新增 mismatch立即回滚。

## 9. 审阅结论

Task-89 mc1211 pipeline实现保持与mc1201的 guard/status parity，同时将 sample→decision→feature-plan orchestration委托给 shared `TellusColumnPipeline`，避免双实现。disabled、coordinate/apron invalid、UNKNOWN/QUEUED、PARTIAL policy均在目标触碰前正确停止；known path只写 canonical top cell。Feature writes、完整 storage、generator/lifecycle/default path均未接入。

Lead targeted compile/test已通过；task-90 review完成。后续若让mc1211 pipeline与显式 registration/lifecycle或真实 generator串联，必须保留 caller opt-in、legacy fallback、status-preserving results及 `13,824 / 0`、`7 / 1 bare/BWh` parity门槛。
