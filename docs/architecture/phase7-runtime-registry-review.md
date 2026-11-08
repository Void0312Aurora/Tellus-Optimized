# Tellus Phase 7 Runtime Registry/Factory 审阅

> 审阅对象：task-61 revision 3、task-62 fixed test revision、task-64 revision 3、task-75 status propagation regression fix 与 task-80 mc1201 descriptor order contract test。
>
> 审阅任务：task-63 / task-66 / task-76 / task-81 follow-up。
>
> 结论：**Phase 7 提供了 ordered、identity-based 的 runtime registry/factory boundary：factory 按声明顺序探测，只有一个 exact-generator-owner adapter 才能创建；registry 按 exact identity 注册/解析，attach/detach 显式调用，detachAll 逆注册顺序执行。当前没有 loader lifecycle、server tick、generator constructor 或默认 adapter 创建；legacy generator/runtime 仍是行为权威，registry 本身不会改变生成路径。**

## 1. 实现范围与验证

### 1.1 Runtime registry/factory 类型

- `src/main/java/com/yucareux/tellus/worldgen/runtime/TellusGeneratorRuntimeRegistry.java`
- `src/main/java/com/yucareux/tellus/worldgen/runtime/TellusGeneratorRuntimeFactory.java`
- `src/main/java/com/yucareux/tellus/worldgen/runtime/TellusGeneratorRuntimeDescriptor.java`
- `src/main/java/com/yucareux/tellus/worldgen/runtime/TellusEarthRuntimeFactories.java`
- 相关 contract：
  - `TellusGeneratorRuntime`
  - `TellusGeneratorRuntimeView`
  - `EarthGeneratorRuntimeAdapter`
  - `EarthGeneratorSettingsViewAdapter`

`TellusGeneratorRuntimeRegistry` 提供：

- `register(generator, runtime)`；
- exact identity `resolve(generator)`；
- `attach(generator)` / `detach(generator)`；
- reverse-order `detachAll()`；
- immutable ordered adapter projection；
- `RegistrationStatus`、`ResolutionStatus`、`AttachResolution` diagnostics。

`TellusGeneratorRuntimeFactory` 提供：

- declaration-order `AdapterFactory` probing；
- `create(generator, factories)`；
- `createAndRegister(registry, generator, factories)`；
- NOT_FOUND、AMBIGUOUS、INVALID_OWNERSHIP、CREATED 状态。

`TellusGeneratorRuntimeDescriptor` additionally binds:

- stable descriptor `id` and typed `Kind`;
- `sourceLayer` provenance (`root` for Earth descriptor);
- adapter factory returning `Optional<TellusGeneratorRuntime>`;
- typed settings factory returning `Optional<TellusGeneratorSettingsView>`.

`TellusEarthRuntimeFactories` exposes a stable `tellus:earth` descriptor, ordered descriptor list, adapter factory projection, and explicit Earth runtime/settings creation. It does not register the descriptor globally or invoke lifecycle hooks.

- `src/test/java/com/yucareux/tellus/worldgen/runtime/TellusGeneratorRuntimeDescriptorTest.java`
- `shared/mc1201/src/main/java/com/yucareux/tellus/worldgen/runtime/TellusMinecraft1201RuntimeFactories.java`
- `shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/runtime/TellusMinecraft1211RuntimeFactories.java`
- `shared/mc1201/src/test/java/com/yucareux/tellus/worldgen/runtime/TellusGeneratorRuntimeRegistryTest.java`
- `shared/mc1201/src/test/java/com/yucareux/tellus/worldgen/runtime/TellusMinecraft1201RuntimeDescriptorOrderTest.java`

### 1.2 编译与 targeted test 验证

```powershell
./gradlew.bat -PtellusTargets=mc1201 :mc1201:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1211 :mc1211:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1201 :mc1201:test --tests "com.yucareux.tellus.worldgen.runtime.TellusGeneratorRuntimeDescriptorTest" --offline --no-daemon --console=plain
```

