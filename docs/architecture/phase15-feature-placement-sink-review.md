# Tellus Phase 15 Feature Placement Sink 审阅

> 审阅对象：task-96 FeaturePlacementSink guard与 mc1201/mc1211 semantic feature placement sinks。
>
> 审阅任务：task-97。
>
> 结论：**Phase 15 将 FeaturePlan placements 放入显式、generic、owner/conflict-aware sink boundary，并提供平行 mc1201/mc1211 target sinks。UNKNOWN、QUEUED、EMPTY、owner mismatch、MIRROR_OWNER conflict和UNKNOWN material都有明确 no-write/invalid结果；只有 AVAILABLE plan且 placement guards通过时才触碰 target。sinks不自动注册 generator/lifecycle，不成为默认 feature writer。当前 target sink逐 placement写入，跨 placement atomicity/rollback仍是后续 storage contract。**

## 1. 实现范围

### 1.1 Shared sink contract

- `src/main/java/com/yucareux/tellus/worldgen/feature/FeaturePlacementSink.java`
- `src/main/java/com/yucareux/tellus/worldgen/feature/FeaturePlacementSinkGuard.java`
- `src/main/java/com/yucareux/tellus/worldgen/feature/FeaturePlacementResult.java`
- `src/main/java/com/yucareux/tellus/worldgen/feature/FeaturePlacementStatus.java`
- existing `FeaturePlan` / `FeaturePlacement` / `PlacementOperation` / `PlacementConflictPolicy`。

`FeaturePlacementSink<M>`：

```java
FeaturePlacementResult<M> place(
    WriteContext context,
    FeaturePlan plan,
    SemanticMapper<M> mapper
);
```

shared contract保持 generic：没有 `ChunkAccess`、`BlockState`、registry或 target lifecycle依赖。

### 1.2 Target sinks

- `shared/mc1201/src/main/java/com/yucareux/tellus/worldgen/feature/TellusMinecraft1201FeaturePlacementSink.java`
- `shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/feature/TellusMinecraft1211FeaturePlacementSink.java`

两侧均：

- 接收 caller-owned target chunk；
- 复用 shared `FeaturePlacementSinkGuard`；
- 使用注入的 `SemanticMapper<BlockState>`；
- 计算 owner target X/Z与 placement offsets；
- 将 semantic material映射为 target `BlockState`；
- 按 placement height写入 vertical span；
- 返回 generic `FeaturePlacementResult<BlockState>`。

mc1201/mc1211 target API保持在各自模块，sink没有 generator/lifecycle registration。

## 2. Status semantics

### 2.1 Plan-level guards

`FeaturePlacementSinkGuard.planResult`：

- `FeaturePlan.EMPTY` → `NO_WRITE_EMPTY`；
- `FeaturePlan.UNKNOWN` → `NO_WRITE_UNKNOWN`；
- `FeaturePlan.QUEUED` → `NO_WRITE_QUEUED`；
- `FeaturePlan.AVAILABLE` → 继续 placement validation。

所有 no-write result强制空 mapped writes；diagnostic优先使用 plan diagnostic。UNKNOWN/QUEUED不会触碰 target，EMPTY也不会产生空 feature write。

### 2.2 Placement-level statuses

`FeaturePlacementStatus`包括：

- `WRITTEN`；
- `NO_WRITE_EMPTY`；
- `NO_WRITE_UNKNOWN`；
- `NO_WRITE_QUEUED`；
- `NO_WRITE_OWNER`；
- `NO_WRITE_CONFLICT`；
- `INVALID`。

`FeaturePlacementResult`保证 no-write status不能携带 mapped writes。UNKNOWN material返回 `INVALID`，避免 mapper静默合成默认 block。

## 3. Owner与canonical/apron semantics

### 3.1 Canonical target

非-apron `WriteContext`要求 placement：

- `placement.apronMirror()==false`；
- `placement.owner()==context.targetOwner()`；
- `placement.canonicalOwner()==context.targetOwner()`。

因此 Earth、Sphere-Face、Cube-Net canonical placement不能跨 owner写入。targetBlock解析 owner-specific base X/Z，再叠加 semantic offset。

### 3.2 Apron mirror

apron context只接受：

- `placement.apronMirror()==true`；
- `placement.owner()==CUBE_NET_APRON`；
- placement canonical owner等于 `canonicalSourceCoordinate.owner().kind()`。

`MIRROR_OWNER` conflict policy要求 placement确实是 apron mirror；错误组合返回 `NO_WRITE_CONFLICT`或`NO_WRITE_OWNER`。apron base block使用 `CubeNetApronOwnerView.blockProjection()`，但 sink仍不把 apron提升为 canonical persistence owner。

### 3.3 FeaturePlacement invariants

`FeaturePlacement`自身继续验证：

- apron mirror必须使用 `MIRROR` operation；
- non-canonical owner需要 mirror operation；
- height至少为 1；
- featureId、material、owner、canonicalOwner、metadata非空；
- metadata immutable copy。

Sink guard在 context级别再次验证，防止单个 placement绕过 write owner boundary。

## 4. Conflict与mapping semantics

### 4.1 Conflict policy

当前 guard重点验证 policy合法组合：

- `MIRROR_OWNER`只能用于 apron mirror；
- canonical context不接受 apron mirror或跨 owner placement；
- apron context不接受普通 canonical placement。

