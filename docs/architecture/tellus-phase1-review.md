# Tellus Phase 1 实现审阅

> 审阅对象：task-5 revision 4 的 settings/runtime view 实现，以及 task-8 revision 3 的兼容边界修正。
>
> 审阅日期：2026-11-07。
>
> 结论：**Phase 1 可以作为只读投影与 ownership marker 的增量落地，尚不具备接管生命周期、替换消费者或统一运行时调度的条件。** task-8 已将全部 runtime adapter 的 ownership 收紧为 bound-generator identity，并为 1.20.1/1.21.1 Cube-Net 增加实际 generator/biome-source typed settings factory；当前实现仍没有修改 generator、Codec、存档格式、消费者或既有 runtime hook。进入 coordinate context 之前，需保留这些兼容边界，并补齐 contract tests 与后续 runtime factory/readiness 层。

## 1. 本次审阅范围与证据

### 1.1 实现文件

公共层：

- `src/main/java/com/yucareux/tellus/worldgen/runtime/TellusGeneratorSettingsView.java`
- `src/main/java/com/yucareux/tellus/worldgen/runtime/TellusGeneratorRuntimeView.java`
- `src/main/java/com/yucareux/tellus/worldgen/runtime/TellusGeneratorRuntime.java`
- `src/main/java/com/yucareux/tellus/worldgen/runtime/EarthGeneratorSettingsViewAdapter.java`
- `src/main/java/com/yucareux/tellus/worldgen/runtime/EarthGeneratorRuntimeAdapter.java`

1.20.1 适配层：

- `shared/mc1201/src/main/java/com/yucareux/tellus/worldgen/runtime/CubeNetGeneratorSettingsViewAdapter.java`
- `shared/mc1201/src/main/java/com/yucareux/tellus/worldgen/runtime/CubeNetGeneratorRuntimeAdapter.java`
- `shared/mc1201/src/main/java/com/yucareux/tellus/worldgen/runtime/SphereFaceGeneratorSettingsViewAdapter.java`
- `shared/mc1201/src/main/java/com/yucareux/tellus/worldgen/runtime/SphereFaceGeneratorRuntimeAdapter.java`

1.21.1 适配层：

- `shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/runtime/CubeNetGeneratorSettingsViewAdapter.java`
- `shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/runtime/CubeNetGeneratorRuntimeAdapter.java`
- `shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/runtime/SphereFaceGeneratorSettingsViewAdapter.java`
- `shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/runtime/SphereFaceGeneratorRuntimeAdapter.java`

### 1.2 已执行验证

以下两个命令均成功；Gradle 将任务判定为 `UP-TO-DATE`：

```powershell
./gradlew.bat -PtellusTargets=mc1201 :mc1201:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1211 :mc1211:compileJava --offline --no-daemon --console=plain
```

结果均为 `BUILD SUCCESSFUL`。这证明两目标的 typed API、包边界和当前编译依赖可成立；它不证明 runtime 已接管、adapter 已被消费者使用，也不证明 parity 或客户端行为发生了验证。

### 1.3 task-8 contract correction files

1.20.1 与 1.21.1 各新增：

- `shared/mc1201/src/main/java/com/yucareux/tellus/worldgen/CubeNetGeneratorSettingsViewFactory.java`
- `shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/CubeNetGeneratorSettingsViewFactory.java`

task-8 同时更新了各目标 runtime adapter 的 ownership 判断，并为 Cube-Net settings/runtime adapter 提供单参实际 generator 构造；factory 位于 generator package，可读取 biome source 的 package-private `settings()`，不使用 reflection。

### 1.4 当前未改变的内容

- `EarthGeneratorSettings.CODEC`、`CubeNetBiomeSource.Settings.CODEC`、`CubeNetChunkGenerator.CODEC`、`SphereFaceChunkGenerator.CODEC` 未改动。
- generator 的采样、biome、column、feature、结构和写入路径未改动。
- `TellusCommon`、`TellusRealtimeManager`、DH、Voxy、managed terrain、Cube-Net runtime、Sphere-Face runtime 和 mixin 未改动。
- 未新增 runtime consumer factory、registry 或 lifecycle takeover。
- 未新增测试类；本阶段只有 compile evidence。

## 2. 已覆盖能力

### 2.1 公共 settings view

`TellusGeneratorSettingsView` 已形成只读、不可变投影：

- generator kind、generator ID、geometry ID；
- world scale、effective vertical range、sea level、height offset；
- optional spawn、terrain、structure、random-biome、realtime、DH、Voxy、managed terrain、experimental-height groups；
- capability set；
- `VerticalRange`、`SpawnPoint`、各 options record 对输入做了基本的不可变包装/范围校验。