结果：mc1201 与 mc1211 `compileJava` 均 `BUILD SUCCESSFUL`（mc1201 `UP-TO-DATE`，mc1211执行成功）。task-62 fixed targeted runtime registry/factory test由 Lead 串行 rerun 并 `BUILD SUCCESSFUL`；task-65 修正后的 descriptor targeted test由 Lead 串行 cleanTest rerun 并 `BUILD SUCCESSFUL`；task-75 registry/factory regression test已由 Lead提供通过证据；task-80 `TellusMinecraft1201RuntimeDescriptorOrderTest` targeted test已由 Lead串行执行并 `BUILD SUCCESSFUL`，验证三项 descriptor/factory顺序、id/kind/sourceLayer metadata、非空 factory与空 registry side-effect。task-64/75/80 additions保留 declaration-order、exact ownership、typed settings和source-layer metadata。mc1211为回归编译目标，没有引入 mc1201 runtime implementation。

这些是纯/isolated registry evidence；不证明 loader lifecycle、server hooks、world save/reload、generator registration或 parity output 已接入。
## 2. Registration order 与 factory resolution

### 2.1 Factory declaration order

`TellusGeneratorRuntimeFactory.create` 依次执行传入 `factories`：

1. 每个 factory 必须非空；
2. factory result 必须非空 Optional；
3. candidate runtime必须 `owns(generator)`；
4. 任意 candidate不拥有 exact generator时立即返回 `INVALID_OWNERSHIP`；
5. 0个 owner → `NOT_FOUND`；
6. 超过1个 owner → `AMBIGUOUS`；
7. 恰好1个 owner → `CREATED`。

因此 factory list 的声明顺序是稳定探测顺序，但顺序不会把多个 owner claim 静默裁剪为 first-wins；多个 identity owner 保持 unresolved，避免 runtime adapter竞争。

### 2.2 Registry ordered registration

`TellusGeneratorRuntimeRegistry.register`：

- 先要求 runtime owns 同一个 generator instance；
- 同一 generator identity重复注册返回 `DUPLICATE_IDENTITY`；
- 成功后按注册顺序追加 entry；
- `orderedAdapters()`返回不可修改的注册顺序投影。

registry不按 class、generator id或 equals替代 identity ownership；exact object identity是 registration authority。这与既有 task-8 strict ownership和 runtime adapter `candidate == this.generator`一致。

### 2.3 Descriptor order and typed settings

Descriptors are the metadata layer above the adapter factory list:

- descriptor list order is explicit and must be preserved when projected to `orderedAdapterFactories()`;
- descriptor IDs/kinds/source layers are immutable and validated nonblank;
- adapter creation and settings creation each return typed `Optional` values, so an unsupported generator remains NOT_FOUND instead of receiving a class-only fallback;
- Earth's `tellus:earth` descriptor uses `Kind.EARTH`, `sourceLayer="root"`, exact `EarthChunkGenerator` matching, `EarthGeneratorRuntimeAdapter`, and `EarthGeneratorSettingsViewAdapter`;
- settings are obtained through the descriptor's typed `SettingsFactory`, not by runtime lifecycle side effect;
- descriptor metadata does not itself register, attach, or start an adapter.


`createAndRegister` 先完成 factory resolution，再将非-created状态映射成 registry registration status：

- NOT_FOUND → `FACTORY_NOT_FOUND`；
- AMBIGUOUS → `FACTORY_AMBIGUOUS`；
- INVALID_OWNERSHIP → `INVALID_OWNERSHIP`；
- CREATED才调用 `registry.register`。

### 2.4 Status propagation regression

Task-75 regression evidence keeps status at each explicit boundary instead of collapsing failures into a successful adapter:

