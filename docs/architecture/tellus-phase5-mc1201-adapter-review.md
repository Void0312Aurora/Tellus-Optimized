# Tellus Phase 5 mc1201 Adapter 审阅

> 审阅对象：task-52 revision 3 的 mc1201 semantic mapper/canonical column adapter。
>
> 审阅任务：task-54。
>
> 结论：**task-52 提供了一个隔离的 Minecraft 1.20.1 canonical-column adapter：先执行纯 `TellusColumnMaterializer` guard，再将一个已知 semantic top material 映射为 `BlockState`，最后写入目标 `ChunkAccess`。UNKNOWN、QUEUED、显式拒绝的 PARTIAL、apron target 和 invalid owner 都在目标 chunk触碰前返回。当前 adapter 没有注册 generator/lifecycle，apron mirror仍 deferred，legacy writer仍是默认回滚路径，未改变 parity 输出。**

## 1. 实现范围与验证

### 1.1 mc1201 adapter 类型

- `shared/mc1201/src/main/java/com/yucareux/tellus/worldgen/write/TellusMinecraft1201CanonicalColumnAdapter.java`
- `shared/mc1201/src/main/java/com/yucareux/tellus/worldgen/write/TellusMinecraft1201SemanticMapper.java`

`TellusMinecraft1201SemanticMapper`：

- 只把 `SemanticMaterial` 映射到 1.20.1 `BlockState`；
- 不读取 `ChunkAccess`、world、registry或 source；
- `UNKNOWN` material 显式抛出 mapping error；
- 其他 semantic values 使用 1.20.1 `Blocks.*.defaultBlockState()`。

`TellusMinecraft1201CanonicalColumnAdapter`：

- 接收 `ChunkAccess`、`WriteContext`、`ColumnDecision`；
- 先执行 status/ownership/persistence guard；
- 只写一个已知 semantic top cell；
- body/water/strata expansion仍 deferred；
- canonical Earth/Sphere/Cube owner可解析 position；
- Cube-Net apron直接返回 invalid，交给后续 canonical mirror adapter。

### 1.2 编译验证

```powershell
./gradlew.bat -PtellusTargets=mc1201 :mc1201:compileJava --offline --no-daemon --console=plain
./gradlew.bat -PtellusTargets=mc1211 :mc1211:compileJava --offline --no-daemon --console=plain
```

结果：mc1201 与 mc1211 `compileJava` 均 `BUILD SUCCESSFUL`（mc1201 `UP-TO-DATE`，mc1211 执行成功）。

未发现独立 mc1201 adapter test 文件；task-50 writer contract tests覆盖 shared guard/persistence/mapper boundary，但当前尚未证明真实 `ChunkAccess.setBlockState` 目标写入、save/reload、heightmap/light或完整 generator输出。

## 2. No-write-before-target contract

### 2.1 Guard顺序

adapter 的 `write` 顺序是关键兼容边界：

1. 校验 context/decision 非空；
2. UNKNOWN 立即返回 `NO_WRITE_UNKNOWN`；
3. QUEUED 立即返回 `NO_WRITE_QUEUED`；
4. apron target立即返回 `NO_WRITE_INVALID`；
5. `PartialWritePolicy.NO_WRITE` + PARTIAL 返回 `NO_WRITE_PARTIAL`；
6. 执行 shared `TellusColumnMaterializer`，建立 persistence identity并再次校验 owner/decision；
7. 检查 terrainY 与 top material；
8. 只有到此才 `requireNonNull(target)`、构造 `BlockPos`、调用 mapper并执行 `target.setBlockState`。

因此 UNKNOWN、QUEUED、拒绝的 PARTIAL、wrong decision coordinate和apron context都在 target chunk触碰前返回。这是首个旧 consumer adapter应保持的 no-write-before-target 纪律。

### 2.2 Target write scope

当前 adapter只写一个 top cell：

- `terrainY` 作为 Y；
- owner block X/Z作为 `BlockPos` X/Z；
- mapper映射 `decision.surface().top()`；
- `setBlockState(position, state, false)`；
- status为 AVAILABLE→WRITTEN、PARTIAL known-layers→PARTIAL；
- 返回 persistence identity和 `canonical semantic top cell` diagnostics。

它不写：

- surface filler/depth；
- strata body/bedrock；
- water surface/column；
- heightmap/light/unsaved/block entity；
- feature placements、roads/buildings/trees；
- apron mirror或 seam-transformed block。

因此该 adapter是 isolated top-cell sink，不是完整 terrain writer或完整 Earth/Cube generator替换。

## 3. Version boundary 与 semantic mapping

### 3.1 mc1201-only API isolation