该接口是 view，不是新 Codec，也没有 setter；因此不会把 runtime command override、DH API override 或 Voxy volatile override 写回 world settings。这一点与当前 settings/override 分层兼容。

### 2.2 Earth adapter

`EarthGeneratorSettingsViewAdapter` 正确复用：

- `EarthChunkGenerator.settings()` 作为配置权威；
- `EarthChunkGenerator` 的实际 sea level/min Y/height，避免仅用 raw settings 推导运行时范围；
- `EarthGeneratorSettings.resolveHeightLimits` 与 `effectiveHeightOffset`；
- Earth 的 terrain、structure、random biome、realtime、DH、Voxy、managed terrain、experimental-height 字段。

Earth 的 `enableWater`、experimental height、DH OSM fixed values 等 effective-value 语义继续由原 record/constructor 提供，view 没有复制一份归一化逻辑。

### 2.3 Cube-Net adapters

1.20.1 与 1.21.1 都有显式 typed Cube-Net settings/runtime adapter，保持了两目标的版本层分离。已覆盖：

- generator 的 mapping/world scale/vertical range/sea level/height offset；
- Cube-Net biome source settings 的洞穴、矿脉、地质石块、道路/建筑、水、湖泊冻结、结构开关和 random-biome；
- `GEODETIC_POSITION` 与 `SEAM_NAVIGATION` capability；
- runtime attach/detach marker，但不接管 `CubeNetRuntime`。

Cube-Net 的 realtime、DH、Voxy、managed terrain、experimental height 等 optional groups 当前返回 `Optional.empty()`，因此不会把 Earth-only 能力误宣称为 Cube-Net 能力；已有 `TellusRealtimeManager` 的 `tellus.cubeNet.*` system-property fallback 仍由旧消费者保持。

### 2.4 Sphere-Face adapters

1.20.1 与 1.21.1 都有显式 typed Sphere-Face settings/runtime adapter，当前只投影：

- mapping geometry ID；
- face generator 的 world scale、sea level、height range；
- 空 spawn/terrain/structure/realtime/DH/Voxy/managed/experimental groups；
- 空 capability set；
- runtime attach/detach marker，但不接管六面 `SphereFaceRuntime`。

这保留了当前 Sphere-Face “几何/地形/六面路由入口，而非 Earth feature-policy generator”的边界，不从 `EarthGeneratorSettings.DEFAULT` 发明 OSM、结构或 realtime 设置。

### 2.5 typed runtime shell

`TellusGeneratorRuntime` 现在使用 `net.minecraft.world.level.chunk.ChunkGenerator` typed ownership API，而不是 `Object`/反射。Phase 1 runtime 只提供：

- `settings()`；
- `owns(ChunkGenerator)`；
- 幂等 `attach`/`detach`；
- `attached()` marker。

它没有安装 server tick、chunk unload、player callback、payload receiver、ticket、网络请求或生成任务。因此不会产生双 tick、重复 callback 或 runtime 状态迁移。

## 3. 兼容边界审阅

### 3.1 必须保持的兼容契约

1. **Codec 与存档格式保持原样。** view 只读 decoded/effective object，不成为第二个序列化源。
2. **Earth/Cube-Net/Sphere-Face 的 settings ownership 不合并。** Earth settings 仍由 `EarthGeneratorSettings` 持有；Cube-Net settings 仍在 biome source，几何仍在 generator；Sphere-Face 仍由 geometry codec 持有。
3. **effective 值优先于 raw JSON。** Earth 的 `resolveHeightLimits`、`effectiveHeightOffset`、water normalization 和 experimental-height 不能在 view 中自行复制出另一套规则。
4. **runtime override 不进入 view。** realtime 命令覆盖、Voxy nullable 覆盖、DH 临时 API override 和 managed terrain retry/availability 仍属于各 manager/runtime 层。
5. **Optional.empty 的语义是“不拥有该策略”，不是简单 disabled。** 对 Sphere-Face 与 Cube-Net 的 Earth-only groups，消费者必须走已有 legacy branch，而不是把 empty 当成 false 并关闭功能。
6. **Cube-Net realtime fallback 不得被 Phase 1 改写。** 当前 adapter 返回 empty；下一轮若暴露 configured/effective realtime，必须保持 `runtime override > Earth settings（仅 Earth） > Cube-Net system properties > default` 的旧优先级。
7. **Sphere-Face 未成组六面时仍可独立存在。** settings adapter 不能把单 face 当成拥有 face routing；完整 group 校验留给未来 runtime adapter。
8. **版本 API 继续隔离。** 公共接口只提供跨目标语义，`shared/mc1201`/`shared/mc1211` 保留各自 `ChunkGenerator` 方法和 imports；不可把一个版本的 generator/Codec 类型引回根 shared 层。
9. **无 runtime takeover。** 在 consumer/factory 接入前，旧 `TellusCommon`、Cube-Net、Sphere-Face lifecycle registration 必须仍是唯一权威。

