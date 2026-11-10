# Tellus 自定义生成器与原生功能迁移审计

日期：2026-11-10，Asia/Shanghai。方式：源码、Git 对照、留存原始验收材料核验、离线门禁与最小坐标算式复现。

## 1. 结论摘要

**主仓库已确认；mc1201/mc1211 的统一生产链迁移和可回退默认接入已完成其已声明验收范围。后续等价性以全球旅行受到影响的地形、环境与实际运行组合为准，不以原生每处细节或全部 loader 一致为交付条件。**

2026-11-10 晚间旅行主线修正：分类 provider 的 Earth/球面 noise 坐标分离，雪/峡谷邻域改为同一地理位置的邻点；接入 `custom_trees`、`huge_red_mushrooms`、`climate_based_built_up_terrain`；球面 Macrostrat 默认跟随原生启用策略。**DEM 可选源缺口是前次审计误判**：当前 Earth 的 `DemSelection` 构造/自动/手动/未知 ID 都归一到已有 Mapterhorn（兼容 ID `terrarium`），海底沿用 OpenWaters；没有一项实际可选源能力需要在 Cube 再造开关。新代码、回归和真实原生进程证据集中在[旅行修正记录](../validation/cube-net-travel-parity-20261110/README.md)。下文 A1–A3 保留最初审计快照，完成状态以其后结构补齐记录为准。

这里须分开两个命题：

1. **重构前后等价**：旧 Cube/Sphere/Earth 路径与各自新的统一生产路径保持输出和生命周期语义。已有较强源码、受控真实引擎入口及有限真实 GIS 世界验收证据。
2. **跨生成器功能等价**：Cube-Net 或 Sphere-Face 是否覆盖原生 Earth 的所有规则、设置、结构和运行体验。仍有明确功能差异、版本移植缺口，以及本轮新发现的结构碰撞坐标错误。

统一链记录了四个阶段，不代表四个阶段在所有生成器内都有相同功能；与旧自定义实现保持一致，也会保留旧实现的遗漏。

初始只读审计阶段未修改生产源码、Git 分支或索引，未重新启动 Minecraft、未运行 Gradle 全量编译/回归。下文 A1–A3 是该阶段缺陷快照；后续补齐阶段已修复这三项及终审发现的查询预算问题，并完成双版本扩展回归（629/620项），实际服务器 Mixin 烟测与自然结构验收仍分开。详见[补齐结果与原始证据](../validation/cube-net-structure-fix-20261110/README.md)。Git分支、HEAD与索引仍保持原状。

## 2. 仓库与基线

- 主仓库：[Tellus-Optimized](../../)。现场绝对路径 `D:/workshop/Tellus-Optimized`。
- 当前分支 `feat/cube-net-parity`，HEAD `1c35ccbb2441fd146ceb28ee35700f684dec8ed3`；不是旧记忆中的 `main`。
- origin：`https://github.com/Void0312Aurora/Tellus-Optimized.git`。
- upstream：`https://github.com/Yucareux/Tellus.git`。
- 本地 `upstream/main`：`c38a4d2f4d732056cdde79578920c8c66e4e189b`，也是 HEAD 与该引用的 merge-base；HEAD 相对它 ahead 200、behind 0。
- 本地 `origin/main`：`ede5c3d43a79d4cc58f5ccf0a5648851d73b9138`；HEAD 相对它 ahead 120、behind 0。
- 本轮未 fetch；以上是本地引用，不是对远端最新状态的声明。
- 已提交 upstream→HEAD 差异：561 个文件，+77,041/-848；upstream→当前 tracked 工作树：566 个文件，+78,757/-1,148。
- 当前生产审计对象还包括 21 个 tracked modified 文件及 769 个完全展开的 untracked 文件；普通 `git status` 的 129 条 untracked 目录折叠项不是文件总数。索引没有 staged 变更。

最新 sample/decision/write/runtime/persistence 实现及验收资料大量在工作树中。只对 HEAD 做审计或直接重置到 HEAD，都会漏掉这些最新实现。

### 原生基线的三层含义

| 基线 | 本轮用途 |
|---|---|
| 本地 upstream/main 的原生 Tellus Earth | 识别哪些能力原本就存在，判定跨生成器功能迁移缺口 |
| 当前工作树 EarthChunkGenerator | 检查现有完整功能实现与共享规则接入 |
| 真实 A/B 的旧生产提交 `80dd37ba39677adb5a4229a781ca3b4e5adabc8f` | 判断统一生产链是否改变此前实现；这个对照不是 upstream 原生 Earth 与 Cube 的全功能对照 |

