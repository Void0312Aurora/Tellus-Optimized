# Tellus Phase 3 Provider Factory 审阅

> 审阅对象：task-37 revision 3 的 provider composition（`TellusSampleProviderFactory`、`TellusWorldgenSourcesProviderFactory` 与 `TellusWorldgenEarthSampleBackend`）。
>
> 审阅任务：task-39。
>
> 结论：**task-37 提供了一个显式、无生命周期 wiring 的 Earth provider composition root：optional snapshot/preload backends → concrete Earth memory-only backend → optional injected legacy backends。** Factory 没有接入任何 generator、旧 global source registry、cache manager、executor、runtime 或 consumer。Cube-Net/Sphere-Face 没有默认 Earth backend命中：没有匹配的 optional/legacy backend 时，adapter 返回 UNKNOWN；因此它可以作为下一轮 DecisionKernel 的输入，但在接入前必须固定 layer-level decision、source provenance、purpose/resolution mapping 和 provider/legacy parity 对照。

## 1. 实现范围与验证

### 1.1 Provider composition 类型

- `src/main/java/com/yucareux/tellus/worldgen/sample/TellusSampleProviderFactory.java`
- `src/main/java/com/yucareux/tellus/worldgen/TellusWorldgenSourcesProviderFactory.java`
- `src/main/java/com/yucareux/tellus/worldgen/TellusWorldgenEarthSampleBackend.java`

Factory 暴露：

- `TellusSampleProviderFactory.forEarth(settings)`；
- `forEarth(settings, optionalBackends)`；
- `forEarth(settings, snapshotBackends, legacyBackends)`；
- worldgen package facade `TellusWorldgenSourcesProviderFactory.forEarth(...)`。

Concrete Earth backend：

- `TellusWorldgenEarthSampleBackend(settings)`；
- 只接受 `EARTH_PROJECTED` owner；
- 校验 request geometry world scale 与 Earth settings scale；
- 使用 memory-only land-cover/visual-cover/elevation accessors；
- 使用 local-only land mask；
- 使用 `previewWaterResolver` 计算 water info，并将 projected Y 按 Earth effective height offset/vertical scale 转成 metres；
- 返回 AVAILABLE/PARTIAL/UNKNOWN，保留 source snapshot provenance；
- 不做 Cubed-Sphere/Sphere-Face采样。

### 1.2 独立验证

```powershell
./gradlew.bat -PtellusTargets=mc1201 :mc1201:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1211 :mc1211:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1201 :mc1201:test --tests "com.yucareux.tellus.worldgen.sample.TellusSampleContractTest" --offline --no-daemon --console=plain
```

本轮 mc1201/mc1211 `compileJava` 均 `BUILD SUCCESSFUL`。focused test 运行期间出现并行 task 对测试源文件的竞争，曾先出现 runtime result-file error，随后重编译阶段看到测试文件仍在被更新，导致非稳定的 compile diagnostics；该失败不作为 provider factory 行为结论。需要在 task 工作树稳定后单独重跑 focused suite，不能把这次并行写入失败当作 parity 或 source failure。

### 1.3 明确未接入

Factory 与 Earth backend 当前没有引用/调用：

- `EarthChunkGenerator`、`CubeNetChunkGenerator`、`SphereFaceChunkGenerator` 主循环；
- `TellusCommon`、realtime、DH、Voxy、managed terrain；
- `TellusWorldgenSources` global registry 的替换或 mutation；
- preload package registry 的全局注册；
- cache manager、executor、network/payload、runtime lifecycle；
- DecisionKernel、FeaturePlanner、writer 或 client path；
- parity comparator / generator output path。

Factory 只是显式调用者创建的 provider composition，不会自动改变旧生成路径。

## 2. Precedence 与 fallback

### 2.1 Earth composition 顺序

`TellusSampleProviderFactory.forEarth` 将 backends 按以下顺序复制到一个新 list：

1. optional/snapshot backends（调用者提供的 list order）；
2. `TellusWorldgenEarthSampleBackend(settings)`；
3. 双 list overload 的 injected legacy backends（调用者提供的 list order）。