### 3.2 发现的待修正项

#### 已由 task-8 解决：strict identity ownership

全部 Earth、Cube-Net、Sphere-Face runtime adapters 现在要求 `candidate == this.generator`，不再接受同类 generator 的宽泛 `instanceof` ownership。这样绑定 generator A 的 adapter 不会把 generator B attach 到 A 的 settings view/runtime marker 上。

保留的验收要求：同类 generator B 传入 adapter A 时返回 `false`/`NOT_OWNED`；A 的 attach/detach marker 不受 B 影响。该边界已由代码实现，但仍应在下一轮 focused unit test 中固化。

#### 已由 task-8 解决：Cube-Net settings source factory

1.20.1 与 1.21.1 均新增 `CubeNetGeneratorSettingsViewFactory`，位于 `com.yucareux.tellus.worldgen` generator package，可读取实际 `CubeNetBiomeSource` 的 package-private `settings()`，不使用 reflection。Cube-Net settings/runtime adapter 现在提供单参实际 generator 构造；非 Cube-Net biome source 保留既有 `CubeNetBiomeSource.Settings.DEFAULT` fallback。

该修正消除了普通调用者随意把另一份 `Settings` 传给 generator adapter 的默认路径。显式双参构造仍应只供同包/测试或已完成一致性校验的调用者使用；`requireMatchingSettings` 对实际 source settings 与 supplied settings 不相等时抛出 `IllegalArgumentException`，未来 consumer 应优先使用单参构造/factory。world scale、sea level、height offset、apron/geometry 的完整一致性仍留给 focused tests，不应静默扩展为 runtime takeover。

#### P2：缺少 runtime factory/registry，当前还不能替换消费者

Phase 1/8 提供了 Cube-Net settings view factory，但没有统一 `TellusGeneratorRuntimeFactory`、registry 或 level identity map。当前这是正确的低风险边界，但也意味着：

- 没有证明每个 generator 只选择一个 runtime adapter；
- 没有证明 unrelated vanilla generator 选择 none；
- 没有防止同一个 level 重复创建/attach runtime；
- 没有生命周期 cleanup owner。

runtime factory 应留到后续 runtime adapter 阶段，并先做纯 identity classification 测试，再接入 `TellusCommon`。

#### P2：runtime view 尚未表达 live capability/readiness

`TellusGeneratorRuntimeView` 当前只返回 settings、kind、settings capability 和 attached marker。它没有 DH present/generation gate、Voxy live radius、managed availability、realtime effective override、face-group completeness 或 seam staging readiness。

因此当前 capability set 只能表示“该 generator family 的配置语义/潜在能力”，不能替代 manager 的 live state。后续消费者迁移必须先添加独立 runtime capability view，且不能把 `settings.capabilities()` 当成 DH/Voxy/mod availability。

#### P2：Sphere-Face group identity 尚未进入 view

Sphere-Face settings view 当前 geometry ID 只覆盖 mapping，未携带 face index、planet ID 或 singularity config。单 face settings 投影本身仍与现有 generator 行为兼容；不过 `SphereFaceRuntime` 的完整六面校验需要至少能够比较：

- face `0..5` 是否各自存在且不重复；
- planet ID 是否相同；
- mapping、world scale、singularity policy、sea level、vertical range 是否一致。

这些字段不应靠 `EarthGeneratorSettings.DEFAULT` 补齐，也不应在坐标 context 阶段临时拼装。

#### P2：structure ID 映射需固定一个反向表

adapter 将 `add_*` boolean 映射为资源 ID，例如 `minecraft:village`、`minecraft:desert_pyramid`、`tellus:abandoned_camp`。`TellusStructurePolicy` 当前按 path（如 `village`、`desert_pyramid`、`abandoned_camp*`）判断，而不是完整 resource ID。view 可以继续使用稳定 ID，但下一消费者必须通过一个集中、双向的 mapping helper 调用 policy，避免把 `minecraft:village` 直接传给只接受 path 的 API 后误判为 enabled。

#### P3：缺少 Phase 1 专用 unit tests

两个 compile task 证明类型兼容，但还没有覆盖：

