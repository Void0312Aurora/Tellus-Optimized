# Tellus Phase 5 Writer Boundary 审阅

> 审阅对象：task-46 revision 3、task-49 revision 3 以及 task-50 revision 4 的 semantic writer/materializer contract。
>
> 审阅任务：task-48 / task-51 follow-up。
>
> 结论：**Phase 5 writer contract 已补齐 PARTIAL policy、canonical persistence identity、apron seam projection identity 和 persistence-aware WriteResult。** `WriteContext` 仍不包含 `ChunkAccess`/`BlockState`；UNKNOWN/QUEUED 明确 no-write，`NO_WRITE_PARTIAL` 可拒绝不完整列。当前仍没有接入 generator、storage、save/reload 或 runtime；首个 mc1201 canonical consumer adapter 的前置已从“补基础 contract”推进到“固定 PARTIAL policy、实现 semantic mapper、验证 persistence/idempotency，再保留 legacy writer fallback”。

## 1. 实现范围与验证

### 1.1 Writer 类型

- `src/main/java/com/yucareux/tellus/worldgen/write/TellusColumnWriter.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/TellusColumnMaterializer.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/WriteContext.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/WriteStatus.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/WriteResult.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/SemanticMapper.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/PartialWritePolicy.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/TellusPersistenceIdentity.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/CanonicalPersistenceKey.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/SeamProjectionIdentity.java`
- `src/test/java/com/yucareux/tellus/worldgen/write/TellusColumnWriteContractTest.java`

### 1.2 编译与 targeted test 验证

```powershell
./gradlew.bat -PtellusTargets=mc1201 :mc1201:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1211 :mc1211:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1201 :mc1201:test --tests "com.yucareux.tellus.worldgen.write.TellusColumnWriteContractTest" --offline --no-daemon --console=plain
```

结果：mc1201 与 mc1211 `compileJava` 均 `BUILD SUCCESSFUL`；task-47/49/50 由 Lead 串行执行的 targeted writer suite `BUILD SUCCESSFUL`。测试现覆盖 UNKNOWN/QUEUED/PARTIAL policy、partial sentinel/no-write、wrong decision/context invalid、canonical/apron owner source、canonical persistence key共享、apron derived identity、seam projection finite/stable、重复 materialize deterministic、opaque SemanticMapper 与 shared writer boundary 无 Minecraft API 依赖。


### 1.3 明确未接入

Writer boundary 当前没有引用/调用：

- `ChunkAccess`、`BlockState`、`BlockPos`、registry、heightmap、light、block entity；
- Earth/Cube-Net/Sphere-Face generator主循环；
- `SphereTerrainMaterializer`、`EarthGeneratorSettings` writer path、Cube-Net apron edit/index sync；
- `TellusWorldgenSources`、SampleProvider、FeaturePlanner source path；
- server lifecycle、runtime/payload、save/reload、DH/Voxy、client renderer；
- parity comparator或generator output。

`SemanticMapper<M>` 的 `M` 是 opaque target value，尚未绑定任何版本 API；`TellusColumnMaterializer` 目前只建立 status/ownership gate，known decision 的 surface/strata expansion明确 deferred。

## 2. Mapping 与 ChunkAccess 隔离

### 2.1 SemanticMapper contract

`SemanticMapper<M>` 只有：

```java
M map(SemanticMaterial material, int y, WriteContext context);
```

它把 semantic material 与目标版本 opaque value 分离：

- shared writer contract 不 import `BlockState`、`ChunkAccess` 或 registry；
- mc1201/mc1211 可分别实现 mapping；
- material→target mapping 可以按 target version、world settings、height range 和 owner view 注入；
- mapper 仍不能进行 source query、修改 decision 或写 storage。

接入前需要固定 UNKNOWN material、AIR/WATER、STONE/SNOW 等 semantic enum 的 target mapping与缺失 registry行为；mapping失败应返回明确 invalid/no-write，而不是默认石头。

### 2.2 TellusColumnWriter contract

`TellusColumnWriter<M>.write(context, decision, mapper)` 是版本中立写 boundary：

- 接收 `WriteContext`、immutable `ColumnDecision` 和 `SemanticMapper<M>`；
- 返回 immutable `WriteResult<M>`；
- 不规定 storage side effect，具体 implementation 才决定是否提交目标值；
- 目标版本 adapter 必须在更外层处理 ChunkAccess、heightmap/light、unsaved 和 save/reload。

当前 generic contract 未规定 mapper 调用次数、顺序、重复写幂等性、transaction/rollback 或 partial write semantics；这些必须在第一个旧 consumer adapter 前固定。