- generic `TellusGeneratorRuntimeFactory.create` preserves `CREATED`, `NOT_FOUND`, `AMBIGUOUS` and `INVALID_OWNERSHIP`, including candidate count for ambiguity;
- `createAndRegister` maps factory failures to `FACTORY_NOT_FOUND`, `FACTORY_AMBIGUOUS` and `INVALID_OWNERSHIP`, and only invokes registry registration for `CREATED`;
- registry assertions preserve `REGISTERED`, `DUPLICATE_IDENTITY`, `INVALID_OWNERSHIP`, `FOUND` and `NOT_FOUND`, with attach results `ATTACHED`/`ALREADY_ATTACHED`;
- target-specific `TellusMinecraft1201RuntimeFactories`/`TellusMinecraft1211RuntimeFactories` convenience `create`/`settings` methods return typed `Optional` projections, while status-bearing resolution remains available through the generic factory/registry boundary;
- failed factory resolution does not attach, register, start lifecycle, or mutate legacy generator behavior.

This regression evidence guards status propagation and side-effect ordering; it does not claim Common/server lifecycle registration or real runtime manager work.


### 2.5 Target descriptor order

Both target-specific descriptor providers expose the same ordered family shape:

1. `tellus:earth` with `sourceLayer="root"`;
2. `tellus:cube_net` with target source layer (`mc1201` or `mc1211`);
3. `tellus:sphere_face` with target source layer (`mc1201` or `mc1211`).

Each descriptor uses exact typed generator matching and a paired runtime/settings adapter factory. Declaration alone remains inert: it creates no registry, does not attach an adapter, and does not install a loader/server callback.


### 2.6 mc1201 order metadata regression

`TellusMinecraft1201RuntimeDescriptorOrderTest` asserts the target descriptor contract without constructing or registering a runtime:

- exactly three descriptors and three projected adapter factories;
- order is `tellus:earth`, `tellus:cube_net`, `tellus:sphere_face`;
- typed kinds are `EARTH`, `CUBE_NET`, `SPHERE_FACE`;
- source layers are `root`, `mc1201`, `mc1201`;
- every descriptor ID is nonblank and both adapter/settings factories are non-null;
- every projected adapter factory is non-null;
- a fresh registry remains size `0` before and after descriptor lookup.

This is metadata-order evidence, not lifecycle registration evidence: descriptor access and factory projection remain pure and do not attach, tick, detach, or install Common/server hooks. The same ordered family shape is expected from the mc1211 provider with target layer `mc1211`.

## 3. Resolve/attach/detach lifecycle boundary

### 3.1 Exact identity resolution

`resolve(generator)`先查 exact registered identity：

- 1个 exact entry且 runtime仍 owns → `FOUND`；
- 多个 exact entry → `DUPLICATE`；
- registered runtime失去 ownership → `NOT_FOUND`；
- 没有 exact entry，再做 ownership probing仅用于诊断；
- 一个 unregistered runtime claim不等于 registration，仍返回 `NOT_FOUND`；
- 多个 ownership claims返回 `AMBIGUOUS`。

这避免仅凭 class matching或 broad adapter claim自动接管 generator。

### 3.2 Attach contract

`attach(generator)`只对 `FOUND` resolution调用 runtime.attach：

- unresolved runtime没有 attach result；
- exact owned generator首次 attach → `ATTACHED`；
- 重复 attach → runtime自有 `ALREADY_ATTACHED`；
- wrong identity → `NOT_OWNED`或 unresolved；
- registry不创建 manager tick、chunk task、source provider或 world hook。

`TellusGeneratorRuntime`的 attach只是 phase-1 lifecycle marker；现有 `EarthGeneratorRuntimeAdapter`仅保存 attached boolean，未接管 TellusCommon lifecycle authority。

### 3.3 Detach contract

- `detach(generator)`先 resolve，只有 FOUND才调用 runtime.detach；
- `detachAll()`按注册逆序 detach，适合未来依赖顺序释放；
- detach不会自动清除 registry entry，也不会启动/停止外部 lifecycle；
- registry当前没有 unregister/remove API，若需要重复 world reload或多次注册，必须先定义 entry清理与 stale identity policy。

