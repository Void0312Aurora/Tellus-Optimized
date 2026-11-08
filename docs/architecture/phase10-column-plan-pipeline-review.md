# Tellus Phase 10 Shared Column Plan Pipeline 审阅

> 审阅对象：task-83 targeted tests与 shared `TellusColumnPipeline` / `TellusColumnPlan` extraction。
>
> 审阅任务：task-84。
>
> 结论：**Phase 10 抽出一个只读、plan-only 的 shared column orchestration：SampleProvider → ColumnDecisionEngine → FeaturePlanner。它输出 `TellusColumnPlan`，不映射 target value、不写 feature、不触碰 ChunkAccess，也不注册 lifecycle。每个阶段保留 status与既有 guard；UNKNOWN/QUEUED 在最早可判定阶段停止后续调用。Writer、mc1201 pipeline、legacy generator与 parity路径未被替换。**

## 1. 实现范围

### 1.1 Shared pipeline

- `src/main/java/com/yucareux/tellus/worldgen/decision/TellusColumnPipeline.java`
- `src/main/java/com/yucareux/tellus/worldgen/decision/TellusColumnPlan.java`
- `src/test/java/com/yucareux/tellus/worldgen/decision/TellusColumnPipelineContractTest.java`

`TellusColumnPipeline` 是 functional interface：

```java
TellusColumnPlan plan(
    TellusGenerationContext generation,
    TellusCoordinateContext coordinate,
    TellusSampleRequest request
);
```

`compose` 注入三个既有 stage：

- `TellusSampleProvider`；
- `TellusColumnDecisionEngine`；
- `TellusFeaturePlanner`。

### 1.2 Read-only output

`TellusColumnPlan` 携带：

- pipeline status：`AVAILABLE`、`PARTIAL`、`UNKNOWN`、`QUEUED`；
- original `TellusSample`；
- optional `ColumnDecision`；
- optional `FeaturePlan`；
- immutable diagnostics。

它不携带 `BlockState`、`ChunkAccess`、`BlockPos`、registry holder、mapped payload或 storage transaction；`FeaturePlan`在这里仅作为 semantic planning result，不表示 feature placement已经写入。

## 2. Stage order 与 early stop

### 2.1 Input/identity guard

pipeline先验证：

- generation、coordinate、request非空；
- `request.coordinate().equals(coordinate)`。

坐标不一致立即抛出 `IllegalArgumentException`，不调用 provider、decision engine或feature planner。Sample request仍携带 geometry、purpose、resolution和seed，后续 DecisionEngine/Sample contract继续负责 geometry/provenance一致性。

### 2.2 Stage sequence

严格顺序为：

1. `sampleProvider.sample(request)`；
2. sample status gate；
3. `decisionEngine.decide(generation, coordinate, sample)`；
4. decision status gate；
5. `featurePlanner.plan(generation, coordinate, decision, sample)`；
6. feature plan status gate；
7. `TellusColumnPlan.planned(...)` 返回 semantic plan。

pipeline不并行调用，不跳过 decision直接规划 feature，也不让 FeaturePlanner自行补采样。

### 2.3 Sample early stop

Sample status为：

- `UNKNOWN` → `TellusColumnPlan.Status.UNKNOWN`，无 decision、无 featurePlan，diagnostic `sample is UNKNOWN; target remains untouched`；
- `QUEUED` → `TellusColumnPlan.Status.QUEUED`，无 decision、无 featurePlan，diagnostic `sample is QUEUED; target remains untouched`；
- `AVAILABLE`或`PARTIAL` → 继续 DecisionEngine。

因此 unknown/queued sample不会被 default terrain、default biome、FeaturePlanner或Writer继续消费。

### 2.4 Decision early stop

DecisionEngine返回：

- `UNKNOWN` → decision携带到 `TellusColumnPlan.decision`，无 featurePlan，plan status UNKNOWN；
- `QUEUED` → decision携带到 plan，无 featurePlan，plan status QUEUED；
- `PARTIAL` → 继续 FeaturePlanner，最终通常为 PARTIAL；
- `AVAILABLE` → 继续 FeaturePlanner。

decision coordinate与 sample request coordinate不一致会在 `TellusColumnPlan` invariant中拒绝；pipeline不把 UNKNOWN/QUEUED升级为可写状态。

### 2.5 Feature plan early stop

FeaturePlanner返回：