mc1201 adapter唯一引入目标版本 API：

- `net.minecraft.core.BlockPos`；
- `net.minecraft.world.level.block.state.BlockState`；
- `net.minecraft.world.level.chunk.ChunkAccess`；
- `net.minecraft.world.level.block.Blocks`。

这些类型没有回流到 root `src/main` writer contract。`TellusColumnWriter<M>`、`SemanticMapper<M>`、`WriteContext`、`WriteResult`和 persistence records仍保持跨版本/opaque边界。mc1211只参与编译回归，没有共享 mc1201 adapter类。

### 3.2 Mapper policy

`TellusMinecraft1201SemanticMapper` 的 mapping表覆盖当前 `SemanticMaterial`：

- AIR→`Blocks.AIR`；
- BEDROCK→`BEDROCK`；
- STONE/DEEPSLATE/DIRT/GRASS/SAND/SANDSTONE/GRAVEL/CLAY/MUD；
- SNOW/POWDER_SNOW/ICE/WATER；
- TERRACOTTA/RED_SAND；
- UNKNOWN→显式 `IllegalArgumentException`。

该 mapping没有 fallback到stone，因此 unknown semantic input不会静默写错方块。真实 adapter tests还需验证 mapper exception发生在 target mutation之前；当前代码先计算 state再 set，mapper失败不会写 target。

### 3.3 Coordinate owner mapping

`position(context,y)`支持：

- `EarthOwnerView`：Earth compatibility point floor X/Z；
- `CubeNetCanonicalOwnerView`：canonical sphere block X/Z；
- `SphericalOwnerView`：sphere block X/Z；
- 其他 owner：抛出 canonical owner异常；
- long X/Z超出 Minecraft int范围：抛出明确异常。

它不支持 `CubeNetApronOwnerView`，由顶层 guard提前拒绝。owner mapping未做 chunk bounds、dimension min/max Y或target chunk identity校验；这些应由后续 concrete storage adapter补齐。

## 4. Canonical/apron 与 persistence

### 4.1 Canonical-only behavior

adapter 只接受 canonical target：

- Earth projected canonical；
- Sphere face canonical；
- Cube-Net canonical；
- 不接受 Cube-Net apron target。

shared `WriteContext` 已为 apron保存 canonical source，并可生成 `CanonicalPersistenceKey`/`SeamProjectionIdentity`；但 mc1201 adapter 当前不消费apron projection。这样避免错误地把 proxy/apron block当成第二 canonical storage owner。

### 4.2 Persistence identity propagation

shared materializer先创建 `TellusPersistenceIdentity`，adapter成功/guard result通过 `WriteResult.persistenceIdentity`返回。该 identity：

- canonical owner/address是durable key；
- apron derived view不会替代canonical owner；
- geometry stable key参与 identity；
- seam projection仅作为derived proxy/target face/chunk诊断。

当前 `setBlockState` 写入没有保存 persistence key、transaction ID或replay marker到 Minecraft storage；save/reload/idempotency仍是后续 storage adapter职责。

### 4.3 Apron deferred

apron mirror目前返回 invalid no-write：

- 不执行 `BlockPos`构造；
- 不调用 mapper；
- 不调用 target setBlockState；
- 不从apron proxy重新采样或重新生成decision。

后续 mirror adapter必须从 canonical decision、canonical persistence key和 seam projection执行，并证明 canonical/apron写入不会重复持久化或覆盖编辑 overlay。

## 5. Legacy fallback 与未接入 generator

### 5.1 Legacy fallback

当前 adapter是显式构造的 isolated sink，不是自动替换：

- 旧 `EarthChunkGenerator`/`SphereTerrainMaterializer`/Cube-Net writer继续是行为权威；
- adapter默认没有 feature flag/registry/lifecycle registration；
- adapter异常或未支持apron不自动改写旧路径；
- consumer可在 adapter返回 no-write/invalid时回到 legacy writer；
- 不删除旧 cache、save、edit overlay或generator state。

首个正式 consumer应将 adapter置于 opt-in branch：legacy默认，adapter只处理明确满足 status/owner/target条件的 isolated canonical column。

### 5.2 明确未接入

未修改/接入：

- `EarthChunkGenerator`、`CubeNetChunkGenerator`、`SphereFaceChunkGenerator`主循环；
- `TellusCommon`、runtime、payload、server lifecycle；
- Cube-Net apron edit/index/block entity sync；
- heightmap/light/unsaved/save/reload；
- FeaturePlanner、SampleProvider、DecisionKernel registry；
- Sodium/DH/client renderer、Voxy；
- parity comparator/generator output path。

mc1211仅编译回归，不共享 mc1201 target adapter实现。