随后由 `OrderedSampleBackendSelector` first-match 选择。这个顺序意味着：

- optional preload/snapshot backend 可以覆盖 Earth backend；
- concrete Earth backend 是默认 Earth fallback；
- legacy backends 只有在前两段都不 support request 时才命中；
- list 被 defensive copy，不会被 factory 外部后续 mutation 改变。

Factory 不自动推断 optional backends 是 preload、cache 还是 live source；调用者必须按明确顺序传入，并通过 backend `supports` 表达 applicability。

### 2.2 Earth backend support

`TellusWorldgenEarthSampleBackend.supports` 当前检查：

- owner 必须 `EARTH_PROJECTED`；
- request geometry worldScale 必须等于 injected Earth settings worldScale；
- 当前所有 `SamplingPurpose` enum 值均允许进入 Earth backend。

它不在 `supports` 中检查 projection ID、coverage flag、settings/source revision 或 purpose-specific resolution semantics；sample 时才做 coordinate range/source calls。因此真实 factory 接入前仍需要 request gate，避免 selector 先选中后才得到 generic UNKNOWN。

### 2.3 Cube-Net / Sphere-Face fallback

Factory 本身没有 Earth-only 的硬编码 unknown branch；行为由 selector 决定：

- Cube-Net canonical/apron 与 Sphere-Face request 不被 `TellusWorldgenEarthSampleBackend` 支持；
- 若 optional/legacy list 中没有支持对应 owner 的 backend，`TellusLegacySampleProviderAdapter` 返回 `TellusSample.unknown(request, "no legacy backend selected")`；
- 若调用者显式注入 Cube/Sphere legacy/snapshot backend，则 selector 可按 list order 命中该 backend；
- Earth backend不会把 Cube/Sphere坐标转成 Earth planar坐标，也不会伪造 dry/land/ocean。

因此当前 fallback 是“无匹配则 UNKNOWN，匹配的 legacy backend 由调用者显式提供”，不是自动调用旧 generator 或自动接入 `SphereChunkSampler`。

### 2.4 Non-blocking fallback

Concrete Earth backend使用 memory-only cover/elevation、local-only land mask，并在 source snapshot不可完整获取时保持 UNKNOWN/PARTIAL；optional snapshot backend若返回 QUEUED，factory adapter原样透传。Factory自身没有 scheduler、future join、refresh 或 network side effect。

真实 source adapter仍需证明 memory-only/local-only API不调度、不刷新；provider composition不能把 optional backend的 pending结果自动 fallback成 Earth dry/land，也不能把 Earth backend的 partial water写成 AVAILABLE完整列。

## 3. Concrete Earth backend 的 DecisionKernel 前置

### 3.1 Layer status 与 aggregate

Earth backend的 layer策略：

- elevation finite → AVAILABLE，否则 UNKNOWN；
- raw/visual cover均有效 → AVAILABLE，部分/缺失 → PARTIAL/UNKNOWN；
- water dry或有效surface → AVAILABLE，water info不可用 → UNKNOWN/PARTIAL；
- 三层全 AVAILABLE → aggregate AVAILABLE；
- 三层全 UNKNOWN → aggregate UNKNOWN；
- 其他组合 → aggregate PARTIAL。

DecisionKernel接入前必须逐 layer读取 status，不能把 aggregate PARTIAL当成所有字段可用：

- cover known、elevation unknown 对 biome/terrain 的允许范围需由 kernel policy 明确；
- water unknown 对 shoreline/ocean/biome 的 fallback需明确；
- partial elevation 不应自动转成默认 0m或sea level；
- provenance必须保留 source stage/unknown reason。

### 3.2 Water conversion 前置

相比早期 concrete bridge，Earth backend已使用 `WaterSurfaceResolver.WaterInfo` 并进行：

```text
surfaceMetres = (projectedSurfaceY - effectiveHeightOffset)
                 * effectiveVerticalWorldScale
```

这仍需要 DecisionKernel/source contract 固定：