- Earth effective vertical limits、experimental height、effective water/height offset；
- Cube-Net settings fallback、source/generator mismatch、height/apron geometry；
- Sphere-Face empty optional groups 与 grouped routing capability absence；
- adapter A/B identity ownership、idempotent attach/detach；
- `Optional.empty` 与 existing legacy fallback 的调用约定。

这些测试应在 factory/consumer 接入前补齐；不需要扩大真实世界、renderer、OSM 或 parity 样本，只需用现有 codec fixtures、synthetic sampler/doubles 和 generator test fixtures。

## 4. 当前 capability 覆盖矩阵

| 能力/语义 | Earth | Cube-Net | Sphere-Face | Phase 1 证据 | 下一步限制 |
|---|---|---|---|---|---|
| settings projection | 已覆盖 | 已覆盖 | 已覆盖 | compile + code audit | 加 codec fixture assertions |
| kind/generator/geometry identity | 已覆盖 | 已覆盖 | 已覆盖 | compile + code audit | factory identity test |
| effective vertical range | Earth 用 resolve limits；Cube/Sphere 用 generator range | 已覆盖 | 已覆盖 | adapter code audit | 显式 logical-height/experimental tests |
| sea level/height offset | 已覆盖 | 已覆盖 | 当前 height offset=sea level | code audit | 不把两者重新合并 |
| spawn | Earth 已覆盖 | Cube-Net 已覆盖 | empty | code audit | context adapter 需复用 owner/GeoPoint |
| terrain/structures/random biome | Earth full | Cube-Net subset | empty | code audit | resource ID/path mapping test |
| realtime configured settings | Earth 已覆盖 | empty，保留 system-property fallback | empty | code audit | 独立 live runtime capability |
| DH/Voxy/managed configured groups | Earth 已覆盖 | empty | empty | code audit | live readiness 不得由 settings capability 推断 |
| geodetic capability | Earth | Cube-Net | absent | capability sets | Sphere standalone remains absent |
| seam navigation | absent | declared | absent | Cube-Net capability set | 仍由 CubeNetRuntime 权威处理 |
| face routing | absent | absent | 当前 absent | no group-aware adapter | group validation 后再暴露 |
| lifecycle attach marker | Earth/Cube/Sphere | 每目标层 adapter | 每目标层 adapter | compile + code audit | strict identity 已由 task-8 固定；runtime factory 待后续 |
| actual lifecycle/tick takeover | absent | absent | absent | deliberate non-goal | 留到 runtime adapter 阶段 |

## 5. 下一轮 coordinate context 的前置条件

Coordinate context 可以在本阶段之后开始设计/实现，但只能以 settings view 作为**静态配置输入**，不能把当前 runtime shell 当成 live lifecycle API。进入下一轮前应满足以下条件。

### 5.1 坐标 context 必须保持四类坐标分离

下一轮新增的 context 至少应区分：

1. `GeoPoint`：地球物理位置，供 DEM、land cover、Köppen、OISST、OSM 和地标查询；
2. Earth compatibility point/grid：`EarthProjection`/Earth raster/classifier 的兼容 X/Z；
3. spherical noise point：跨日期线、跨 face 连续的 ECEF-like deterministic noise 坐标；
4. view/owner coordinate：Earth planar、Cube-Net face/planar、Sphere face-local，以及 canonical owner/apron mirror。

不能用 `worldScale`、`geometryId` 或 `generatorId` 推导另一类坐标；也不能把 Cube-Net planar X/Z 作为第二个 terrain owner。

### 5.2 context factory 的输入契约

context factory 应接受：

- `TellusGeneratorSettingsView` 的静态尺度、垂直范围、geometry identity；
- 明确的 generator/view kind；
- Earth `GlobalMapModel`/`EarthProjection`、Sphere `SphereWorldModel`/`SphereFaceTopology` 或 Cube-Net `CubeNetLayout`/`CubeNetAddressAdapter`；
- 当前 native/apron/LOD/biome-locate/structure-probe sampling purpose；
- canonical owner 与 seam/rotation metadata（若为 derived apron）。

settings view 不能替代这些 geometry authorities；尤其 Cube-Net 的 apron width、singularity/corner policy、face ownership 仍属于 Cube-Net geometry/runtime 边界。

### 5.3 coordinate context 验收门槛

在接入 SampleProvider 前，使用现有测试/证据，不增加真实样本面：

- `EarthProjectionTest`、`SphereWorldModelTest`、`SphereFaceTopologyTest`；
- `CubeNetAddressAdapterTest`、`CubeNetLayoutTest`、`CubeNetCrossFaceConsistencyTest`、`CubeNetBoundaryContractTest`；
- `SphereChunkSamplerTest` 与现有 sampler doubles；
- 现有 `13,824 columns / 0 mismatch` parity lane；
- 现有 `7 biome cases / 1 known bare/BWh mismatch` lane。