- `FeaturePlanStatus.UNKNOWN` → 保留 decision与featurePlan，plan status UNKNOWN，附加 `feature plan is UNKNOWN; target remains untouched`；
- `FeaturePlanStatus.QUEUED` → 保留 decision与featurePlan，plan status QUEUED，附加 `feature plan is QUEUED; target remains untouched`；
- `EMPTY` → plan可继续作为已解析 column plan；不产生 feature writes；
- `AVAILABLE` → plan status按 decision为 AVAILABLE/PARTIAL，携带 semantic feature plan，但不写 placement。

FeaturePlanner unknown/queued不会触碰 writer，因为 shared pipeline本身没有 writer stage。

## 3. TellusColumnPlan invariants

构造器固定：

- `AVAILABLE`必须有 `ColumnDecision.AVAILABLE`；
- `PARTIAL`必须有 `ColumnDecision.PARTIAL`；
- decision存在时，其 coordinate必须等于 sample request coordinate；
- feature plan为 UNKNOWN时，column plan必须为 UNKNOWN；
- feature plan为 QUEUED时，column plan必须为 QUEUED；
- blocked plan只接受 UNKNOWN或QUEUED；
- diagnostics与所有 optional inputs immutable/nonnull。

这些 invariant防止 stage status被压平、feature status超前于 column status、或错误 coordinate进入下游计划。

## 4. Existing guards 与 strict delegation

### 4.1 Reused guards

shared pipeline复用既有 contract，不复制规则：

- SampleProvider保持 SampleStatus、provenance和request identity；
- DecisionEngine继续校验 coordinate/geometry并处理 terrain/status；
- FeaturePlanner继续执行 owner、canonical/apron、UNKNOWN/QUEUED policy；
- TellusColumnPlan只负责跨 stage aggregation/invariants。

pipeline不重新解释 elevation、水、biome或surface，不重写 PartialWritePolicy，也不改变 canonical/apron persistence。

### 4.2 No second sampling

pipeline仅调用一次 `sampleProvider.sample(request)`，后续 stage只接收同一个 sample；

- DecisionEngine不被pipeline外再次调用；
- FeaturePlanner获得同一 sample与decision；
- no source/cache refresh、network、executor、retry或fallback；
- queued/unknown不会隐式重试或合成 default value。

## 5. Writer 与 feature write 未改变

### 5.1 Writer unchanged

`TellusColumnPipeline`不调用：

- `TellusColumnWriter`；
- `TellusColumnMaterializer`；
- `TellusMinecraft1201CanonicalColumnAdapter`；
- `TellusMinecraft1201CanonicalPipeline`；
- `ChunkAccess`、`BlockState`、`SemanticMapper`或StorageTransactionBoundary。

因此 plan生成完成不代表 column已经写入；mc1201 writer仍由原有 explicit pipeline/adapter负责，legacy writer仍是默认行为权威。

### 5.2 No feature writes

FeaturePlanner返回的 placements只进入 `FeaturePlan`：

- 不执行 `PlacementOperation`；
- 不应用 `PlacementConflictPolicy`；
- 不生成 BlockPos/BlockState；
- 不执行 canonical/apron mirror；
- 不创建 save/reload/edit overlay或 transaction。

后续 writer必须显式消费计划，并重新遵守 owner/status/persistence gate。

## 6. Lifecycle 与 generator 边界

当前 shared pipeline没有：

- generator constructor/codec registration；
- runtime registry/factory/lifecycle bridge调用；
- Common/server/world/chunk hooks；
- provider registry自动发现；
- save/reload、server tick、payload、renderer或DH/Voxy接入。

它是可由调用方显式组合的 value-plan boundary；删除 pipeline不会改变旧 generator、legacy source或runtime行为。

## 7. task-83 targeted test evidence

Lead 已串行执行 targeted tests并报告 `BUILD SUCCESSFUL`。`TellusColumnPipelineContractTest`覆盖：

- AVAILABLE sample → decision → feature planner完整顺序；
- PARTIAL sample保留 PARTIAL decision与 planner call；
- UNKNOWN sample提前停止，decision/featurePlan为空；
- QUEUED sample提前停止，decision/featurePlan为空；
- feature planner call count验证 unknown/queued early stop；
- coordinate mismatch拒绝；
- plan contract保持 Minecraft-free。

本 review不另行启动测试。

## 8. Legacy、rollback 与 parity

### 8.1 Legacy unchanged

- 旧 Earth/Cube-Net/Sphere generator不自动使用 shared pipeline；
- 旧 SampleProvider/source、DecisionKernel、FeaturePlanner和Writer consumer保持原显式路径；
- pipeline不接管 mc1201 target writer；
- no pipeline plan不会自动回退或双写 legacy；
- pipeline只提供 plan，不改变旧 save/cache/runtime/lifecycle。

