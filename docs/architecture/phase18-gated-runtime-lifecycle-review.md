# Tellus Phase 18 Gated Runtime Lifecycle Manager 审阅

> 审阅对象：task-102 `TellusGeneratorRuntimeLifecycleManager`、registry unregister/stale cleanup与 manager contract test。
>
> 审阅任务：task-104。
>
> 结论：**Phase 18 提供了一个 caller-owned、feature-gated lifecycle manager。默认构造为 disabled；只有显式 `enabled=true` manager callback才会经 readiness-gated bridge attach/tick/unregister。manager不创建 registry、不推断 generator registration、不安装 TellusCommon/server hooks。exact identity unregister与server stopping cleanup可清除 stale registry entries，但不触碰 save/reload或world storage。**

## 1. 实现范围

### 1.1 Manager与cleanup类型

- `src/main/java/com/yucareux/tellus/worldgen/runtime/TellusGeneratorRuntimeLifecycleManager.java`
- updated `TellusGeneratorRuntimeRegistry.unregister(...)`；
- updated `TellusGeneratorRuntimeRegistry.unregisterAll()`；
- existing `TellusGeneratorRuntimeLifecycleBridge` / `AttachPolicy` / `BridgeResult` / `LifecycleEvent`。

Manager构造器接收：

- caller-owned `TellusGeneratorRuntimeRegistry`；
- optional enabled flag（默认false）；
- optional caller-provided readiness probe。

内部仅创建一个 bridge：

- enabled → `AttachPolicy.REQUIRE_CAPABILITY_READY`；
- disabled → `AttachPolicy.DISABLED`。

### 1.2 Explicit callbacks

公开 callback：

```java
onGeneratorRegistered(ChunkGenerator generator)
onServerTick()
onGeneratorUnregistered(ChunkGenerator generator)
onServerStopping()
```

这些方法是显式调用入口，不是自动 hooks。构造 manager不会 attach、tick、detach或清理 registry。

## 2. Disabled/enabled semantics

### 2.1 Default disabled

`new TellusGeneratorRuntimeLifecycleManager(registry)`：

- `enabled()==false`；
- onGeneratorRegistered → `DISABLED` / `REJECTED`；
- onServerTick → `DISABLED`；
- onGeneratorUnregistered → `DISABLED`；
- onServerStopping → `DISABLED`；
- registry entry保持不变；
- runtime保持 unattached。

disabled manager不把 registry entry当作自动 lifecycle授权，符合 legacy/default fail-closed边界。

### 2.2 Explicit enabled

`enabled=true`时：

- onGeneratorRegistered经 bridge exact identity resolve与 capability readiness；
- readiness通过后 → `ATTACHED`或`ALREADY_ATTACHED`；
- onServerTick只分发到 attached runtime；
- onGeneratorUnregistered按 exact identity detach并移除 entry；
- onServerStopping逆序 detach并清空 registry。

enabled只打开显式 callback的权限，不主动发现 generator或安装 callback。

## 3. Caller-owned registry与exact identity

### 3.1 Registry ownership

manager不创建 registry：

- caller决定 registry scope、生命周期和 registration timing；
- manager不保存跨-world global registry；
- tests/worlds可隔离 registry instance；
- manager shutdown通过显式 onServerStopping清理 caller-owned registry。

### 3.2 Exact identity register/unregister

`onGeneratorUnregistered(generator)`调用 `registry.unregister(generator)`：

- 只匹配同一个 generator object identity；
- 找不到 exact identity → `NOT_FOUND`，已有 registration保留；
- 找到 → detach runtime、移除对应 entry、返回 `UNREGISTERED`；
- 不按 class、equals或 generator id 清除其他 entry。

这解决早期 registry缺少 unregister的 stale identity blocker。

### 3.3 Server stopping cleanup

`onServerStopping()`调用 `registry.unregisterAll()`：

- reverse registration order detach；
- 清空 entries；
- 返回 `UNREGISTERED_ALL`与 count diagnostic；
- 不保存 world、不reload chunk、不触碰 backend/storage。

## 4. Readiness/tick boundary

### 4.1 Readiness

enabled manager使用 `REQUIRE_CAPABILITY_READY`：

