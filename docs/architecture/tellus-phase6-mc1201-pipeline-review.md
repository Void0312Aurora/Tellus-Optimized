# Tellus Phase 6 mc1201 Opt-in Pipeline 审阅

> 审阅对象：task-55 revision 3、task-56 revision 3 以及 task-58 revision 3 的 `TellusMinecraft1201CanonicalPipeline` 与 storage transaction boundary。
>
> 审阅任务：task-57 / task-60 follow-up。
>
> 结论：**Phase 6 pipeline 将 SampleProvider → DecisionEngine → FeaturePlanner → semantic materializer → mc1201 canonical writer 串成显式 opt-in 流程；默认 disabled，不注册 generator/lifecycle，所有 read-only stages通过后才创建/触碰 target writer。** UNKNOWN/QUEUED sample、decision或feature plan会在目标 chunk触碰前 no-write；apron target显式 invalid；legacy generator/writer仍是默认行为。当前 pipeline只写 canonical top cell，feature placements、完整 column layers、storage persistence和parity output仍未接入。

## 1. 实现范围与验证

### 1.1 Pipeline 类型

- `shared/mc1201/src/main/java/com/yucareux/tellus/worldgen/write/TellusMinecraft1201CanonicalPipeline.java`
- `shared/mc1201/src/main/java/com/yucareux/tellus/worldgen/write/TellusMinecraft1201ColumnWriter.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/StorageFallbackPolicy.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/StorageCommitPolicy.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/StorageTransactionStatus.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/StorageTransaction.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/StorageTransactionBoundary.java`
- 复用：`TellusMinecraft1201CanonicalColumnAdapter`、`TellusMinecraft1201SemanticMapper`、shared `TellusColumnMaterializer`。

Pipeline构造依赖：

- `boolean enabled`；
- `TellusSampleProvider`；
- `TellusColumnDecisionEngine`；
- `TellusFeaturePlanner`；
- `TellusColumnMaterializer`；
- `SemanticMapper<BlockState>`。

### 1.2 编译验证

```powershell
./gradlew.bat -PtellusTargets=mc1201 :mc1201:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1211 :mc1211:compileJava --offline --no-daemon --console=plain
```

结果：mc1201 与 mc1211 `compileJava` 均 `BUILD SUCCESSFUL`（本轮均 `UP-TO-DATE`）。task-56 由 Lead 串行执行的 pipeline targeted test `BUILD SUCCESSFUL`，验证 disabled gate、stage order、sample/decision/feature no-write、canonical top-cell sink 与 legacy-safe boundary；task-59 writer targeted test 已由 Lead 串行执行并 `BUILD SUCCESSFUL`，覆盖 transaction handoff 前的 canonical writer/no-write behavior。task-58 增加的 transaction contract 保持 pure preparation：可计算 canonical/idempotency identity与 PREPARED/no-commit 状态，但不执行 save/reload或engine storage commit。mc1211是回归编译目标，未引入 mc1201 target adapter。

该 targeted pipeline/transaction evidence不证明 generator registration、server lifecycle、真实 storage commit、save/reload 或 parity output 已接入。
## 2. 默认 legacy 与 explicit enable

### 2.1 Disabled default boundary

Pipeline只由显式构造者提供 `enabled`：

- `enabled=false` 时立即返回 `NO_WRITE_INVALID`，diagnostic为 pipeline disabled；
- disabled path不校验/读取 target、context、generation、coordinate、request，也不调用 provider、decision、planner或writer；
- 没有 system-wide default enable、静态 singleton、registry或自动发现；
- 没有 generator constructor、loader lifecycle或server callback接入。

因此 legacy generator/writer保持默认行为。未来 consumer必须在独立 feature flag/实验分支显式创建 `enabled=true` pipeline，并保留 disabled fallback。

### 2.2 Explicit enable input gate

enabled后，pipeline依次校验：

1. context/generation/coordinate/request非空；
2. target context与 coordinate相等；
3. request.coordinate与 coordinate相等；
4. target不是 Cube-Net apron；
5. provider sample返回非空且 request identity由 sample contract保证；
6. sample UNKNOWN/QUEUED no-write；
7. DecisionEngine geometry/coordinate/status gate；
8. decision UNKNOWN/QUEUED no-write；
9. FeaturePlanner返回非空且 plan UNKNOWN/QUEUED no-write；
10. 最后才创建 `TellusMinecraft1201ColumnWriter(target, materializer)`并调用 writer。

