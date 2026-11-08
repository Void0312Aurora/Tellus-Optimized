# Tellus Phase 8 Runtime Lifecycle Bridge 审阅

> 审阅对象：task-67 revision 3 与当前 `TellusGeneratorRuntimeLifecycleBridgeTest` 的 `TellusGeneratorRuntimeLifecycleBridge` / explicit lifecycle policy-readiness contract。
>
> 审阅任务：task-69。
>
> 结论：**Phase 8 提供了一个显式 opt-in 的 lifecycle bridge：调用方选择 `AttachPolicy` 后，bridge 才能 attach、tick、detach 或 detachAll；构造 bridge 本身不执行任何 lifecycle action。`REQUIRE_CAPABILITY_READY` 会在 attach 前调用外部 readiness probe，未 ready 时不 attach；`DISABLED` 拒绝所有操作。当前没有接入 TellusCommon、loader、server callback、generator constructor 或自动 tick，legacy runtime/generator 仍是默认行为。**

## 1. 实现范围与验证

### 1.1 Lifecycle 类型

- `src/main/java/com/yucareux/tellus/worldgen/runtime/TellusGeneratorRuntimeLifecycleBridge.java`
- `src/main/java/com/yucareux/tellus/worldgen/runtime/AttachPolicy.java`
- `src/main/java/com/yucareux/tellus/worldgen/runtime/LifecycleEvent.java`
- `src/main/java/com/yucareux/tellus/worldgen/runtime/BridgeResult.java`
- `src/main/java/com/yucareux/tellus/worldgen/runtime/CapabilityReadiness.java`
- updated runtime contracts:
  - `TellusGeneratorRuntime.tick()` explicit hook;
  - `TellusGeneratorRuntimeRegistry.tickAttached()` dispatches only attached adapters.
- `src/test/java/com/yucareux/tellus/worldgen/runtime/TellusGeneratorRuntimeLifecycleBridgeTest.java`

### 1.2 Bridge boundary

`TellusGeneratorRuntimeLifecycleBridge`保存：

- existing `TellusGeneratorRuntimeRegistry`；
- `AttachPolicy`；
- caller-provided `ReadinessProbe`。

它提供：

- `attach(generator)`；
- `tick()`；
- `detach(generator)`；
- `detachAll()`。

所有操作返回 immutable `BridgeResult`，包含 status、lifecycle event、optional runtime、readiness snapshot 和 diagnostic。bridge不持有 world/server singleton、不执行 I/O、不创建默认 runtime。

### 1.3 验证证据

本轮只读审阅不启动 Gradle；编译与测试由 Lead 统一串行执行。当前 `TellusGeneratorRuntimeLifecycleBridgeTest` 覆盖：

- DISABLED bridge 对 attach/tick/detach/detachAll 的 no-op/rejection；
- REQUIRE_CAPABILITY_READY 的 not-ready no-attach；
- ready attach、重复 attach 的 ALREADY_ATTACHED；
- explicit tick、detach、重复 detach、detachAll；
- registry NOT_FOUND resolution forwarding；
- EXPLICIT_ONLY 忽略 readiness probe并允许显式 attach。

Lead 已提供 task-67 bridge test evidence；本 review 不重新运行测试或生成新的 build 结果。
## 2. Opt-in semantics

### 2.1 Construction is inert

构造 `TellusGeneratorRuntimeLifecycleBridge`只保存 registry、policy和 readiness probe：

- 不自动 resolve generator；
- 不自动 attach；
- 不自动 tick；
- 不自动 detach；
- 不注册 Common/server hook；
- 不启动 manager、provider、chunk task或网络事件。

这使 bridge成为调用方可控的 adapter，而不是新的全局 lifecycle authority。

### 2.2 AttachPolicy

`AttachPolicy`固定三种语义：

- `DISABLED`：attach/tick/detach/detachAll全部拒绝，返回 `BridgeResult.Status.DISABLED` 和 `LifecycleEvent.REJECTED`；
- `EXPLICIT_ONLY`：显式 attach调用即可继续，readiness probe不作为硬门槛；
- `REQUIRE_CAPABILITY_READY`：显式 attach还必须通过 caller-provided `ReadinessProbe`，否则返回 `NOT_READY`/`LifecycleEvent.NOT_READY`，不调用 registry.attach。

policy不会把 disabled或not-ready自动 fallback到其他 runtime，也不会创建第二 adapter。

### 2.3 Attach result mapping

attach流程为：

