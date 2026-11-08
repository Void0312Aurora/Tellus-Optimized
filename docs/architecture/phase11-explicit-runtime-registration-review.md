# Tellus Phase 11 Explicit Runtime Registration 审阅

> 审阅对象：task-85 mc1201 与 task-87 mc1211 explicit registration helpers。
>
> 审阅任务：task-86 / task-88 follow-up。
>
> 结论：**Phase 11 增加了一个 caller-owned、explicit-only 的 mc1201 registration facade。调用者必须提供 registry 与 generator；helper只委托给 ordered mc1201 adapter factories / `createAndRegister`，返回既有 `RegistrationResult`，不 attach、不 tick、不启动 lifecycle、不创建默认 registry。失败状态与 exact identity/ownership 规则原样传播，legacy generator/default behavior、rollback和parity均保持不变。**

## 1. 实现范围

### 1.1 Explicit registration helper

- `shared/mc1201/src/main/java/com/yucareux/tellus/worldgen/runtime/TellusMinecraft1201RuntimeRegistration.java`
- `shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/runtime/TellusMinecraft1211RuntimeRegistration.java`
- `shared/mc1211/src/test/java/com/yucareux/tellus/worldgen/runtime/TellusMinecraft1211RuntimeRegistrationTest.java`
- 依赖：
  - `TellusMinecraft1201RuntimeFactories.orderedAdapterFactories()`；
  - `TellusGeneratorRuntimeFactory.createAndRegister(...)`；
  - caller-owned `TellusGeneratorRuntimeRegistry`。

公开两个 overload：

```java
register(registry, generator)
register(registry, generator, orderedFactories)
```

无 registry factory、singleton、static shared state或 lifecycle callback。

### 1.2 Caller-owned registry

helper不执行 `new TellusGeneratorRuntimeRegistry()`：

- registry生命周期由调用者决定；
- registry可按 world/test/consumer scope隔离；
- registration timing由调用者决定；
- helper不保存 registry引用；
- helper返回 registration result，不返回 bridge或attached runtime handle。

这使 registration boundary与 Phase 8 lifecycle bridge分离：注册成功不等于 attach成功，也不等于 runtime已开始 tick。

## 2. Explicit-only semantics

### 2.1 调用前置

调用者必须显式提供：

- 非空 `TellusGeneratorRuntimeRegistry`；
- 非空 `ChunkGenerator`；
- 可选但非空的 ordered factory list。

任一 required input为 null立即失败。无 generator constructor、codec decode、Common initializer、server callback或 automatic discovery路径调用该 helper。

### 2.2 Default overload

`register(registry, generator)`只从 `TellusMinecraft1201RuntimeFactories.orderedAdapterFactories()`取得声明顺序：

1. `tellus:earth`；
2. `tellus:cube_net`；
3. `tellus:sphere_face`。

然后严格委托 `TellusGeneratorRuntimeFactory.createAndRegister`。它不会自动 attach，也不会为 caller创建新的 registry。

### 2.3 Custom ordered factories

第二个 overload允许 caller提供 ordered factories，用于：

- test doubles；
- 受控 adapter family；
- 未来显式版本/feature flag组合。

顺序和 ownership仍由 generic factory authority判定：exact generator owner必须唯一；多个 owner不采用 first-wins。

### 2.3 mc1211 explicit boundary

`TellusMinecraft1211RuntimeRegistration` mirrors the mc1201 facade exactly:

- caller supplies registry and generator;
- default overload uses `TellusMinecraft1211RuntimeFactories.orderedAdapterFactories()`;
- custom overload accepts caller-owned ordered factories;
- delegates to the same generic `createAndRegister` status authority;
- never attaches, ticks, detaches, creates a registry, or installs lifecycle hooks.

Its descriptor order is `tellus:earth`, `tellus:cube_net`, `tellus:sphere_face`, with mc1211 target source layers for target-specific descriptors. This gives mc1201/mc1211 explicit registration parity without sharing target-version Minecraft APIs.


helper返回 `TellusGeneratorRuntimeRegistry.RegistrationResult`，不会把失败压平为 Optional.empty或成功：

- `REGISTERED`：恰有一个 factory candidate exact-owns generator，且 registry接受 registration；
- `DUPLICATE_IDENTITY`：registry已有同一个 generator identity；保留已有 entry；
- `INVALID_OWNERSHIP`：runtime claim与传入 generator不匹配；不注册；
- `FACTORY_NOT_FOUND`：没有 factory匹配；不注册；
- `FACTORY_AMBIGUOUS`：多个 ordered factories claim同一 exact generator；不注册。

`createAndRegister`内部先执行 factory resolution，再仅在 `CREATED`时调用 registry.register。因此：

- factory failure不会 attach；
- factory failure不会 tick；
- factory failure不会写入 registry；
- ambiguous/invalid candidate不会自动选择第一个；
- duplicate不会替换原 runtime。

## 4. No attach/lifecycle/default behavior

### 4.1 No attach