目标 chunk只在所有 read-only stages通过后才被传给 sink-backed writer，满足 no-write-before-target。

## 3. Pipeline stage contract

### 3.1 SampleProvider stage

`sampleProvider.sample(request)` 是唯一 source entry：

- pipeline不自行调用 source/cache/registry；
- sample UNKNOWN/QUEUED直接返回 no-write；
- sample PARTIAL继续交给 DecisionEngine/FeaturePlanner，由下游 policy决定；
- sample provenance/status不被pipeline重写。

provider失败/exception不会在pipeline中被转换成 default stone；具体 provider contract应保持 UNKNOWN/PARTIAL/QUEUED。

### 3.2 DecisionEngine stage

DecisionEngine消费 generation/coordinate/sample：

- geometry/coordinate mismatch抛出或返回明确 invalid，由 pipeline保持失败；
- UNKNOWN/QUEUED decision no-write；
- PARTIAL decision可以继续，受 Writer `PartialWritePolicy`约束；
- semantic decision目前是 baseline stone/plains/top Y，不是完整 Earth/Cube policy。

Pipeline不修改 decision，也不把 sample status升级为 AVAILABLE。

### 3.3 FeaturePlanner stage

FeaturePlanner被调用并且其 plan status成为 pipeline gate：

- UNKNOWN/QUEUED plan no-write；
- EMPTY plan目前允许继续到 column writer；
- AVAILABLE plan当前只被验证，不把 placements传给 mc1201 writer；
- pipeline只写 ColumnDecision top cell，不写 FeaturePlan placements、roads/buildings/trees/landmarks。

因此“FeaturePlanner stage通过”不等于 features已写入；完整 feature writer仍是后续工作。若未来 feature plan AVAILABLE但 column writer失败，pipeline需要 feature/column transaction policy，当前尚未定义。

### 3.4 ColumnWriter stage

`TellusMinecraft1201ColumnWriter`是最后 target sink：

- 使用 shared materializer guard；
- 使用 1.20.1 mapper；
- 只写 canonical top cell；
- aprons已在 pipeline gate拒绝；
- 完整 surface/filler/strata/water/heightmap/light/save deferred。

Writer失败/partial status不会自动接入 legacy writer；consumer必须显式处理 `WriteResult`并决定 fallback。

## 4. Transaction boundary 与 save/reload 未接入

### 4.1 Pure transaction preparation

Task-58 增加了 shared pure transaction boundary：

- `StorageCommitPolicy`：enabled、PARTIAL policy、fallback policy；
- `StorageFallbackPolicy`：`NO_COMMIT` / `DEFER_READ_ONLY`；
- `StorageTransactionStatus`：`PREPARED`、disabled/unknown/queued/partial/fallback no-commit；
- `StorageTransaction`：canonical key、idempotency key、mapped writes、diagnostics、optional seam projection；
- `StorageTransactionBoundary.prepare(...)`：只计算 commit eligibility，不执行 save/reload/ChunkAccess storage。

事务准备顺序：

1. 从 WriteContext 计算 canonical persistence key；
2. 以 `canonicalKey + operationRevision` 形成 idempotency key；
3. disabled、apron、UNKNOWN、QUEUED、PARTIAL policy不允许时返回对应 NO_COMMIT；
4. 只有 policy enabled、非 no-write、且 partial policy允许时返回 PREPARED；
5. PREPARED 携带 result mapped writes，但 commit仍由外部 storage owner执行。

### 4.2 Canonical/apron transaction rules

- canonical owner可以准备 transaction；
- apron target永远 `NO_COMMIT_FALLBACK`，因为derived view不能直接commit；
- apron的 seam projection可随 transaction诊断保留，但不替代canonical key；
- 相同 canonical key + operationRevision可通过 `idempotentlyMatches`判断重复准备；
- non-committable status不得携带 writes。

### 4.3 Save/reload仍未接入

当前 task-58 boundary没有：