1. 校验 generator；
2. `DISABLED`立即拒绝；
3. registry exact identity resolve；
4. NOT_FOUND/AMBIGUOUS/DUPLICATE映射为对应 bridge status，保持 readiness not-ready；
5. REQUIRE_CAPABILITY_READY时执行 readiness probe；
6. readiness不通过则不 attach；
7. ready或EXPLICIT_ONLY时调用 registry.attach；
8. `ATTACHED`/`ALREADY_ATTACHED`/`NOT_OWNED`映射为 bridge result。

因此 bridge不会通过 class matching绕过 registry identity authority。

## 3. Readiness contract

### 3.1 CapabilityReadiness

`CapabilityReadiness`是 immutable snapshot，但不是 capability probing或健康检查本身：

- `ready`布尔值；
- `readyCapabilities` immutable set；
- diagnostic。

`available()`只构造 `ready=true`、空 capability set、`"ready"` diagnostic；它不证明 source/provider/network/world manager已就绪。`notReady()`同样只是 caller/probe提供的结果。实际 readiness由外部 `ReadinessProbe`决定，bridge不读取或修改 runtime settings。
### 3.2 Readiness gate

只有 `REQUIRE_CAPABILITY_READY`使用 probe作为 attach硬门槛：

- probe返回 null是错误；
- not-ready返回 `NOT_READY`，runtime保持未 attach；
- ready继续 registry attach；
- readiness snapshot放入 BridgeResult，便于审计与回滚；
- EXPLICIT_ONLY不假装 capability ready，而是返回 `available()`作为 operation result metadata，后续应明确这不是 provider health证明。

未来 capability分类应由 descriptor/settings view与实际 runtime resource contract统一，不能在 bridge中硬编码 Earth/Cube/Sphere特例。

## 4. Tick、detach 与 lifecycle 未接入

### 4.1 Explicit tick

`tick()`：

- `DISABLED`返回 rejected/disabled；
- enabled policy调用 registry `tickAttached()`；
- registry只对 `runtime.attached()`为 true的 entries调用 `runtime.tick()`；
- 不调用未 attach runtime；
- 返回 TICKED与数量诊断；
- 不绑定 server tick线程、world tick或异步 executor。

`TellusGeneratorRuntime.tick()`默认空实现，当前 `EarthGeneratorRuntimeAdapter`也没有覆写 `tick()` 或连接任何 manager、executor、provider refresh、chunk task或 server state。因此 `registry.tickAttached()`只证明它向 attached runtime调用了 runtime hook并计数；当前测试中的 `TICKED` 不证明真实 manager tick、world tick或 terrain update已经执行。后续 adapter若实现 tick，必须另测异常隔离、线程和 backpressure。
### 4.2 Detach

- `detach(generator)`必须显式调用；
- DISABLED拒绝；
- unresolved generator不调用 runtime.detach；
- FOUND调用 registry.detach并返回 DETACHED；
- `detachAll()`按 registry reverse registration order执行；
- detach不会自动清 registry entries，也不会触发 save/reload、provider shutdown或 world cleanup。

registry仍缺少 unregister/remove；world reload时必须隔离 registry instance或增加 stale identity cleanup contract。

### 4.3 Common hooks absent

当前没有以下调用方：

- TellusCommon initializer/manager；
- Fabric/loader server started、server tick、world load/unload、chunk load/unload回调；
- generator constructor或codec decode path；
- runtime payload/transition、network task；
- SampleProvider/DecisionKernel/FeaturePlanner/Writer自动发现；
- save/reload/edit overlay、DH/Voxy/Sodium/Iris/client renderer。

bridge文件存在和可被显式调用，不等于 Common lifecycle已迁移。

## 5. Legacy default 与 rollback

### 5.1 Legacy behavior

- 默认不创建 bridge或使用 `AttachPolicy.EXPLICIT_ONLY`隐式运行；
- 现有 Earth/Cube-Net/Sphere-Face generator与TellusCommon runtime保持行为权威；
- lifecycle bridge不修改旧 source/cache/payload/save/runtime state；
- disabled/not-ready/unresolved operation不自动改写 legacy generator；
- no bridge tick means no new runtime work。

Phase 8因此只是 lifecycle call boundary，不是生命周期接管。

### 5.2 Rollback points

