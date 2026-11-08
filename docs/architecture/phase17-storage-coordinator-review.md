# Tellus Phase 17 Storage Transaction Coordinator 审阅

> 审阅对象：task-101 `StorageTransactionCoordinator`、injected atomic backend contract与 coordinator status mapping。
>
> 审阅任务：task-103。
>
> 结论：**Phase 17 将 prepared transaction与实际 storage commit/reload连接为显式 caller-owned coordinator，但仍要求注入 `AtomicStorageBackend`；coordinator不创建默认 backend、不安装 save hook、不自动 retry/fallback。commit先经过既有 `StorageTransactionBoundary`，只有 PREPARED transaction才交给 backend；UNKNOWN/QUEUED/PARTIAL/disabled/apron fallback在 backend之前终止。backend的 committed/already/idempotency/not-found/rejected/failed状态被明确映射为 coordinator结果。**

## 1. 实现范围

### 1.1 Coordinator API

- `src/main/java/com/yucareux/tellus/worldgen/write/StorageTransactionCoordinator.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/StorageCoordinatorStatus.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/StorageCoordinatorResult.java`
- `src/main/java/com/yucareux/tellus/worldgen/write/AtomicStorageBackend.java`
- existing `StorageTransactionBoundary` / `StorageTransaction` / `StorageCommitPolicy`。

构造器必须注入：

- `AtomicStorageBackend<M>`；
- 可选的 `StorageTransactionBoundary`。

公开操作：

```java
commit(WriteContext context, WriteResult<M> result,
       StorageCommitPolicy policy, String operationRevision)
reload(CanonicalPersistenceKey key, String idempotencyKey)
```

`StorageCoordinatorResult`为 immutable result，携带 status、optional transaction、diagnostics；成功状态必须携带 transaction，非成功状态禁止携带 transaction。

## 2. Commit/reload status semantics

### 2.1 Coordinator statuses

- `COMMITTED`：backend首次成功 commit；
- `ALREADY_COMMITTED`：同一 canonical/idempotency transaction重复提交；
- `RELOADED`：reload成功；
- `NOT_FOUND`：backend找不到 canonical transaction；
- `IDEMPOTENCY_CONFLICT`：同一 key使用不同 idempotency key；
- `BACKEND_REJECTED`：backend拒绝；
- `BACKEND_FAILED`：backend失败；
- `NO_COMMIT_DISABLED`：commit policy disabled；
- `NO_COMMIT_UNKNOWN` / `NO_COMMIT_QUEUED`：输入状态不可提交；
- `NO_COMMIT_PARTIAL`：partial policy拒绝；
- `NO_COMMIT_FALLBACK`：apron/unsupported/fallback policy阻断。

### 2.2 Commit flow

`commit`固定顺序：

1. `StorageTransactionBoundary.prepare(...)`计算 canonical key/idempotency key与 eligibility；
2. 非-eligible transaction转换为 `NO_COMMIT_*`，不调用 backend；
3. PREPARED transaction调用注入的 `backend.commit`；
4. `StorageBackendStatus`映射为 coordinator status；
5. backend transaction与diagnostics原样进入 coordinator result。

coordinator没有自动 retry、legacy writer fallback或第二次 commit。

### 2.3 Reload flow

`reload`要求非空 canonical key与非空 idempotency key，调用 `backend.reload`：

- backend `COMMITTED` → coordinator `RELOADED`；
- backend `ALREADY_COMMITTED` → `ALREADY_COMMITTED`；
- backend `NOT_FOUND`、`IDEMPOTENCY_CONFLICT`、`REJECTED`、`FAILED`分别映射；
- reload不重新采样、不重建 feature/column plan、不触发 generator/lifecycle。

## 3. Injected backend boundary

`AtomicStorageBackend<M>`是唯一 concrete storage boundary：

```java
StorageBackendResult<M> commit(StorageTransaction<M> transaction);
StorageBackendResult<M> reload(CanonicalPersistenceKey key, String idempotencyKey);
```

backend实现者拥有：

- actual persistence store；
- synchronization/atomicity；
- save/reload implementation；
- idempotency record；
- transaction recovery/error mapping。

shared coordinator不假设 backend是 Minecraft `ChunkAccess`、region file、world save或数据库，也不读取 backend internals。`StorageBackendResult`同样禁止成功状态缺少 transaction，禁止失败状态携带 transaction。