- 调用 `ChunkAccess`、Minecraft save API、region file、world storage或reload；
- 设置 heightmap/light/unsaved/block entity；
- 写入 edit overlay/index、transaction log或commit marker；
- 执行 rollback/atomic commit/transaction recovery；
- 将 idempotency key持久化到 world save。

因此 `PREPARED` 不是 `COMMITTED`，`NO_COMMIT_*` 也不执行 legacy writer。外部 consumer必须明确选择 commit/fallback，并保持 legacy default。

## 5. Legacy fallback 与默认 generator

### 5.1 Default legacy behavior

- `TellusMinecraft1201CanonicalPipeline.enabled=false`仍立即返回 no-write；
- pipeline与transaction boundary没有 generator constructor、registry、loader lifecycle或server tick注册；
- 默认 Earth/Cube-Net/Sphere-Face generator继续使用旧 writer/source/runtime；
- task-58不会把 `StorageCommitPolicy.canonicalDefault()`自动注入任何现有 generator；
- pipeline/transaction只由显式 consumer构造，disabled/invalid/no-commit由consumer决定是否保留 untouched或回legacy。

### 5.2 Fallback policy

`StorageFallbackPolicy.NO_COMMIT`表示准备结果不交给任何 storage path；`DEFER_READ_ONLY`只表示外部调用者可稍后处理，不能在 boundary内自动调用 legacy writer。首个 consumer应：

- legacy writer默认；
- pipeline/transaction opt-in；
- no-commit保持 chunk untouched或显式回legacy，但避免双写；
- commit失败时按 transaction/idempotency policy回滚，不把失败重试隐式写成第二份 canonical记录。

## 6. No generator/lifecycle registration

仓库中 pipeline当前未被以下路径注册/调用：

- `EarthChunkGenerator`、`CubeNetChunkGenerator`、`SphereFaceChunkGenerator`；
- `TellusCommon`、`TellusRuntimePlatform`、server started/tick/chunk unload/player callbacks；
- `CubeNetRuntime`、SphereFaceRuntime、payload/transition/seam movement；
- `TellusWorldgenSourcesProviderFactory`、global provider registry、preload registry；
- FeaturePlanner registry、writer registry、save/reload/edit overlay；
- Sodium/DH/Iris/Voxy/client renderer。

`TellusMinecraft1201CanonicalPipeline`的存在本身不改变任何旧世界/旧生成路径；只有显式调用 `write(...)` 且 enabled=true才有可能触碰 target。

## 7. Legacy fallback 与回滚点

### 7.1 Legacy default

- disabled pipeline返回 no-write，不取代 legacy；
- legacy Earth/Sphere/Cube generator/writer继续唯一行为权威；
- pipeline不修改 source/cache/runtime/save；
- consumer收到 disabled/invalid/unknown/queued/partial result时应回到 legacy writer或保留 chunk untouched；
- 不自动在pipeline内部调用 legacy writer，避免双写和不可控 fallback。

### 7.2 回滚点

1. **Pipeline-off rollback**：enabled切回false，所有 target write停止，legacy path继续。
2. **Stage rollback**：provider/decision/planner任一 stage异常时禁用pipeline，不改变旧 source/decision/writer。
3. **Writer rollback**：mapper/ChunkAccess/storage问题只停用 mc1201 writer，保留 shared semantic pipeline或legacy writer。
4. **Feature rollback**：目前 feature placements未写；未来feature sink异常时回退到 column-only或legacy，并保持 canonical owner。
5. **Apron rollback**：apron永远走 no-write，直到独立 mirror adapter/transaction验证通过。
6. **Parity rollback**：任何 `13,824` 或 biome case变化立即关闭 opt-in，输出 sample/decision/plan/write diff，回到legacy path。

## 8. 首个 opt-in consumer 前置

### 8.1 必须固定的 contract

- explicit enable source/feature flag与默认 disabled存档/配置语义；
- provider/decision/planner status gate和异常分类；
- PARTIAL `KNOWN_LAYERS_ONLY` vs `NO_WRITE` 选择；
- FeaturePlan placements与ColumnDecision是否原子提交；
- top-cell与后续 strata/water扩展顺序；
- target ChunkAccess setBlockState flags、heightmap/light/unsaved；
- canonical persistence key、save/reload、idempotency；
- legacy fallback何时触发、避免双写；
- no second sampling/no source side effect。