- readiness probe在 attach前执行；
- not-ready → `NOT_READY`，不 attach、不修改 lifecycle state；
- ready → registry.attach；
- probe结果只作为 BridgeResult snapshot；
- `CapabilityReadiness.available()`不等于真实 provider/network/world health。

### 4.2 Tick

onServerTick是 explicit callback：

- disabled → rejected；
- enabled → bridge.tick → registry.tickAttached；
- 仅 attached runtime收到 `runtime.tick()`；
- manager不创建 server tick hook、executor、manager task或 provider refresh；
- current Earth adapter的 tick仍是 no-op marker，TICKED不等于真实 manager/world update。

## 5. Save/reload与Common hooks未接入

manager不接入：

- ChunkAccess/region/world save；
- StorageTransactionCoordinator或AtomicStorageBackend；
- reload/idempotency transaction；
- TellusCommon initializer/manager；
- Fabric/loader server started/tick/world/chunk callbacks；
- generator constructor/codec decode；
- network payload、renderer、DH/Voxy。

`onServerStopping`只做 runtime detach + registry cleanup，不执行 save/reload。真实 storage生命周期仍必须由独立 coordinator/backend contract负责。

## 6. Status与rollback

### 6.1 Manager results

manager复用 bridge status：

- DISABLED、NOT_FOUND、AMBIGUOUS、DUPLICATE、NOT_READY；
- ATTACHED、ALREADY_ATTACHED、TICKED；
- UNREGISTERED、UNREGISTERED_ALL。

结果携带 runtime/readiness/diagnostic；失败不会自动创建第二 adapter或fallback到另一个 registry。

### 6.2 Rollback points

1. **Feature flag rollback**：enabled切回false，所有 callback变为 DISABLED；
2. **Readiness rollback**：probe不ready时不 attach，legacy继续；
3. **Stale identity rollback**：exact unregister失败保持 entry，caller修复 identity；
4. **Stopping rollback**：onServerStopping显式detach+clear，避免 stale entries跨 world；
5. **Tick rollback**：停止调用 onServerTick，不影响旧 generator；
6. **Save boundary rollback**：manager不管理 storage，backend/save失败不会改变 lifecycle registry contract；
7. **Parity rollback**：任何 runtime opt-in影响 comparator时禁用 manager/bridge consumer，回到 legacy。

无自动 retry、default lifecycle fallback或隐式 Common hook。

## 7. Task-102 targeted test evidence

Lead 已报告 manager targeted test `BUILD SUCCESSFUL`，并完成 mc1211 compile。现有：

- `src/test/java/com/yucareux/tellus/worldgen/runtime/TellusGeneratorRuntimeLifecycleManagerTest.java`

覆盖：

- default disabled manager拒绝所有 callbacks且registry untouched；
- enabled callback attach/tick/exact unregister；
- stale generator identity返回 NOT_FOUND；
- onServerStopping返回 UNREGISTERED_ALL并清空 registry、detach runtime；
- no TellusCommon/automatic hook side effect。

测试证明 manager callback/registry cleanup contract，不证明真实 Common/server lifecycle、save/reload或 runtime manager tick已接入。本 review不额外启动测试。

## 8. Parity 门槛保持

本次 gated runtime lifecycle manager review不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

Manager targeted tests与mc1211 compile没有 generator output、save/reload或parity replay接入；`TICKED`/`UNREGISTERED`只表示显式 lifecycle state transition。任何未来 manager opt-in若引发 mismatch，立即关闭 feature flag并回滚 legacy。

## 9. 审阅结论

Task-102 manager边界正确：默认disabled、caller-owned registry、explicit callbacks、exact identity unregister、reverse stop cleanup、readiness-gated attach和attached-only tick均有明确结果。它不安装 TellusCommon hook、不接 save/reload、不创建默认 generator/runtime path，legacy/default行为保持。Lead manager targeted test与mc1211 compile evidence已通过。

## 10. Explicit rollout evidence gate

Task-120 adds `src/main/java/com/yucareux/tellus/worldgen/runtime/TellusRuntimeRolloutGate.java`, a pure evidence gate with no hooks or side effects.

Enablement requires all conditions:

- caller `explicitFlag == true`;
- columns `ParityEvidence.fresh && passed`;
- biome `ParityEvidence.fresh && passed`;
- `storageReady == true`.