## 4. Canonical/apron与existing guards

coordinator先经过 `StorageTransactionBoundary`，保留既有规则：

- canonical owner可准备 transaction；
- apron target为 derived view，返回 `NO_COMMIT_FALLBACK`；
- canonical persistence key是 durable identity；
- seam projection仅作为 derived transaction metadata；
- UNKNOWN/QUEUED/partial policy拒绝不会到 backend；
- no-commit transaction不能携带 writes。

因此 coordinator不会把 apron proxy当第二 storage owner，也不会绕过 WriteContext owner/decision checks。backend也不接收未准备的 transaction。

## 5. No default save hook/lifecycle

当前 coordinator没有接入：

- Minecraft save/reload hook；
- `ChunkAccess`、region/world storage；
- generator constructor/codec；
- TellusCommon、server/world/chunk lifecycle；
- runtime registry/attach/tick/detach；
- pipeline/writer/FeaturePlanner自动发现；
- network payload、DH/Voxy/client renderer。

构造 coordinator只保存 boundary/backend，不执行 commit/reload。调用 `commit`/`reload`必须由显式 caller发起；不存在默认 global coordinator、默认 backend或自动保存。

## 6. Task-101 targeted test evidence

Lead 已串行执行 coordinator targeted test并报告 `BUILD SUCCESSFUL`。现有：

- `src/test/java/com/yucareux/tellus/worldgen/write/StorageTransactionCoordinatorTest.java`

覆盖：

- injected in-memory backend首次 commit → `COMMITTED`；
- same idempotency replay → `ALREADY_COMMITTED`；
- different operation revision on same key → `IDEMPOTENCY_CONFLICT`；
- reload success → `RELOADED`；
- UNKNOWN与disabled policy → `NO_COMMIT_UNKNOWN` / `NO_COMMIT_DISABLED`；
- no-write statuses不调用 backend；
- backend commit count保持一次，证明 coordinator不隐式重复提交。

该 test使用显式 in-memory backend fixture，不代表真实 Minecraft save/reload或world transaction已经接入。本 review不额外启动测试。

## 7. Legacy、rollback与atomicity

### 7.1 Legacy/default unchanged

- coordinator不替换 legacy writer或generator；
- backend未注入时没有可执行 commit路径；
- `NO_COMMIT_*`不自动回调 legacy writer；
- commit失败不会隐式重试或双写；
- coordinator不会修改 source/cache/payload/runtime。

### 7.2 Rollback points

1. **Coordinator opt-out**：停止显式调用 coordinator，旧 writer/save path继续。
2. **Backend rollback**：backend rejected/failed时只返回状态，不生成第二次隐式 commit。
3. **Idempotency rollback**：冲突保持 `IDEMPOTENCY_CONFLICT`，caller决定修复 revision或恢复 legacy。
4. **Reload rollback**：NOT_FOUND/冲突不合成 transaction、不恢复默认 blocks。
5. **Canonical/apron rollback**：apron NO_COMMIT_FALLBACK保持，禁止把 derived view写成 canonical record。
6. **Lifecycle rollback**：无默认 save hook/lifecycle；未来 caller接入失败时停用 coordinator consumer。
7. **Parity rollback**：任何 storage opt-in改变 column/biome comparator，停止 coordinator consumer并回到 legacy。

Atomicity仍由注入 backend负责；coordinator只保证 prepared eligibility、status propagation与单次显式 backend handoff。

## 8. Parity 门槛保持

本次 StorageTransactionCoordinator review不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

Coordinator targeted test只验证 in-memory backend/status/idempotency contract，没有 generator/save/reload/parity replay接入；`COMMITTED`不代表 worldgen parity已重新运行。任何真实 backend opt-in必须与 legacy path使用同一 comparator，新增 mismatch立即回滚。

## 9. 审阅结论

Task-101 coordinator boundary正确：它以显式 injected backend为唯一 storage authority，复用 prepared transaction/canonical-apron guards，保留 no-commit与backend/idempotency/reload statuses；没有 default save hook、自动 lifecycle、implicit retry或legacy fallback。Lead targeted test已验证首次提交、幂等重放、冲突、reload与no-write不触 backend。

Task-103 review完成；后续若接入真实 world storage，必须先定义 backend atomicity/recovery、save/reload persistence、caller opt-in与同一 `13,824 / 0`、`7 / 1 bare/BWh` parity门槛。