1. **Policy rollback**：切换 `AttachPolicy.DISABLED`，阻断所有 bridge operations。
2. **Attach rollback**：readiness失败或 registry resolution失败时不 attach；保留 legacy。
3. **Tick rollback**：停止外部调用 bridge.tick，已 attach runtime由显式 detach/detachAll释放。
4. **Runtime rollback**：adapter异常时先 detach，再停止 bridge consumer，不改旧 generator/source。
5. **Registry rollback**：隔离/丢弃当前 registry instance，避免 stale identity跨 world复用；正式 lifecycle前需补 unregister。
6. **Parity rollback**：任何 `13,824` 或 biome case变化立即关闭 bridge/adapter opt-in，回到 legacy并输出 runtime/pipeline diff。

bridge不提供自动 retry或fallback，避免 attach/tick双重执行和跨生命周期残留。

## 6. 首个 lifecycle consumer 前置

在任何真实 Common/server hook之前，必须固定：

- explicit bridge creation和policy配置来源；
- readiness probe输入、线程和超时语义；
- attach与runtime registry exact identity绑定；
- tick频率、线程、异常隔离和 backpressure；
- detach/detachAll与world unload顺序；
- unregister/stale registry cleanup；
- Phase 6 pipeline/storage transaction与runtime attach的边界；
- legacy fallback、双 attach、双 writer和重复 tick防护。

推荐顺序：

1. 纯 bridge policy/readiness/result tests；
2. registry marker double验证 attach/tick/detach顺序；
3. isolated runtime fixture验证 not-ready/no-attach；
4. 与 Phase 6 pipeline显式 consumer对照；
5. 单一 generator family opt-in；
6. 最后才评估一个 Common hook，仍保留可关闭开关和 legacy fallback。

## 7. 最小测试矩阵与 parity 门槛

不扩大测试面，新增纯 lifecycle bridge tests并复用 runtime registry/descriptor fixtures：

| 类别 | 测试/证据 | 门槛 |
|---|---|---|
| disabled | `TellusGeneratorRuntimeLifecycleBridgeTest` | DISABLED拒绝attach/tick/detach/detachAll，不调用registry |
| explicit attach | `TellusGeneratorRuntimeLifecycleBridgeTest` | EXPLICIT_ONLY只在显式调用时attach |
| readiness | `TellusGeneratorRuntimeLifecycleBridgeTest` | REQUIRE_CAPABILITY_READY未ready不attach；ready才attach；probe只是外部snapshot |
| resolution | `TellusGeneratorRuntimeLifecycleBridgeTest` | NOT_FOUND/AMBIGUOUS/DUPLICATE映射稳定 |
| tick | `TellusGeneratorRuntimeLifecycleBridgeTest` | 只tick attached runtime；当前不证明真实manager/server hook |
| detach | `TellusGeneratorRuntimeLifecycleBridgeTest` | detach/detachAll顺序稳定；无自动 cleanup假设 |
| legacy | no-hook fixture | Common/generator默认路径无变化 |
| rollback | policy/runtime failure fixtures | disabled/detach后无重复tick/attach；legacy恢复 |
| regression | mc1201/mc1211 compile | target边界无泄漏 |
| parity | Cube/Sphere `13,824 / 0 mismatch` | bridge未接入Common/generator时无变化；未来opt-in双路径0 mismatch |
| biome | Earth/Cube `7 cases / 1 mismatch bare/BWh` | 不新增mismatch，不在lifecycle bridge修复bare/BWh |

## 8. Parity 门槛保持

本次 Phase 8 lifecycle bridge审阅不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

当前 bridge没有接入 Common、loader、server tick、generator或真实 runtime output，因此没有新的 parity结果。`TICKED`只表示显式分发到 attached runtime，不表示 world generation parity；任何新增 mismatch都是迁移闸门失败，不能以 readiness、attach marker或legacy fallback解释为通过。

## 9. 审阅结论

Task-67 lifecycle bridge方向正确，当前 bridge test evidence已验证：构造 inert、policy显式、readiness probe gate、exact identity resolution、ready attach、重复 attach、explicit tick/detach结果可审计；DISABLED/NOT_READY/NOT_FOUND/AMBIGUOUS/DUPLICATE均不绕过 registry identity。`tickAttached()`当前只调用 attached runtime的 `tick()` hook并返回计数；Earth adapter没有真实 manager tick实现，因此 TICKED不是 manager/world update证据。`CapabilityReadiness.available()`是 caller/probe snapshot，不是 provider/network/world健康证明。

当前没有 Common hooks、loader/server lifecycle、generator注册或自动调用，legacy仍为默认行为，rollback可通过关闭policy/停止consumer/detach完成。下一步应在真实 adapter实现 tick或接入 Common 前补 unregister/stale identity、tick异常隔离、readiness来源与线程语义；继续保持 `13,824 / 0`、`7 / 1 bare/BWh` parity门槛。