`OWNER_ONLY`、`PRESERVE_EXISTING`、`REPLACE_TERRAIN`等 policy的完整 block conflict semantics仍由后续 concrete writer/storage层定义；本 sink当前不读取 existing block做策略判断，除 target set之外也没有 edit overlay/index。

### 4.2 Mapping and vertical span

对于每个 valid placement：

1. 取得 target owner base X/Z；
2. 加上 placement offset X/Z；
3. 对 `y = offsetY .. offsetY + height - 1`逐层调用 mapper；
4. 每层调用 `setBlockState(position, state, false)`；
5. 收集 mapped states并返回 `WRITTEN`。

未知 material在第一层 mapping前返回 `INVALID`。当前 sink不执行 surface/strata/heightmap/light/save/reload，也不处理 feature transaction。

### 4.3 Atomicity gap

当前实现先写已通过的 placement，之后 placement若 owner/conflict/material校验失败，会返回 no-write result，但此前已写入的 target mutations不会自动 rollback。因而：

- 推荐一个 FeaturePlan在进入 sink前完成全量 validation；
- 后续应增加 prepared mapped batch/transaction boundary；
- 在 atomic contract固定前，不应把 sink当作跨 placement all-or-nothing writer；
- parity或回滚失败时应回到 legacy feature path并输出 partial write diff。

## 5. Target parity与测试证据

### 5.1 Root guard test

- `src/test/java/com/yucareux/tellus/worldgen/feature/FeaturePlacementSinkGuardTest.java`

覆盖：

- UNKNOWN/QUEUED/EMPTY plan no-write statuses；
- valid canonical owner placement；
- `MIRROR_OWNER` without apron mirror conflict rejection。

### 5.2 mc1201/mc1211 sink tests

- `shared/mc1201/src/test/java/com/yucareux/tellus/worldgen/feature/TellusMinecraft1201FeaturePlacementSinkTest.java`
- `shared/mc1211/src/test/java/com/yucareux/tellus/worldgen/feature/TellusMinecraft1211FeaturePlacementSinkTest.java`

两侧覆盖：

- UNKNOWN/QUEUED/owner/conflict no-write before target；
- known canonical one-span mapping；
- target `GRASS_BLOCK` state与 mapped writes；
- target-specific bootstrap/ChunkAccess fixture。

Lead 已报告 task-96 mc1201/mc1211 compile + root guard targeted test `BUILD SUCCESSFUL`；本 review不额外启动测试。Target sink tests的语义与 API boundary仍按 source/test evidence审阅，完整 multi-placement atomicity尚未由现有测试证明。

## 6. No default writes/lifecycle

Feature placement sink不会：

- 自动调用 FeaturePlanner；
- 自动从 ColumnPipeline获取 plan；
- 自动注册 generator/runtime；
- attach/tick/detach lifecycle；
- 接入 TellusCommon/server/world/chunk hooks；
- 自动写 feature到所有生成路径；
- 创建默认 target chunk、mapper或 registry。

只有 caller显式调用 `place(context, plan, mapper)`且 plan/status/owner guards通过时才可能触碰 target。FeaturePlan创建本身不写入；sink实例存在本身不改变 legacy generator。

## 7. Legacy、rollback与剩余前置

### 7.1 Legacy unchanged

- Earth/Cube-Net/Sphere旧 feature paths保持默认；
- sink不替换旧 roads/buildings/trees/landmarks/OSM writers；
- UNKNOWN/QUEUED/EMPTY不自动回调 legacy writer，caller决定 untouched或fallback；
- target API与保存/编辑生命周期仍由旧路径负责。

### 7.2 Rollback points

1. **Sink opt-out**：停止显式调用 target sink，旧 feature writer继续。
2. **Plan status rollback**：UNKNOWN/QUEUED/EMPTY gate异常时恢复 no-write，禁止 default placement。
3. **Owner rollback**：owner/apron validation异常时禁用该 route，不扩大 supports范围。
4. **Mapping rollback**：semantic mapping错误时禁用 target mapper，回到 legacy material mapping。
5. **Atomicity rollback**：发现跨 placement partial mutation时停用 multi-placement sink，保留 prepared/legacy path。
6. **Lifecycle rollback**：sink无 lifecycle registration；未来 consumer失败只停用显式 caller，不修改默认 generator。
7. **Parity rollback**：任何 feature/column comparator变化立即停止 sink opt-in，输出 placement/write diff并回到 legacy。

## 8. Parity 门槛保持

本次 FeaturePlacementSink review不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

Root guard与mc1201/mc1211 sink tests没有 generator/lifecycle/parity replay接入；known one-span write不等于完整 feature parity。任何未来 sink consumer opt-in必须使用同一 comparator，新增 mismatch或partial mutation立即回滚。

## 9. 审阅结论

Task-96 FeaturePlacementSink boundary方向正确：generic sink与guard保留 status、owner、apron/canonical、conflict和UNKNOWN material semantics；mc1201/mc1211 target sinks平行映射 vertical placement，并在 plan/status/owner guard通过后写入。当前无默认 feature writes、无 lifecycle/generator registration；跨 placement atomicity、existing-block conflict semantics与save/reload仍是后续前置。

Task-97 review完成；后续若将 sink接入 Phase 10 plan、Phase 12 column pipeline或显式 runtime consumer，必须先固定 prepared batch/rollback、owner persistence和同一 `13,824 / 0`、`7 / 1 bare/BWh` parity门槛。