### 8.2 推荐首个 consumer

选单一 mc1201、单一 canonical Earth/Sphere/Cube column fixture：

1. 构造 explicit enabled pipeline，注入 synthetic/test-double provider、decision engine、empty planner和mapper；
2. 用 fresh disposable ChunkAccess验证 known AVAILABLE top cell；
3. 逐项验证 disabled/UNKNOWN/QUEUED/NO_WRITE_PARTIAL/apron no-write且target untouched；
4. 对照legacy top-cell state、persistence identity和WriteResult；
5. 保存/reload、heightmap/light、完整 layers仍留在后续 adapter；
6. 首个通过后才允许一个真实 generator family opt-in。

## 9. 最小测试矩阵与 parity 门槛

不扩大测试面，新增纯 pipeline/target fixture并复用现有 contract/geometry/decision证据：

| 类别 | 测试/证据 | 门槛 |
|---|---|---|
| disabled | 新增 pipeline tests | enabled=false不调用任何 stage、不触碰target |
| stage order | provider/decision/planner doubles | sample→decision→plan→writer顺序稳定 |
| no-write-before-target | target spy/ChunkAccess fixture | UNKNOWN/QUEUED/invalid/PARTIAL NO_WRITE/apron均不触碰target |
| canonical write | mc1201 fixture | AVAILABLE top cell mapping正确；legacy可对照 |
| partial | WriteContext/PartialWritePolicy tests | explicit policy稳定；不升级status |
| feature | planner doubles | EMPTY允许column path；placements当前不写，行为明确 |
| fallback | isolated consumer fixture | pipeline disabled/invalid回legacy，无双写 |
| transaction | task-58 StorageTransactionBoundary tests | PREPARED仅表示外部commit eligible；disabled/unknown/queued/partial/apron no-commit |
| persistence | task-49/50 identity tests | canonical/apron key稳定；save/reload前置明确 |
| regression | mc1211 compile | 无mc1201 API泄漏 |
| parity | Cube/Sphere `13,824 / 0 mismatch` | pipeline未注册时无变化；未来opt-in双路径0 mismatch |
| biome | Earth/Cube `7 cases / 1 mismatch bare/BWh` | 不新增mismatch，不在pipeline修复bare/BWh |

### 9.1 接入顺序

1. **已完成（task-56）**：disabled/no-write/stage-order pipeline tests；
2. **已完成（task-58）**：pure StorageTransactionBoundary eligibility/idempotency contract；
3. synthetic provider + baseline DecisionEngine + empty planner；
4. mc1201 canonical top-cell fixture；
5. legacy state/persistence/fallback对照；
6. 外部 storage/save/reload/heightmap/light；
7. FeaturePlan placements独立sink；
8. 对照同一 parity comparator；
9. 所有门槛稳定后才允许generator family opt-in。

## 10. Parity 门槛保持

本次 Phase 6 pipeline审阅不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

当前 pipeline没有注册generator/lifecycle、没有真实 consumer/parity output，因此没有新的parity结果。它的single top-cell sink不构成完整column或biome parity；任何新增mismatch都是迁移闸门失败，不能以explicit enable、FeaturePlanner EMPTY、top-cell scope或legacy fallback解释为通过。

## 11. 审阅结论

Task-55/56/58/59 pipeline边界正确：默认disabled，显式enabled才运行；sample→decision→feature plan→writer顺序明确；所有read-only stages通过前才触碰target；UNKNOWN/QUEUED/invalid/apron no-write；mc1201 mapper/ChunkAccess API仅在version adapter层。task-56 pipeline test验证了 disabled gate、stage order、sample/decision/feature no-write、canonical top-cell sink和legacy-safe boundary；task-59 writer targeted test已通过；task-58 transaction boundary进一步保证 `PREPARED` 只表示外部 commit eligible，不执行 save/reload，apron/unknown/queued/partial/disabled均走 no-commit。默认 generator/lifecycle 未注册，legacy仍是行为权威。

Task-60 review完成。下一步应在 transaction preparation gate基础上补单一mc1201 canonical fixture、外部 storage commit/save-reload adapter和legacy fallback transaction；任何 generator opt-in前继续保持默认 legacy，并通过 `13,824 / 0`、`7 / 1 bare/BWh` parity门槛。