### 2.3 TellusColumnMaterializer current boundary

`TellusColumnMaterializer.materialize` 当前执行：

1. 校验 context/decision 非空；
2. 从 generation geometry + target coordinate 构造 `TellusPersistenceIdentity`，失败则 `NO_WRITE_INVALID`；
3. 调用 `WriteContext.acceptsDecision`，拒绝非 canonical source 的 decision；
4. UNKNOWN → `NO_WRITE_UNKNOWN`；
5. QUEUED → `NO_WRITE_QUEUED`；
6. PARTIAL + `PartialWritePolicy.NO_WRITE` → `NO_WRITE_PARTIAL`；
7. PARTIAL + `KNOWN_LAYERS_ONLY` → `PARTIAL`；AVAILABLE → `WRITTEN`；
8. 当前仍暂不发出 mapped writes，返回 persistence identity 与 `semantic material expansion deferred` diagnostics。

因此 `WRITTEN` 仍只表示 semantic boundary accepted，不代表 Minecraft storage 已发生写入；`NO_WRITE_PARTIAL` 让第一个 consumer adapter 可以先拒绝不完整列。

## 3. Canonical/apron persistence contract

### 3.1 WriteContext ownership

`WriteContext` 现在还携带 `PartialWritePolicy`，默认 legacy-compatible 值为 `KNOWN_LAYERS_ONLY`；consumer 可以显式使用 `NO_WRITE`，在完整 decision 前拒绝 PARTIAL。

它还提供：

- `canonicalPersistenceKey()`：canonical owner durable identity；apron通过 canonical source生成同一 key；
- `seamProjectionIdentity()`：仅 apron存在的 derived proxy/target face/chunk projection identity；
- `acceptsDecision()`：apron只接受 canonical source decision，非-apron只接受 target decision。
构造规则仍为：

- canonical/non-apron target 不得携带 canonical source；
- Cube-Net apron target 必须携带 canonical source；
- canonical source 必须是 `CUBE_NET_CANONICAL` owner；
- `acceptsDecision` 对 apron 只接受 canonical source coordinate，对 canonical target只接受 target coordinate。

这使 apron mirror 不会使用 proxy/apron column decision 作为独立 terrain authority。Writer 必须从 canonical source decision + target apron projection执行 mirror，不可重新采样或重新规划。

### 3.2 Persistence / edit overlay 前置

Task-49 已提供纯 persistence identity，但仍未连接 storage：

- `TellusPersistenceIdentity`：canonical owner/address 为 durable identity，apron 只携带 derived view；
- `CanonicalPersistenceKey`：把 geometry stable key、owner、face/block/chunk 固定成 canonical stable key；
- `SeamProjectionIdentity`：记录 apron proxy bits、target face/chunk projection；
- `WriteResult.persistenceIdentity`：让 semantic result 可携带 persistence identity，而不写入 storage。

尚未表达/实现的内容：

- edit overlay/index、block entity或saved chunk identity；
- generation revision、feature ID、write transaction ID；
- reload/replay idempotency、transaction/rollback；
- 实际 seam rotation/matrix应用和 ChunkAccess 持久化。

在接入 Cube-Net apron writer、edit index 或 block entity sync 前，target writer必须使用 canonical key作为唯一持久化 owner，并将 seam projection作为 derived view；不能把 apron proxy坐标作为第二 storage owner。

### 3.3 Canonical/apron mapping

`WriteContext.apronMirror()`只告诉 writer target owner是 apron；它不携带具体 seam transform。具体版本 writer仍需从 `TellusCoordinateContext`/`CubeNetApronResolver`读取 projection，并将 semantic offsets映射到目标面。不能直接把 `offsetX/offsetZ` 当目标面 block坐标。

## 4. UNKNOWN/QUEUED/PARTIAL write policy

### 4.1 UNKNOWN/QUEUED

当前 `WriteStatus` 固定：

- `NO_WRITE_UNKNOWN`：decision source unknown；
- `NO_WRITE_QUEUED`：snapshot尚未准备；
- `NO_WRITE_PARTIAL`：explicit NO_WRITE policy拒绝不完整 decision；
- `NO_WRITE_INVALID`：context/decision ownership或mapping invalid。

`WriteResult` 对 no-write status禁止携带 mapped writes。该 contract保护：

- no fake terrain/feature blocks；
- no write while non-blocking data queued；
- no write on wrong owner/coordinate；
- no accidental fallback to stone/air/water。

### 4.2 PARTIAL

`PartialWritePolicy` 现在明确两种策略：

- `KNOWN_LAYERS_ONLY`：允许只写 decision 已证明的 layers；
- `NO_WRITE`：完整 decision 之前保持列不变。