### 8.2 Rollback points

1. **Pipeline opt-out**：停止显式调用 `TellusColumnPipeline.plan`，旧 generator路径继续。
2. **Stage rollback**：SampleProvider/DecisionEngine/FeaturePlanner出现异常或 status regression时停用 shared composition，不改各 stage旧实现。
3. **Status rollback**：若 aggregation错误，恢复 stage-local UNKNOWN/QUEUED/PARTIAL semantics，禁止转为 AVAILABLE。
4. **Writer isolation rollback**：plan输出异常时不调用 Writer/ChunkAccess；保留旧 writer。
5. **Feature isolation rollback**：feature plan异常只丢弃 plan，不执行 placements或 mirror。
6. **Parity rollback**：任何 column/biome comparator变化立即停用 pipeline consumer，输出 sample/decision/plan diff并回到 legacy。

## 9. Parity 门槛保持

本次 shared column plan pipeline审阅不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

Plan-only extraction没有 generator/storage/parity output接入；UNKNOWN/QUEUED early stop也不是 biome mismatch修复。任何未来 consumer opt-in必须与 legacy path使用同一 comparator，新增 mismatch立即回滚。

## 10. 审阅结论

Task-83 shared column pipeline保持严格只读编排：sample → decision → feature plan，逐阶段传播 status并在 UNKNOWN/QUEUED处提前停止；coordinate、decision、feature status invariants防止错误计划下游传播。它不写 target、不执行 feature placements、不接 lifecycle/generator，不改变 writer或legacy行为。Lead targeted tests已验证 AVAILABLE/PARTIAL顺序、UNKNOWN/QUEUED early stop、call count、coordinate mismatch与Minecraft-free contract。

## 11. Explicit Earth classifier pipeline factories

Task-116 adds target-specific explicit factories to mc1201 and mc1211 canonical pipelines:

- `TellusMinecraft1201CanonicalPipeline.withEarthBiomeClassifier(...)`;
- `TellusMinecraft1211CanonicalPipeline.withEarthBiomeClassifier(...)`.

Each factory composes the shared `TellusColumnPipeline` with:

- `TellusColumnDecisionEngine.withEarthBiomeClassifier()` or an injected Earth resolver;
- caller-provided sample provider and Köppen resolver;
- caller-provided feature planner, materializer and semantic mapper.

The existing constructors remain classifier-free and continue to compose the default DecisionEngine. Factory creation is explicit; it does not register a generator, create a runtime registry, attach lifecycle, or alter a default pipeline instance.

### 11.1 Plan/write boundary

The explicit factory only changes the DecisionEngine dependency used by the shared plan stage. Existing pipeline gates remain:

- sample UNKNOWN/QUEUED stops before decision/feature;
- complete climate is required for the Earth kernel to classify;
- absent/blank climate keeps the legacy default biome decision;
- plan UNKNOWN/QUEUED remains no-write;
- writer receives only the resulting plan decision after all read-only stages pass.

### 11.2 Targeted evidence and rollback

Lead reported mc1201 and mc1211 targeted factory tests `BUILD SUCCESSFUL`. The fixtures prove default pipeline behavior remains plains while an explicitly constructed factory resolves the cover-60/BWh fixture to desert through an injected resolver. No generator/lifecycle registration or default callsite changed.

## 12. Sample-climate pipeline composition

Task-118 adds `TellusColumnPipeline.composeWithSampleClimate(...)` and symmetric mc1201/mc1211 canonical-pipeline factories that derive raw Köppen input from `TellusSample.climate()`:

- only a complete climate sample contributes `climate.code()`;
- absent, partial, UNKNOWN or QUEUED climate resolves to null and preserves the DecisionEngine complete-climate gate;
- the old `TellusColumnPipeline.compose(...)` overload remains unchanged and classifier-free;
- explicit caller chooses the sample-climate factory, feature planner, materializer and mapper.

Lead reported mc1201/mc1211 sample-climate factory tests `BUILD SUCCESSFUL`. The new factories remain plan-only/opt-in: they do not register generators, create runtime registries, attach lifecycle or alter default constructors. Existing UNKNOWN/QUEUED early stops, writer handoff and parity rollback gates remain unchanged.

Rollback is factory-local: stop constructing the sample-climate factory and retain the old classifier-free `compose(...)` path; no registry detach, save/reload or legacy generator migration is required. Parity remains gated by `13,824 / 0` and `7 / 1 bare/BWh`.