- `WaterInfo.surface()` 的 Y 坐标是否与该 settings/worldScale 同一世界；
- ocean/inland/lake authority precedence；
- enableWater、DEM selection、resolution/coverage；
- no-data/pending/coverage unavailable status；
- water conversion与Earth legacy materialization的同值对照。

在这些证据前，不应把 water AVAILABLE 视为完整 Earth/Cube parity已通过。

### 3.3 Purpose/resolution mapping

Earth backend把 quantized metres传入现有 source accessors，但 purpose→source semantics仍未完全固定：

- `LOD`影响 visual cover，但 DEM/elevation resolution contract需明确；
- `PRELOAD`、`BIOME`、`BIOME_LOCATE`、`HEIGHTMAP`、`SURFACE`共享同一 cover/elevation calls，尚无 source-specific mapping；
- `FEATURE_PROBE`、`STRUCTURE_PROBE`进入Earth backend虽可返回terrain样本，但不等价于feature/structure backend；
- `NON_BLOCKING`由 request flag选择memory/local-only路径，仍需 source side-effect proof。

DecisionKernel不应从 purpose 名称猜 source precedence；provider factory应在真实 backend factory里显式配置 purpose/resolution capability。

### 3.4 Geometry identity / coverage gate

Earth backend目前主要比较 worldScale。真正接入前应增加：

- `GeometryIdentity.projectionId()` 与 `EarthProjection.projectionModeId()`；
- `coordinate.earth().projectionModeId()` 与 geometry identity；
- `withinMercatorCoverage` 极区标记；
- concrete revision/source dataset/settings fingerprint；
- Earth owner cell range与int source range；
- noise algorithm identity（即使Earth source当前不使用，也应在key中稳定）。

不匹配应由 supports/gate 返回 false或明确 UNKNOWN，不得把clamped极区/错projection结果作为AVAILABLE Earth terrain。

## 4. Source API blockers 与 legacy fallback

### 4.1 Source API blockers

1. **Water API equivalence**：projected Y→metres 已有转换代码，但与Earth legacy water materialization的逐值等价尚未用fixture锁定。
2. **Purpose/resolution map**：factory支持所有 purpose，但existing cover/elevation accessors未按purpose分层定义。
3. **Coverage/revision gate**：worldScale校验存在；projection、Mercator coverage、source revision/fingerprint尚未完全进入supports。
4. **Layer policy**：aggregate PARTIAL合法，但DecisionKernel的per-layer消费策略尚未定义。
5. **Provenance**：Earth snapshot provenance以 `settings:<worldScale>` 作为revision，缺少DEM/cover/water/source dataset revision、coverage和stage细分。
6. **Source side effects**：memory-only/local-only semantics需要source-owned测试证明；factory本身不调度不等于底层source没有side effect。
7. **Duplicate backend IDs**：factory复制传入list，但selector目前不要求backendId唯一；重复source registration需明确拒绝或允许alias。

### 4.2 Legacy fallback

- Earth request：optional snapshot先命中；否则 concrete Earth backend；再否则 injected legacy backend；全部不匹配则 UNKNOWN。
- Cube-Net/Sphere-Face：concrete Earth backend返回 false；由显式 injected backend或legacy provider处理；无匹配则 UNKNOWN。
- 不会自动回退到 generator、cache registry、`SphereChunkSampler`或旧 `TellusWorldgenSources`路径。
- 旧生成路径仍是唯一行为权威；factory provider是 opt-in composition，不是替换。

## 5. 最小测试矩阵

不扩大测试面，优先使用现有 fixtures/test doubles和同一parity comparator：