`WriteContext` 默认使用 `KNOWN_LAYERS_ONLY` 以保持兼容；首个旧 consumer adapter 建议显式选择 `NO_WRITE`，直到 layer-aware mapping、recompute、save/reload 和回滚协议稳定。`TellusColumnMaterializer` 在 `NO_WRITE` 下返回 `NO_WRITE_PARTIAL` 且不携带 mapped writes；在 `KNOWN_LAYERS_ONLY` 下返回 `PARTIAL`，当前仍只输出 deferred diagnostics。

仍需固定：

- partial water/cover/biome缺失时允许哪些 layers；
- PARTIAL canonical写入与之后完整重算的幂等；
- apron mirror是否必须等待 canonical AVAILABLE；
- target storage如何标记 pending/recompute（由后续 concrete writer负责）。

### 4.3 AVAILABLE semantics

`WriteStatus.WRITTEN` 目前可由 materializer返回，但 mappedWrites为空；这应被视为 semantic plan accepted，而不是 storage commit。真正 Writer 应在 mapping和target storage成功后才返回 WRITTEN；mapping/target failure应返回 NO_WRITE_INVALID或明确 failure status（未来可增加）。

## 5. 首个旧 consumer adapter 顺序

### 5.1 推荐选择

第一个 adapter 建议选 **单一 mc1201、单一 Earth/Sphere canonical column path 的纯 semantic sink**，而不是直接改 `CubeNetRuntime`、apron edit sync、client renderer 或完整 `EarthChunkGenerator`：

1. 先实现 root-level `SemanticMapper` fixture（不接 Minecraft storage）；
2. 实现一个 mc1201 adapter，只把已知 AVAILABLE `ColumnDecision` 映射为 opaque target values，并验证 WriteResult；
3. 再接一个现有 isolated column materialization boundary，保留旧 writer作为 feature flag/legacy fallback；
4. 只在 fresh disposable chunk fixture 中验证 height bounds、surface/strata mapping、no-write unknown/queued；
5. 保存/reload、edit overlay、block entity和lighting仍由旧路径负责，直到新的 writer contract覆盖；
6. 之后才考虑 Cube-Net canonical，最后才是 apron mirror。

### 5.2 暂不作为首个 adapter

- 不先改 `CubeNetRuntime`/transition payload/seam movement；
- 不先改 Cube-Net apron edit index/block entity sync；
- 不先改 Sodium/DH/client renderer；
- 不把 `SphereTerrainMaterializer` 或 `EarthChunkGenerator.ChunkSectionWriter` 一次性替换；
- 不同时接入多个 Minecraft版本；
- 不把 FeaturePlanner全家族或实际 OSM/building/road/tree feature同时接入。

### 5.3 Legacy fallback

首个 adapter必须：

- 默认关闭或按显式 feature flag启用；
- legacy writer仍是行为权威；
- UNKNOWN/QUEUED/PARTIAL contract失败时回到 legacy path，而不是写 default material；
- adapter 不改旧 source/cache/runtime；
- 回滚只切 adapter，不删除 semantic plan或旧保存数据。

## 6. Writer 前置与风险

### 6.1 Semantic mapping blockers

- SemanticMaterial 与 target-version BlockState/registry没有完整 mapping table；
- unknown material、water、air、bedrock、snow等缺少 fallback/invalid policy；
- y bounds、heightmap和sea-level mapping尚未由 writer验证；
- mapper是否pure、是否可重复、是否线程安全未声明；
- `WriteResult.WRITTEN` 与实际 storage commit语义需分离。

### 6.2 Storage/persistence blockers

- ChunkAccess/heightmap/light/unsaved/block entity尚未接入；
- canonical/apron persistence key、seam projection、edit overlay未定义；
- write transaction/rollback/reload idempotency未定义；
- PARTIAL writes、recompute和legacy saved chunk兼容未定义；
- feature placement writes 与 column writes 的 conflict order未对齐。

### 6.3 No second sampling rule

Writer必须只消费 `ColumnDecision`/FeaturePlan和已验证 mapping；不允许：

- 在 map/materialize阶段重新调用 SampleProvider；
- 读取 source singleton/cache registry；
- 对 UNKNOWN/QUEUED自行 fallback；
- 对 apron重新生成canonical decision；
- 修改 decision/plan后再写。

## 7. 无 generator 接入与回滚边界

### 7.1 当前明确未接入