这是当前重要的 runtime blocker：detach后 entry仍存在，runtime可再次被 resolve；后续 reload/shutdown adapter必须明确 registry ownership和生命周期重建语义。

## 4. Legacy 与 lifecycle 未接入

### 4.1 Legacy 默认行为

当前 registry/factory未被以下路径调用：

- `EarthChunkGenerator`、`CubeNetChunkGenerator`、`SphereFaceChunkGenerator` constructor；
- loader initializer、server started/tick/chunk unload/player callbacks；
- `TellusCommon` runtime manager、source/provider registry、preload registry；
- world save/reload、dimension lifecycle、runtime payload或transition hook；
- FeaturePlanner/Writer/Phase 6 pipeline自动发现；
- Sodium/DH/Voxy/client renderer。

因此：

- 现有 generator 默认不创建 runtime adapter；
- legacy generator/source/runtime 仍是行为权威；
- `TellusGeneratorRuntimeFactory.registry()`只是返回空 registry，不注册全局 singleton；
- factory/registry只有显式调用才产生 registration/attach；
- 删除 registry/factory不会改变旧生成或旧 lifecycle行为。

### 4.2 No default adapter

Registry Javadoc明确“不创建 default adapter”；task-64 descriptor/factory不会按 generator class自动实例化 runtime。即便 `TellusEarthRuntimeFactories.descriptors()`提供 Earth descriptor，调用者仍需显式取得 descriptor/factory并调用 `create`、`createAndRegister` 或 `register`；typed settings view也不会自动注入现有 generator。

这保证 Phase 7 只建立 resolution boundary，不提前改变默认 generator行为。

## 5. Rollback 与异常边界

### 5.1 Registration rollback

1. factory返回 NOT_FOUND/AMBIGUOUS/INVALID_OWNERSHIP：不注册、不 attach；
2. duplicate exact identity：保留首个 registration，拒绝新 runtime；
3. attach返回 NOT_OWNED：保留 registration但不启动 adapter state；
4. attach抛异常：由调用方处理，registry不自动 fallback或创建第二 runtime；
5. detachAll逆序执行，后续 manager/resource release由外部 lifecycle负责；
6. runtime stale/owner mismatch：resolve返回 NOT_FOUND，禁止通过 class匹配继续运行。

### 5.2 Legacy rollback

runtime adapter出现问题时：

- 停止显式 registry consumer；
- 不注册/不调用 runtime adapter；
- 继续使用旧 generator/source/runtime；
- 不删除旧 save/cache/payload；
- 不自动把 unresolved runtime route到新的 generator path。

若未来 runtime接入 Phase 6 pipeline，rollback顺序应为：先 detach/disable new adapter，再关闭 pipeline opt-in，最后恢复 legacy generator；禁止在同一 generator identity上双重 attach或双 writer。

### 5.3 Registry cleanup blocker

当前没有显式 unregister，因此 world unload/reload、generator replacement、test fixture teardown必须由调用方隔离 registry instance；不能把一个 registry跨 world复用，除非后续增加 remove/stale cleanup contract。这个缺口在 lifecycle接入前必须解决。

## 6. 最小测试矩阵与 parity 门槛

不扩大测试面，新增纯 registry/factory tests并复用既有 runtime ownership fixtures：