Any missing/stale/failed evidence returns `DISABLED` with diagnostic reasons. A disabled/default flag alone can never enable rollout, even when old parity evidence is marked passing. An enabled decision has no diagnostics; a disabled decision must carry at least one reason.

### 10.1 Evidence statuses and rollback

`ParityEvidence` requires a nonblank evidence ID and separates freshness from pass status. Therefore the locked historical `13,824 / 0` and `7 / 1 bare/BWh` records cannot silently satisfy a fresh rollout gate unless caller explicitly supplies fresh passing evidence IDs. The gate does not run comparators, generate manifests, or infer storage readiness; it only evaluates caller-provided evidence.

Rollout rollback is fail-closed: set the explicit flag false, mark either parity evidence stale/failed, or mark storage not ready. The resulting DISABLED decision blocks enabling without detaching or mutating legacy state.

### 10.2 Targeted evidence

Lead reported task-120 rollout-gate targeted test `BUILD SUCCESSFUL` and mc1211 compile. `TellusRuntimeRolloutGateTest` covers default flag disabled, stale columns, failed biome evidence, storage-not-ready diagnostics, and the only-enabled case where both fresh parity records pass and storage is ready.

The gate is not a TellusCommon hook, runtime registry, save/reload implementation or comparator. It is a pure precondition check for a future caller-owned rollout.

## 11. Final lifecycle boundary

Task-102 manager remains default-disabled and explicit-callback-only. Task-120 adds a separate evidence gate; it does not enable the manager by default or install lifecycle hooks. Legacy generator/runtime/save paths remain authoritative until a caller supplies explicit flag, fresh passing column/biome evidence and storage readiness.

Parity baseline remains unchanged:

- Cube/Sphere canonical columns: `13,824`, `0 mismatch`;
- Earth/Cube biome: `7 cases`, `1 mismatch`, known `bare/BWh`.

## 12. Rollout Result → lifecycle manager integration

Task-122 connects the rollout gate result to `TellusGeneratorRuntimeLifecycleManager` through explicit constructors/factory methods:

- `TellusGeneratorRuntimeLifecycleManager(registry, TellusRuntimeRolloutGate.Result)`;
- the same constructor with a caller-owned readiness probe;
- `TellusGeneratorRuntimeLifecycleManager.fromRolloutGate(registry, result)`.

Only `Result.enabled() == true` sets manager `enabled`; a DISABLED result keeps `AttachPolicy.DISABLED`, preserves registry entries, and returns `BridgeResult.Status.DISABLED` for explicit callbacks. Gate diagnostics are retained in the manager diagnostic path, so stale/failed parity or storage evidence is visible at rejection time.

### 12.1 Backward-compatible constructors

The existing constructors remain intact:

- registry-only constructor defaults disabled;
- `(registry, boolean enabled)` remains available for legacy explicit callers;
- `(registry, boolean, readinessProbe)` remains available.

The Result-based overload is additive and does not reinterpret old boolean callers as rollout-approved. A caller must evaluate `TellusRuntimeRolloutGate` and pass its Result explicitly to obtain rollout-enabled lifecycle behavior.

### 12.2 Targeted evidence and no default hooks

Lead reported task-122 rollout Result→manager targeted test `BUILD SUCCESSFUL` and mc1211 compile. `TellusGeneratorRuntimeLifecycleManagerTest` covers:

- disabled gate Result cannot attach and leaves registry/runtime untouched;
- fresh passing columns/biome plus storage-ready and explicit flag produce enabled manager;
- enabled manager still acts only through explicit register/tick/unregister callbacks;
- legacy/default manager behavior remains disabled.

This integration does not install TellusCommon/server hooks, call save/reload, create a runtime registry, run comparators or infer fresh evidence. It only maps an already evaluated Result to the existing lifecycle bridge policy.

### 12.3 Rollback and parity

Rollback is Result-level and fail-closed: pass a DISABLED Result (or stop constructing the Result-based manager) to block attach/tick/cleanup callbacks without mutating legacy registry state. The manager does not downgrade to legacy runtime automatically, retry stale evidence or synthesize parity passes.

Locked baselines remain:

- Cube/Sphere canonical columns: `13,824`, `0 mismatch`;
- Earth/Cube biome: `7 cases`, `1 mismatch`, known `bare/BWh`.