- Earth/Cube-Net/Sphere-Face generator主循环；
- `ChunkAccess`、BlockState、registry、heightmap/light/block entity；
- FeaturePlanner consumer、SampleProvider/source backend；
- runtime lifecycle/payload/client renderer/DH/Voxy；
- world save/reload/edit overlay；
- parity comparator/generator output path。

### 7.2 回滚点

1. **Contract-only rollback**：移除 write package；旧 generator/source/feature path不变。
2. **Mapper rollback**：mapping失败时禁用版本 mapper，保留 semantic decision与legacy writer。
3. **Canonical rollback**：canonical writer出现 storage/persistence问题时回到 legacy column writer。
4. **Apron rollback**：mirror projection/persistence异常时只禁用 apron branch，不回滚canonical/legacy path。
5. **Status rollback**：PARTIAL策略有误时恢复 UNKNOWN/QUEUED/PARTIAL no-write，停止临时写入。
6. **Parity rollback**：任何 `13,824` 或 biome case变化立即停用 writer opt-in，输出 decision/plan/write diff，回到legacy path。

## 8. 最小测试矩阵与 parity 门槛

不扩大测试面，新增纯 writer contract tests并复用现有 geometry/sample/decision/source/parity证据：

| 类别 | 测试/证据 | 门槛 |
|---|---|---|
| WriteContext | `TellusColumnWriteContractTest` | canonical/apron source ownership严格；wrong decision拒绝 |
| status gate | `TellusColumnWriteContractTest` decision fixtures | UNKNOWN/QUEUED no-write；PARTIAL policy明确 |
| mapping | `TellusColumnWriteContractTest` opaque mapper | material/y/owner mapping deterministic、shared contract不依赖Minecraft |
| result invariants | `TellusColumnWriteContractTest` | no-write不含mapped writes；重复materialize deterministic |
| apron mirror | CubeNet apron fixtures | canonical source only、seam projection由writer负责 |
| persistence identity | task-49 writer contract tests | canonical key稳定；apron derived view不成为owner；seam projection identity可诊断 |
| partial policy | task-49 writer contract tests | KNOWN_LAYERS_ONLY/PARTIAL 与 NO_WRITE/NO_WRITE_PARTIAL语义稳定 |
| first adapter | 单一mc1201 isolated column fixture | 显式选择NO_WRITE或已证明known-layer mapping；legacy fallback、bounds、no second sampling |
| storage | 后续 ChunkAccess adapter tests | heightmap/light/unsaved/block entity顺序正确 |
| parity | Cube/Sphere `13,824 / 0 mismatch` | writer未接入时无变化；未来opt-in双路径0 mismatch |
| biome | Earth/Cube `7 cases / 1 mismatch bare/BWh` | 不新增mismatch，不在writer修复bare/BWh |

### 8.1 接入顺序

1. **已完成（task-47）**：纯 WriteContext/WriteResult/SemanticMapper/materializer contract tests；
2. UNKNOWN/QUEUED/PARTIAL/AVAILABLE semantic fixtures与 PARTIAL policy；
3. canonical/apron ownership/persistence fixtures；
4. 单一 mc1201 isolated legacy consumer adapter；
5. 目标版本 storage mapping和save/reload tests；
6. 对照同一 parity comparator；
7. 所有门槛稳定后，才允许一个generator family opt-in。

## 9. Parity 门槛保持

本次 Writer boundary 审阅不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

当前 writer boundary没有接入generator/provider/FeaturePlanner/storage，因此没有新的 parity结果。`TellusColumnMaterializer` 的 deferred semantic result不构成真实 block output parity；未来首个旧 consumer adapter必须与legacy writer使用同一 comparator。任何新增 mismatch都是迁移闸门失败，不能以PARTIAL policy、semantic mapping或apron mirror解释为通过。

## 10. 审阅结论

Task-46/49/50 writer contract的方向正确，task-47/49/50 targeted contract tests已验证关键纯边界：semantic material mapping、owner-aware WriteContext、canonical/apron source关系、PartialWritePolicy、canonical persistence key、derived seam identity和 no-write status均隔离在版本无关 boundary中；UNKNOWN/QUEUED不写，显式 NO_WRITE policy下 PARTIAL返回 NO_WRITE_PARTIAL，apron必须从canonical source接受decision。当前仍未接入实际 storage，known decision的surface/strata expansion和ChunkAccess持久化仍 deferred。

下一步应固定 concrete semantic mapper、KNOWN_LAYERS_ONLY 的逐层 mapping、canonical/apron save/reload/idempotency和projection-aware Writer，再选择单一 mc1201 isolated legacy consumer adapter；保持legacy writer为默认与回滚路径，并继续通过同一 `13,824 / 0`、`7 / 1 bare/BWh` comparator。