必须证明：

- negative-coordinate 使用 floor 规则不变；
- ±180°/日期线不产生 noise 跳变；
- seam 两侧相同 canonical point 共享 owner/GeoPoint；
- apron 不创建第二 owner；
- corner exclusion 继续返回明确 excluded，而不是猜测一个面；
- settings view 只提供配置，不改变 context 的 coordinate authority。

### 5.4 coordinate context 前不得做的事

- 不把 `TellusGeneratorRuntime.attach` 接到 `TellusCommon` 或任意 server lifecycle；
- 不把 `TellusGeneratorRuntimeView.capabilities()` 当成实时 mod/gate readiness；
- 不把 Cube-Net settings adapter 的 caller-supplied 双参 settings 当作可靠 source；优先使用 task-8 单参实际 generator 构造或 `CubeNetGeneratorSettingsViewFactory`，显式双参必须先通过 `requireMatchingSettings`；
- 不将 Sphere-Face 单面 adapter 标记为 face routing；
- 不在 coordinate context 里修复 `bare/BWh` biome mismatch；
- 不改变 Cube/Sphere 13,824-column comparator、Earth/Cube 7-case comparator 或现有 acceptance matrix。

## 6. 建议的下一阶段最小改动与回滚点

### 6.1 先修 Phase 1 contract，不接消费者

最小修正顺序：

1. **已完成（task-8）**：收紧所有 adapter 的 `owns` 到 bound generator identity；
2. **已完成（task-8）**：增加 Cube-Net source settings consistency accessor/factory，并让 mismatch 显式抛出 `IllegalArgumentException`；
3. 为 `TellusGeneratorSettingsView` 与 task-8 factory 添加 focused contract tests；
4. 增加纯 `TellusGeneratorRuntimeFactory` classification（Earth/Cube/Sphere/vanilla）；
5. 仅在 factory tests 通过后，才给一个无副作用 consumer 提供 view。

### 6.2 回滚点

- Phase 1 的最小回滚是删除/禁用 runtime package，旧 generator、Codec、runtime manager 和消费者完全不受影响。
- 若 factory 或单个 consumer 已接入，保留 `legacy` feature flag，以 generator family 为粒度回退；不删除 settings/Codec。
- 若未来 runtime attach 已持有 tickets/callbacks，先 `detach`，再禁用 factory；不能只切 boolean 而留下 lifecycle state。
- Cube-Net/Sphere-Face 的 payload、apron edit overlay、face routing 和 client renderer 不属于本阶段回滚范围，继续由现有实现管理。

### 6.3 Phase 1 的通过/暂缓判定

**通过（当前）**：

- 两目标 compile 成功；
- view 为只读 projection；
- no Codec/save/runtime consumer changes；
- Cube-Net/Sphere-Face capability 没有冒充 Earth-only integration；
- runtime 只有 typed ownership marker，无生命周期 takeover。

**暂缓（下一轮前需处理）**：

- focused unit/contract tests for strict ownership and settings factory;
- runtime factory/registry；
- live runtime capability/readiness；
- Sphere-Face group identity/routing view。

## 7. 与既有 parity 证据的关系

本审阅不改变 parity 基线：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知为 `bare/BWh`。

Phase 1 未接入 sampling/decision/writer，因此没有理由重新解释或更新这些数字。进入 coordinate context 时，两条 parity lane仍是每阶段门槛；任何数字变化必须先输出字段级差异报告并回滚或单独修复，不得把变化归因于“抽象层重构”。

## 8. 审阅结论

Phase 1/8 的实现边界是合适的：它建立了跨版本可编译的只读 settings projection 和 typed ownership shell，task-8 又完成了 strict identity ownership 与 Cube-Net 实际 settings factory，同时将现有 generator、Codec、manager、payload 和 lifecycle 保持为行为权威。它还没有形成可用于替换消费者的 runtime factory，也没有提供实时 capability/readiness，这两点应明确留在后续阶段。

下一步应先补齐 strict ownership/settings factory 的 focused contract tests，再开始 coordinate context；strict ownership 与 source consistency 的实现边界已由 task-8 固定。Coordinate context 的核心前提是保持 GeoPoint、Earth compatibility grid、spherical noise point、view/owner coordinate 四类语义分离；完成这一前置后再进入 SampleProvider，才能继续复用现有 `13,824/0` 与 `7/1 (bare/BWh)` 证据而不扩大测试面。