| 类别 | 测试/证据 | 门槛 |
|---|---|---|
| composition order | 新增/稳定 `TellusSampleContractTest` factory cases | optional→Earth→legacy first-match稳定 |
| Earth support | concrete Earth backend tests | Earth owner/scale/coverage gate明确 |
| Cube/Sphere fallback | provider factory tests | 无匹配返回UNKNOWN；显式backend可命中；不调用Earth source |
| source calls | `TellusWorldgenSourcesTest`、`EarthProjectionTest` | existing cover/elevation/water accessors不变 |
| water | `WaterSurfaceResolverTest` + Earth water fixtures | Y→metres等值；未证明时不升级完整AVAILABLE |
| status/kernel input | sample consistency tests | layer status/provenance保持；PARTIAL不压平 |
| non-blocking | memory/local-only doubles | no scheduling/IO/join；queued/unknown透传 |
| provenance | source revision/fingerprint tests | stage/source/coverage/error可诊断 |
| parity | Cube/Sphere `13,824 / 0 mismatch` | provider未接入时不改；未来opt-in双路径均0 mismatch |
| biome | Earth/Cube `7 cases / 1 mismatch bare/BWh` | 不新增mismatch，不在factory/kernel修复bare/BWh |

### 5.1 接入顺序

1. 只测 injected optional/legacy backends与factory list order；
2. 测 Earth backend request/identity/coverage/water/status；
3. 复用既有 source fixtures 对照 Earth legacy sample；
4. 验证 Cube/Sphere no-match UNKNOWN 与显式legacy backend命中；
5. 重跑source compatibility tests；
6. 重跑同一parity comparator；
7. DecisionKernel先消费layer-aware Earth sample，再考虑任何generator family opt-in。

## 6. 无 generator 接入、回滚与 parity

### 6.1 当前可承诺范围

- provider factory composition顺序显式且可注入；
- Earth backend是只读memory/local-only source bridge候选；
- Cube/Sphere无专用backend时保持UNKNOWN/legacy fallback；
- status/provenance/request identity由既有contract校验；
- factory不安装lifecycle、不改generator、cache、runtime、network或consumer；
- 删除factory/backend不需world/Codec/save/runtime migration。

### 6.2 当前不可承诺范围

- provider factory已接入任一generator或旧source consumer；
- Cube/Sphere concrete sampling；
- Earth purpose/resolution/coverage/revision source contract完全闭合；
- water conversion与legacy Earth output逐值等价；
- DecisionKernel已消费provider样本；
- provider-driven parity replay。

### 6.3 回滚点

1. **Factory-only rollback**：删除/禁用`TellusSampleProviderFactory`与worldgen facade；旧source/generator路径不变。
2. **Earth backend rollback**：从composition list移除concrete Earth backend，继续optional/legacy backends或旧Earth source calls。
3. **Optional precedence rollback**：恢复旧optional list order；不改source data/cache package。
4. **Water/status rollback**：water conversion或partial status异常时只禁用backend；不回滚Earth legacy materialization。
5. **Kernel rollback**：DecisionKernel发现layer policy变化时回到legacy decisions；保留provider contract供诊断。
6. **Parity rollback**：任何`13,824`或biome数字变化先停止opt-in，输出source/precedence/layer diff，回到legacy path。

## 7. Parity 门槛保持

本次 provider factory 审阅不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

当前 factory/provider没有接入generator或旧consumer，因此没有新的parity结果。未来Earth opt-in必须与legacy Earth path使用同一comparator；Cube/Sphere仍以显式backend或UNKNOWN fallback为当前contract。任何新增mismatch都是迁移闸门失败，不能用precedence重排、partial/UNKNOWN、water转换或resolution映射解释为通过。

## 8. 审阅结论

Task-37 的composition root边界正确：optional snapshot/preload backends先于concrete Earth backend，Earth backend先于injected legacy backends；selector first-match明确表达precedence，factory不接管generator/runtime。Cube-Net/Sphere-Face不会误命中Earth backend，无专用backend时返回UNKNOWN并保留legacy fallback。

下一步应先完成Earth source API contract（water等值、purpose/resolution、projection/coverage/revision、provenance和LocalOnly side-effect tests），再让DecisionKernel定义layer-aware partial消费策略。只有provider/legacy source对照和同一parity comparator通过，才能在不扩大测试面的前提下让一个generator family opt-in，并继续保持`13,824 / 0`、`7 / 1 bare/BWh`门槛。