| 类别 | 测试/证据 | 门槛 |
|---|---|---|
| order metadata | task-80 `TellusMinecraft1201RuntimeDescriptorOrderTest` | Earth→Cube-Net→Sphere-Face ids/kinds/sourceLayer exact；lookup leaves registry size 0 |
| status propagation | task-75 `TellusGeneratorRuntimeRegistryTest` | factory/registry statuses preserved；failure不attach、不register、不启动lifecycle |
| descriptor | task-64/65 descriptor fixtures | descriptor order/id/kind/sourceLayer稳定；typed settings unsupported时为空 |
| factory order | ordered factory doubles | declaration order稳定；0/1/multiple owner结果明确 |
| ownership | Earth/runtime exact identity | wrong instance为 INVALID_OWNERSHIP；class match不接管 |
| registration | registry fixtures | duplicate identity拒绝；ordered adapters不可变 |
| resolution | identity/claim doubles | FOUND/NOT_FOUND/DUPLICATE/AMBIGUOUS语义稳定 |
| attach | runtime marker doubles | ATTACHED/ALREADY_ATTACHED/NOT_OWNED明确 |
| detach | reverse registration fixtures | detachAll逆序；无外部 lifecycle启动 |
| fallback | legacy fixture | factory/attach失败不改变legacy generator |
| cleanup | isolated registry instances | world reload不复用stale registration |
| regression | mc1201/mc1211 compile | target边界无泄漏 |
| parity | Cube/Sphere `13,824 / 0 mismatch` | registry未接入generator时无变化；未来opt-in双路径0 mismatch |
| biome | Earth/Cube `7 cases / 1 mismatch bare/BWh` | 不新增mismatch，不在runtime registry修复bare/BWh |

### 6.1 接入顺序

1. **已完成（task-62）**：纯 factory/registry ownership/resolution tests；
2. **已完成（task-62）**：attach/detach marker与 reverse-order detach tests，不启动真实 lifecycle；
3. **已完成（task-64/65）**：descriptor order、exact typed adapter/settings/source-layer fixtures；task-65 cleanTest targeted rerun通过；
4. **已完成（task-75）**：factory/registry status propagation regression与 no-lifecycle-side-effect assertions；
5. **已完成（task-80）**：mc1201 descriptor order/id/kind/sourceLayer metadata test，lookup保持空 registry；
6. registry cleanup/stale identity tests；
7. 与 Phase 6 pipeline的显式 consumer contract对照；
8. 单一 generator family runtime opt-in，legacy默认；
9. 对照同一 parity comparator；
10. 所有门槛稳定后才允许扩大 runtime/lifecycle覆盖。

## 7. Parity 门槛保持

本次 Phase 7 runtime registry/factory审阅不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

当前 registry/factory没有注册generator、没有启动lifecycle、没有触发pipeline或真实生成输出，因此没有新的 parity结果。任何未来 runtime opt-in必须与 legacy generator使用同一 comparator；新增 mismatch即关闭该 adapter registration并回到 legacy。不能以 registration order、ambiguous fallback或 attach marker解释为 parity通过。

## 8. 审阅结论

Task-61/62/64/65/75/80 runtime registry/factory contract方向正确：它用 exact generator identity作为唯一 ownership authority，用 ordered factory/descriptor probing避免隐式 first-wins，用 immutable descriptor id/kind/sourceLayer与typed settings factory保持版本边界，用 explicit attach/detach和reverse detachAll保持生命周期边界；NOT_FOUND、AMBIGUOUS、DUPLICATE、INVALID_OWNERSHIP及其 createAndRegister映射均显式保留。task-62 fixed targeted test已验证 factory order、identity ownership、resolution状态、attach/detach marker与reverse detach；task-65 cleanTest targeted test已验证 Earth descriptor metadata与typed creation；task-75 regression evidence已验证 status propagation及失败不attach、不register、不启动lifecycle；task-80 targeted test已验证 mc1201 descriptor顺序严格为 Earth→Cube-Net→Sphere-Face、ids/kinds/sourceLayer metadata准确，descriptor/factory lookup前后 registry保持 size 0。当前没有 generator/lifecycle注册，legacy/default行为与rollback语义未改变。

Task-81 review已完成。下一步只需定义 unregister/stale identity语义，再将一个 runtime adapter与 Phase 6 pipeline在显式 opt-in下串联；继续保持 `13,824 / 0`、`7 / 1 bare/BWh` parity门槛。