`TellusMinecraft1201RuntimeRegistration.register`没有调用：

- `registry.attach`；
- `TellusGeneratorRuntimeLifecycleBridge.attach`；
- `runtime.tick`；
- `registry.tickAttached`；
- `runtime.detach` / `detachAll`。

即使返回 `REGISTERED`，runtime仍未 attached；attach必须是后续另一个 explicit caller operation。

### 4.2 No lifecycle

helper未接入：

- TellusCommon initializer/manager；
- Fabric/loader server started/tick/world/chunk callbacks；
- generator constructor、codec或 world-load path；
- network payload、provider refresh、pipeline writer、save/reload；
- client renderer、DH/Voxy/Sodium/Iris。

它只建立 registry entry，不接管 lifecycle authority。

### 4.3 No default

- 不创建默认 registry；
- 不自动注册 Earth/Cube-Net/Sphere-Face generator；
- 不改旧 generator/source/runtime；
- 不启用 Phase 6 pipeline或Phase 8 bridge；
- 没有调用者就没有 registration。

## 5. Exact identity与legacy boundary

mc1201 helper复用 generic exact identity contract：

- `runtime.owns(generator)`必须为 true；
- adapter不是按 class-only匹配接管；
- generator identity由 registry保存为唯一 registration authority；
- CUBE-Net/Sphere/Earth descriptor仍按 mc1201 typed adapter/settings factory区分；
- registration成功也不改变旧 generator的 generation path。

legacy Earth/Cube-Net/Sphere-Face generator/source/runtime继续是默认行为权威。helper删除或禁用不会改变旧 save/cache/payload/lifecycle。

## 6. Rollback points

1. **No-call rollback**：停止调用 helper，registry保持调用者控制，legacy继续。
2. **Factory rollback**：factory NOT_FOUND/AMBIGUOUS/INVALID_OWNERSHIP时不注册、不attach，caller可保留 legacy。
3. **Duplicate rollback**：DUPLICATE_IDENTITY保留原 entry，不覆盖、不创建第二 runtime。
4. **Registry rollback**：丢弃/隔离 caller-owned registry instance；避免 stale identity跨 world复用。
5. **Lifecycle rollback**：registration与attach分离；若后续 bridge失败，只detach/disable bridge，不必修改 legacy generator。
6. **Parity rollback**：任何 runtime opt-in造成 parity变化，停止显式 registration consumer，输出 registration/runtime diff并回到 legacy。

helper不实现自动 retry/fallback，避免 registration失败后产生隐式双 adapter或双 writer。

## 7. 测试/证据边界

Task-85 mc1201 helper evidence与 task-87 mc1211 evidence均由 Lead串行提供：mc1201 descriptor/lifecycle targeted tests在 cross-target fixture adjustment后 `BUILD SUCCESSFUL`，mc1211 compile + targeted registration test也 `BUILD SUCCESSFUL`。`TellusMinecraft1211RuntimeRegistrationTest`覆盖：

- descriptor/factory order从 Earth开始并保持 explicit；
- Earth runtime注册到 caller-owned registry；
- unsupported empty factory list返回 `FACTORY_NOT_FOUND`且 registry size保持 0；
- duplicate exact identity返回 `DUPLICATE_IDENTITY`且不增加 entry；
- generic factory `NOT_FOUND`保持无 runtime result。

mc1201 descriptor/lifecycle serial evidence补强了跨目标 fixture下的 order metadata、explicit lifecycle boundary与无自动 hooks结论；mc1201与mc1211两侧均验证同一 explicit-only、caller-owned、status-preserving boundary。测试不证明 Common/server lifecycle、runtime manager tick、world save/reload或generator output parity已接入。

## 8. Parity 门槛保持

本次 explicit mc1201/mc1211 registration review不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

两侧 registration helper都没有调用 generator、pipeline writer、lifecycle bridge或parity output，因此没有新的 parity结果。`REGISTERED`只表示 caller-owned registry接纳一个 exact-owner runtime，不表示世界生成行为已切换。mc1201/mc1211 explicit boundary保持对称；任何未来 opt-in若产生 mismatch，立即停止 registration consumer并回退 legacy。

## 9. 审阅结论

Task-85/87 registration helper边界正确：mc1201与mc1211均由 caller拥有 registry和调用时机，helper只使用各自 ordered target factories并严格委托 generic `createAndRegister`，完整传播 registration/factory错误状态；成功registration不自动 attach、不 tick、不接 Common/lifecycle、不创建默认路径。task-87 targeted compile/test `BUILD SUCCESSFUL`进一步证明 mc1211 的 Earth/unsupported/duplicate/status boundary与 mc1201 对称。legacy/default、rollback与parity均保持不变。

Task-88 review完成；后续若将任一 target registration与 Phase 8 bridge或 Phase 10 plan pipeline串联，必须继续保持显式调用、exact identity、status-preserving results、可关闭开关及同一 `13,824 / 0`、`7 / 1 bare/BWh` comparator。