## 3. 我们自定义的内容

原生 Tellus 本来就具有真实 DEM、ESA/Köppen 生物群系、OSM/Arnis 道路建筑、树冠树木、洞穴矿物、结构开关、GeoTP 地图 UI、DH 和实时天气。将这些整项称为我们新造的功能会混淆迁移目标。

我们的主要新增和延伸为：

| 自定义内容 | 入口/说明 |
|---|---|
| Cube-Net 单维度六面展开网 | [CubeNetLayout](../../src/main/java/com/yucareux/tellus/worldgen/global/CubeNetLayout.java#L8-L16)、[CubeNetChunkGenerator](../../mc1201/src/main/java/com/yucareux/tellus/worldgen/CubeNetChunkGenerator.java)；canonical owner、cut seam、apron 派生镜像 |
| Sphere-Face 六维度球面模式 | [SphereFaceChunkGenerator](../../mc1201/src/main/java/com/yucareux/tellus/worldgen/SphereFaceChunkGenerator.java)、[SphereWorldModel](../../src/main/java/com/yucareux/tellus/worldgen/global/SphereWorldModel.java)；基础球面地形及 face routing |
| 原有数据链的 GeoPoint 入口、球面采样和特征投影 | [SphereChunkSampler](../../src/main/java/com/yucareux/tellus/worldgen/global/SphereChunkSampler.java)、[CubeNetFeatureRasterizer](../../src/main/java/com/yucareux/tellus/worldgen/global/CubeNetFeatureRasterizer.java) |
| GLiM/Macrostrat 地质数据和球面地层适配 | [geology](../../src/main/java/com/yucareux/tellus/world/data/geology/)、[SphericalGeologySource](../../src/main/java/com/yucareux/tellus/world/data/geology/SphericalGeologySource.java) |
| GeoTP 对自定义投影的泛化 | [TellusGeodeticPosition](../../src/main/java/com/yucareux/tellus/worldgen/TellusGeodeticPosition.java)；沿用原生地图 UI/传送链，不是另建一套 UI |
| 跨面 runtime、编辑同步、载具和渲染适配 | [mc1201 worldgen](../../mc1201/src/main/java/com/yucareux/tellus/worldgen/)、[mc1201 client](../../mc1201/src/client/java/com/yucareux/tellus/client/)；Sodium/Iris/DH seam 扩展 |
| 世界创建工具和预载适配 | [Cube datapack 工具](../../tools/create_cube_net_datapack.py)、[Sphere datapack 工具](../../tools/create_sphere_datapack.py)、[CubeNetPreloadAdapter](../../src/main/java/com/yucareux/tellus/worldgen/CubeNetPreloadAdapter.java) |
| 统一生成抽象及生产挂接 | [NativeProductionPipeline](../../src/main/java/com/yucareux/tellus/worldgen/runtime/TellusNativeProductionPipeline.java)、[NativeRuntimeHost](../../src/main/java/com/yucareux/tellus/worldgen/runtime/TellusNativeRuntimeHost.java)、[NativeSphericalTerrainStage](../../src/main/java/com/yucareux/tellus/worldgen/TellusNativeSphericalTerrainStage.java)；保留现有生成器和 MC scheduler/save queue |

## 4. 已完成且有证据的迁移

### 4.1 当前默认是真实 UNIFIED，不是仅 opt-in 工厂

[NativeRuntimeHost:17–35](../../src/main/java/com/yucareux/tellus/worldgen/runtime/TellusNativeRuntimeHost.java#L17-L35) 的 release default 为 true，限制版本为 1.20.1/1.21.1；[pipeline:37–47](../../src/main/java/com/yucareux/tellus/worldgen/runtime/TellusNativeProductionPipeline.java#L37-L47) 支持显式 legacy/unified 覆盖。

真实 loader bootstrap 后无参数默认走统一链；standalone 构造在未 bootstrap 时仍保持 legacy。默认切换指既有生成器内部链路，不是把所有世界 preset 自动改成 Cube-Net。

[最新默认验收](../validation/native-runtime-default/README.md#L5-L23)记录了双版本：三类生成器解码/attach/tick/stop、无 flag 的 Cube 新生成 A/B、同一世界 default reload/save，以及显式 legacy 重启回滚。

回滚参数：

```text
-Dtellus.worldgen.pipeline=legacy
```

mode 在生成器构造时确定，不支持运行中热切换。无需转换既有存档 schema。启动 journal recovery 早于 runtime 模式判定，legacy 回滚也继续遵守未恢复 journal 的隔离约束。

### 4.2 迁移内容与证据等级

| 项目 | 已有验收 | 严格边界 |
|---|---|---|
| 表面、高程/水/地层、Cube flow/badlands、biome volume、地下规则、积雪 | 真实生产 callsite 已使用共享 kernel/provider/writer 或共享规则；有完整受控引擎入口对照 | 不代表所有特征都已转换成统一逐格 FeaturePlan；Earth 仍复用原生 section writer 与 detail helpers |
| 完整受控生成对照 | 每版 Cube 24 chunks/2,359,296 states；Sphere 18/1,769,472；Earth fill 135,168；含 biome/heightmap/markers/source trace | controlled inputs，不是独立全球 GIS 正确性 oracle；[生产验收](../validation/unified-native-production/README.md#L13-L24) |
| 真实 GIS 新区块生成/保存 | 每版本一个此前不存在的 canonical Cube chunk；1,040,384 blocks、16,256 biome cells、完整 native NBT 一致 | 旧对照是 `80dd37ba`；只排除 LastUpdate/InhabitedTime；不是 Earth↔Cube 全功能比较；[默认验收](../validation/native-runtime-default/README.md#L35-L45) |
| 非默认海平面 Codec | height_offset=76，运行时 sea=88，存档 sea_level=76；三独立启动、两次 stored-LevelStem reload 稳定 | 修复重复存档漂移，保留旧 decoder 语义；[海平面验收](../validation/native-sea-offset-restart/README.md) |
| 非空原生生命周期 | 真实箱子/3 diamonds、block tick、fluid tick、重复 postprocessing markers，经 native save/fault rollback/restart 保留 | 已超越纯 NBT 契约；[Round68](../validation/round68-live-lifecycle/README.md) |
| 双区块 crash recovery | 真正部分发布后 JVM halt；QUARANTINED 故障启动、prepareLevels 前恢复、二次重启保留原始 NBT | 适用于已有 unloaded FULL chunk；不是活跃 loaded chunk 替换或断电耐久性；[crash gate](../validation/native-crash-recovery/README.md) |
| native 生命周期挂接 | Earth deferred tick 经实际 adapter，在原 tick 位置恰好一次；stop detach 清空 registry | Earth unload 为 probe 显式派发，非自然卸载有内容 Earth chunk；Earth/Sphere family fixture 没有生成其真实地形；[边界](../validation/native-runtime-default/README.md#L25-L33) |

正常 MC 保存仍由 ChunkSerializer/ChunkMap 负责；显式 NativeChunkPersistence 事务接口与 startup recovery 不是“每个普通区块都会走的新存储引擎”。跨邻区块原子装饰、外部 region writers、活跃区块批替换、power-loss durability 均不属于已接受范围。

## 5. 跨生成器等价性缺口

### A1 / 高优先级：结构碰撞过滤坐标错误（本轮新发现）

双版本 `cubeNetStructureCollides` 存在相同问题：

- [mc1201:1869–1896](../../mc1201/src/main/java/com/yucareux/tellus/worldgen/CubeNetChunkGenerator.java#L1869-L1896)。
- [mc1211:1882–1909](../../shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/CubeNetChunkGenerator.java#L1882-L1909)。

`SphereChunkAddress.blockX()/blockZ()` 已经返回 `chunkX/chunkZ * 16`，见 [model:694–700](../../src/main/java/com/yucareux/tellus/worldgen/global/SphereWorldModel.java#L694-L700)。碰撞代码再次乘以 `16L`，且将 face-local 位置与结构的 engine-planar BoundingBox 直接取交，再交给期望 face-local bounds 的 rasterizer。

[rasterizer:136–160](../../src/main/java/com/yucareux/tellus/worldgen/global/CubeNetFeatureRasterizer.java#L136-L160)明确以投影后的 faceX/faceZ 栅格化；[adapter:46–70](../../src/main/java/com/yucareux/tellus/worldgen/global/CubeNetAddressAdapter.java#L46-L70)明确从引擎 chunk 反解面内 chunk address。这不是同一坐标系。

本轮使用仓库实际 CubeNetLayout 源码编译最小 Java probe，按生产裁剪算式复现：`faceSize=16384`，face-local chunk=(100,100)，block origin=(1600,1600)，将该 chunk 的真实 planar 包围盒作为结构框；六个面均得到空交集，在读取建筑/道路几何前返回 false。

例：POS_Y 面真实 planar box 为 `1600,-14784 .. 1615,-14769`，错误裁剪为 `25504,25504 .. 1615,-14769`。

这是实际布局和裁剪算式的确定性复现，不是已运行的 Minecraft 结构复现。实际生成功能影响仍须用含结构与 OSM footprint 的回归样本验证；当前已有材料足以否定“碰撞过滤仅因有方法就已经完整等价”的判断。

修正应先统一结构框→面内坐标，处理旋转/反向轴与跨面边界，再做 footprint overlap；仅删除多余的 `*16` 并不足以解决所有坐标混用。地标 intersecting 的 Earth 投影坐标也应在同一专题核对。

### A2 / 功能缺口：海底神殿完整适配尚未迁入 Cube-Net

[Earth:12271–12363](../../src/main/java/com/yucareux/tellus/worldgen/EarthChunkGenerator.java#L12271-L12363)具有深度、海洋比例、核心区水深检查和 sea-level offset 调整；Cube `createStructures` 只执行 surface filter、OSM collisions、underground retarget，见 [Cube:1777–1786](../../mc1201/src/main/java/com/yucareux/tellus/worldgen/CubeNetChunkGenerator.java#L1777-L1786)。

[结构 parity 文档:9–17](../cube-net-structure-parity.md#L9-L17)也明确 monument deferred。启用结构开关不等于迁入了该结构的原生地形适配规则。

通过读取本地 upstream/main 对象确认，这些 Earth 方法本来就存在于原生基线（upstream EarthChunkGenerator 的 adjustOceanMonumentStarts 从第11903行开始），不是我们重构后新增的验收条件。

### A3 / 行为差异：地下结构深度与重定位条件简化

[Cube:1794–1835](../../mc1201/src/main/java/com/yucareux/tellus/worldgen/CubeNetChunkGenerator.java#L1794-L1835)统一用 fixed target depth 42/30/92/72，并按采样最低地表 fit。

[Earth:12063–12268](../../src/main/java/com/yucareux/tellus/worldgen/EarthChunkGenerator.java#L12063-L12268)具有 ocean/tooShallow/terrain-shell 条件，以及由 world seed/坐标决定的随机深度，例如 stronghold `42 + random(17)`、ancient city `92 + random(29)`、trial chambers `72 + random(25)`，矿井有 normal/mesa 不同规则。两者有基础放置能力，但不是规则完全等价。

### A4 / 已修正：bare/BWh 与地理邻域坐标

历史清单的 Earth=`minecraft:desert`、Cube=`minecraft:stony_peaks` 来自 Cube 将球面 noise 坐标传给期望 Earth 投影 block 坐标的 RESOLVE provider：阿拉伯采样点误查到南极 Rock and Ice 区。当前改为正确地理坐标，并保持各版本原生分类次序：1.20.1 使用 ESA/Köppen，1.21.1 provider 优先。未修改 Earth 原生分类来迁就测试。

[新 exporter](../../mc1201/src/test/java/com/yucareux/tellus/worldgen/TellusParityManifestExportTest.java)要求实际生成的 7/0；历史 7/1 清单原样保留，重放需显式使用 `--biome-baseline historical-v1`。同时修正原先把球面 noise 当 Cube planar 查询的雪/峡谷邻域；跨日期变更线的地理邻居正常 wrap，未知高程仍为 NaN。详见[旅行修正记录](../validation/cube-net-travel-parity-20261110/README.md)。

### A5 / 范围差异：Sphere-Face 不等于完整 Earth 或完整 Cube-Net

[Sphere:386–399](../../mc1201/src/main/java/com/yucareux/tellus/worldgen/SphereFaceChunkGenerator.java#L386-L399)中的 carvers/decoration 为记录空阶段，spawnOriginalMobs/createStructures/createReferences 也为空；六面 datapack 使用 fixed plains，见 [sphere 工具:40–48](../../tools/create_sphere_datapack.py#L40-L48)。

若其产品定位仍是基础球面实验/地形基线，这是保留的设计；若将它承诺为完整 Tellus 球面世界，上述项目就是尚未迁移的功能。应与主推 Cube-Net 路径分别验收。

### A6 / 设置覆盖：已有扩展，但不是完整 EarthGeneratorSettings

不能沿用旧文档声称 Cube 的出生经纬度或陆地/海底高度倍率仍未接入：[Cube settings:89–93](../../src/main/java/com/yucareux/tellus/worldgen/CubeNetBiomeSource.java#L89-L93)、[Codec:194–198](../../src/main/java/com/yucareux/tellus/worldgen/CubeNetBiomeSource.java#L194-L198)均已有字段，生成器读取并应用；[initial spawn mixin:24–34](../../shared/pre-26/src/main/java/com/yucareux/tellus/mixin/MinecraftServerInitialSpawnMixin.java#L24-L34)实际应用 Cube spawn。

仍应逐项区分：

- `customTrees`：已接入实际生成选择：true 使用 canopy/procedural；false 使用原生 biome configured tree features 与 5-block placement grid。canonical/apron ownership 保持原设计。
- `hugeRedMushrooms`：已接入与 Earth 相同的原生树 feature 列表过滤，包括包含红蘑菇的 composite selector；不是关闭全部蘑菇的全局开关。
- `demSelection`：**误判已纠正**。当前 Earth 本身强制归一 Mapterhorn/OpenWaters；Cube 使用相同实际源家族。本轮新增回归验证选择归一、陆地覆盖与海底来源、未知保持，不添加假选择 UI/Codec/API。
- `climateBasedBuiltUpTerrain`：已接入实际 built-up cover 决策与 Codec；true 按区域 biome 选择对应地表 cover，false 保留原有 visual cover。默认与当前 Earth 一致为 false，未强行改变旧世界 built-up 默认。
- Earth 的 shell/自动高度/LOD/Voxy/实时等能力需要按具体 capability/原生 manager 接入逐项判定；统一 view 中存在字段，不等于所有生成器支持该能力。

### A7 / 已修正：球面 Macrostrat 默认跟随原生

[MacrostratGeologySource](../../src/main/java/com/yucareux/tellus/world/data/geology/MacrostratGeologySource.java)默认启用；[SphericalGeologySource](../../src/main/java/com/yucareux/tellus/world/data/geology/SphericalGeologySource.java)现在也默认启用 overlay，同时服从全局 `tellus.macrostrat.enabled=false` 与球面单独 `tellus.sphericalGeology.macrostrat=false`。未知、无覆盖、过粗分辨率仍回退 GLiM；cache-only 继续使用原生本地读取策略，不发网络请求。

这是本 fork 当前 Earth/球面默认差异的修正；没有改变 GLiM/Macrostrat reader、原生 Earth default 或球面地理 identity。非 cache-only 冷缓存可能新增原生 Macrostrat tile 请求，沿用现有 timeout/concurrency/backoff；可用上述球面 flag 在重启后恢复旧 overlay 默认。

### A8 / 输入与性能：有限真实样本不证明全场景等价

真实 A/B 固定一个 Cube chunk/version，要求相同 settled detail inputs：115 buildings、79 roads、8 water features、0 road areas。初始 mc1211 cold-input 对照曾差 448 个建筑方块，失败证据已保留，后续一致输入后通过；见 [生产验收:26–43](../validation/unified-native-production/README.md#L26-L43)。

这支持“相同实际输入下新旧链路等价”，不支持“任意冷缓存/网络时间线下世界所有结果都相同”。其观测耗时也不是性能等价：旧约14–15秒、新约33–35秒，非受控 benchmark，需独立性能实验后定性。

## 6. 版本/运行体验覆盖

| 目标 | 自定义生成器/体验现状 |
|---|---|
| Fabric 1.20.1 | Cube/Sphere Codec、Cube/Sphere runtime、完整 apron edit/sync 和客户端 seam 实现最完整；仍保留未完成客户端验收项 |
| Fabric 1.21.1 | Cube/Sphere server generator Codec 与统一生产默认有真实验收；Cube server networking/runtime 已接；缺完整 apron edit lifecycle、client Cube payload 消费/seam renderer，且未见 SphereFaceRuntime 注册 |
| Forge 1.20.1 / NeoForge 1.21.1 | 入口仍只注册 Earth；共享代码存在不等于 Cube/Sphere 产品入口可用 |
| Fabric/NeoForge 1.21.11、26.2、26.3 | 原生项目构建目标存在，但自定义 Cube/Sphere 注册/runtime 没有相同覆盖；不属于本轮双版本迁移验收 |

证据：[mc1201 entry:34–67](../../mc1201/src/main/java/com/yucareux/tellus/Tellus.java#L34-L67)、[mc1211 entry:42–84](../../mc1211/src/main/java/com/yucareux/tellus/Tellus.java#L42-L84)、[mc1211 client](../../mc1211/src/client/java/com/yucareux/tellus/TellusClient.java)、[mc1211 apron helper:9–35](../../shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/CubeNetApronChunkSync.java#L9-L35)。特别是 1211 `readyForStaging` 目前直接返回 true，不能将它当作完整编辑覆盖层就绪证明。

Cube [datapack 工具](../../tools/create_cube_net_datapack.py)已新增 `--minecraft-version 1.20.1|1.21.1`（默认兼容 1.20.1），分别写入 pack_format 15/48，并输出三个旅行设置。Sphere 工具仍固定基础模式的原 metadata，不在本轮产品主线修正范围。

客户端 [现有验收矩阵:41–53](../validation/cube-net-acceptance-matrix-20261106/README.md#L41-L53)仍记录 M4 GeoTP、M8 shader、M10 DH direct 尚待验收，M11 zero-hold open；这些记录本轮没有重新执行。

## 7. 本轮实际执行与验证范围

1. Git root/branch/remotes/local refs/merge-base/diff/status 只读核验。
2. `python -B tools/check_cube_net_acceptance.py`：12/12 静态检查通过；不代表渲染/移动/多人体验通过。
3. 对 [最新默认 manifest](../validation/native-runtime-default/validation.json)重新计算 SHA-256：22/22 源码/测试/控制器条目、66/66 留存文件条目、4/4 retained gate 文件条目匹配。不是对仓库每个源码文件做全量绑定声明。
4. 重新执行严格离线 comparator，读取 Round59 留存的明确原生全列清单：13,824 对13,824，列差异0；七个原始 biome cases，保留 bare/BWh 一个已知差异；开启 full block digests 和 original case set 校验。**本轮是清单重放，不是重新生成 Java 样本。**
5. `python -B -m unittest discover -s tools -p test_tellus_parity_compare.py -v`：7/7 通过。
6. 用仓库实际 CubeNetLayout 编译临时 Java probe，生产裁剪算式在6/6面复现空窗口。**非实机结构生成测试，未修改生产代码。**
7. 554/554（mc1201）、545/545（mc1211）、14 controller tests 及真实 server PASS 是已有归档验收，本轮核验其原始材料/hash，未重新执行全部测试。

严格重放命令：

```powershell
python -B tools/tellus_parity_compare.py --columns-left docs/validation/round59-production-integration/manifests/cube-net.jsonl --columns-right docs/validation/round59-production-integration/manifests/sphere-face.jsonl --biome docs/validation/round59-production-integration/manifests/earth-cube-biome.jsonl --require-block-digests --require-original-biome-cases --biome-baseline historical-v1 --json
```

## 8. 后续主线与验收次序

1. **先收敛原生 Earth→Cube 功能矩阵**：功能、开关/default、采样坐标、数据可用性、stage、owner、版本、验证级别逐项列明。Sphere 基础模式单列，避免混作同一产品。
2. **优先修复 A1 结构碰撞坐标错误**：六面、旋转/负坐标、结构与建筑/道路/road area/地标交集、跨 seam、cache miss；先确定面内 bounds 与结构覆盖，再接实机结构样本。
3. **补齐或显式保留结构规则差异**：海底神殿、地下随机深度及 retarget 条件；不能通过“已有 add_* 开关”宣布完成。
4. **设置对照与数据默认值**：customTrees、huge mushroom、DEM 选择、climate toggle、Macrostrat overlay；保留已经实现的 spawn/height scale，纠正文档误报，避免重复开发。
5. **按产品目标安排1211体验补齐**：client receivers/状态机、apron edit index/router/block entity sync、Sphere runtime；其他 loader 另列，不扩写成已完成。
6. 后续生成器改动继续复用现有严格 parity 与 native save/restart/rollback 门；把新增功能缺口的实际验收与重构等价门分开记录，不重复制造外围契约来替代功能迁移。

## 9. 文档时序纠偏

当前默认接入的权威是 [最新默认验收](../validation/native-runtime-default/README.md)与 [deferred status 顶部](deferred-workstreams-status.md#L3-L9)及其对应源码。较早 validation README 的“legacy default”、generic runtime manager 的默认 disabled、旧迁移计划中的待办，是阶段历史或另一显式 API 的语义。

[旧生成器审计:126–128](../validation/cube-net-tellus-generation-audit-20261103/README.md#L126-L128)关于跨面外挑几何未迁移的表述也已被 [生成器当前说明:248–254](../cube-net-generator.md#L248-L254)描述的 seam replay 更新；应按实际源码追踪，而非把所有历史剩余问题拼成当前待办。反向地，“完整机制接入”也不能覆盖本文指出的结构、设置和版本缺口。