## 6. Adapter blockers 与首个 consumer 前置

### 6.1 Target storage blockers

- `setBlockState(..., false)` flags、heightmap和light更新策略未定义；
- chunk min/max Y、target dimension bounds未校验；
- block entity、unsaved、save/reload与transaction rollback未接入；
- multiple writes/order/idempotency未定义；
- top-cell write与后续 filler/strata/water expansion conflict未定义。

### 6.2 PARTIAL policy

默认 `WriteContext.canonical(...)`使用 `KNOWN_LAYERS_ONLY`；adapter允许PARTIAL且写已知top cell，返回 `PARTIAL`。若首个 consumer需要严格完整列，应显式使用 `PartialWritePolicy.NO_WRITE`，让shared guard返回 `NO_WRITE_PARTIAL`，目标chunk untouched。

不得让mc1201 adapter自行把PARTIAL升级为AVAILABLE，也不得写未知layers的默认stone/water。后续 storage/feature policy需决定PARTIAL重算、pending marker和legacy fallback。

### 6.3 Semantic mapper blockers

- UNKNOWN material已显式失败；
- mapper没有读取context以外的registry/world；
- mapping表尚未逐项与现有 Earth/Cube/Sphere material semantics对照；
- water/snow/bedrock高度与版本feature规则还未扩展到完整 column；
- mapper失败、range失败和target set失败的统一 WriteResult error taxonomy待固定。

### 6.4 首个 consumer 顺序

1. 先通过纯 writer contract tests验证 no-write-before-target、identity、PARTIAL policy、mapper unknown；
2. 以现有 fresh disposable chunk fixture 做单一 mc1201 canonical top-cell adapter测试；
3. 只允许 AVAILABLE或显式 KNOWN_LAYERS_ONLY PARTIAL；`NO_WRITE`默认可作为严格模式；
4. 对照旧 isolated column writer的top-cell state、owner/persistence identity和no-write行为；
5. 再接 surface/filler/strata/water layers；
6. 最后才考虑 Cube-Net canonical/apron mirror、save/reload、edit overlay、heightmap/light；
7. 全程保留legacy writer和feature flag，不改generator默认路径。

## 7. 最小测试矩阵与 parity 门槛

不扩大测试面，复用 shared contract/geometry/decision证据并新增有限 mc1201 adapter fixture：

| 类别 | 测试/证据 | 门槛 |
|---|---|---|
| no-write-before-target | mc1201 adapter tests | UNKNOWN/QUEUED/NO_WRITE_PARTIAL/apron均不触碰target |
| mapping | `TellusMinecraft1201SemanticMapper` tests | 所有known materials稳定；UNKNOWN显式失败 |
| owner position | canonical Earth/Sphere/Cube fixtures | X/Z floor/range/owner mapping正确 |
| persistence | shared task-49/50 tests | canonical key shared；apron derived identity不成为owner |
| partial | `PartialWritePolicy` + adapter tests | known-layers或NO_WRITE语义稳定 |
| legacy fallback | isolated consumer fixture | adapter opt-in失败回legacy，不改旧save/cache |
| storage | 后续 ChunkAccess tests | setBlockState/heightmap/light/unsaved顺序正确 |
| parity | Cube/Sphere `13,824 / 0 mismatch` | adapter未接入generator时无变化；未来opt-in双路径0 mismatch |
| biome | Earth/Cube `7 cases / 1 mismatch bare/BWh` | 不新增mismatch，不在mc1201 mapper修复bare/BWh |

## 8. Parity 门槛保持

本次 mc1201 adapter审阅不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

当前 mc1201 adapter没有注册generator/consumer、没有完整storage output或parity replay，因此没有新的parity结果。其isolated top-cell write不构成Earth/Cube/Sphere column parity；未来接入必须与legacy writer使用同一comparator。任何新增mismatch都是迁移闸门失败，不能以mapper版本差异、PARTIAL policy、apron deferred或top-cell scope解释为通过。

## 9. 审阅结论

Task-52 mc1201 adapter boundary正确地把真实 Minecraft API限制在版本层：先通过 shared no-write/ownership/persistence guard，再做 semantic material→BlockState mapping，最后才触碰 ChunkAccess；UNKNOWN、QUEUED、拒绝PARTIAL和apron target都在目标写入前返回。它只写单一canonical top cell，apron mirror、完整column layers、save/reload、heightmap/light与generator wiring均未实现。

下一步应在legacy默认/feature flag下，用单一mc1201 canonical column fixture验证target mutation、PARTIAL/NO_WRITE、persistence/idempotency和mapper error，再逐层扩展；继续保持generator未接入、Cube/Sphere/biome parity comparator门槛不变。
