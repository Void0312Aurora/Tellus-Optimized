# Cube-Net 旅行地形差异修正（2026-11-10）

## 范围与状态

本轮按用户的全球旅行工程口径：高程/地貌、地表环境、植被和实际使用组合。沿用已经通过的真实新区块生成、物化、原生保存、独立进程重启和回退结论，不把特定样本缺失改写成整条生成链未验收。这里增加的是**本轮新行为的回归和原生设置核对**。

本记录区分已经完成的双版本回归与当前真实进程证据；静态检查不替代运行结论。

## 已落地行为

| 项目 | 修正 | 边界 |
|---|---|---|
| 裸地/热沙漠 | 地理 provider 使用 Earth 投影坐标，不再把球面 noise 坐标误作经纬位置；阿拉伯不再误查南极 Rock and Ice。 | 保持 1.20.1 的 ESA/Köppen 和 1.21.1 的 provider 优先原生差别；未修改 Earth 基线。 |
| 雪山/峡谷邻域 | 使用采样点附近的 GeoPoint 高程，不再把 noise 当 Cube planar；日期变更线 wrap，未知保持 NaN。 | 不改已有球面噪声、洞穴域或海陆高程数据源。 |
| 气候注入 | 注入的 ClimateSampler 保持权威，默认生产气候才执行原生 coherent arid smoothing。 | 解决受控输入被全局缓存二次覆盖；真实默认源保留平滑规则。 |
| 树木设置 | `custom_trees=true` 使用既有 canopy/procedural，false 使用原生 biome configured tree features 与 5 格网格；`huge_red_mushrooms` 使用 Earth 的 composite-feature 筛选语义。 | 保留 canonical owner；apron 不额外种树。红树原生保留路径不再叠加自定义树；feature-empty biome 不凭空添树。 |
| 建成区地面 | `climate_based_built_up_terrain` 实际接入 surface cover 决策；false 保留旧 visual cover，true 按区域 biome 选 cover。 | 默认 false 与当前 Earth 一致；显式启用后未来 built-up 地表可产生预期改变。 |
| 持久设置 | 三个 bool 经 Settings/Codec、双版本生成器和 typed settings view 接通。 | 缺省分别 true/false/false；保留原 40 参数构造和全部旧字段；新 canonical record 为 43 参数。 |
| 地质默认 | 球面 Macrostrat 默认跟随启用策略，服从 global off 与 spherical off；GLiM 兜底不变。 | 非 cache-only 冷缓存可能新增现有原生 tile 请求；未知/粗尺度不造 map unit。 |
| Datapack | Cube 工具增加三项 CLI 与 1.20.1/1.21.1 metadata（15/48）。 | 默认仍为 1.20.1；同时修正已有 output 非空保护的不可达分支，避免重复调用覆盖旧文件。 |

### DEM 审计更正

此前“Cube 缺少 Earth 的可选 DEM 能力”是误判。当前 [EarthGeneratorSettings](../../../src/main/java/com/yucareux/tellus/worldgen/EarthGeneratorSettings.java) 的 `DemSelection` 构造、自动、手动、未知 ID 均强制归一到 Mapterhorn（兼容 ID `terrarium`），海底使用 OpenWaters。[新增回归](../../../src/test/java/com/yucareux/tellus/world/data/elevation/TellusCanonicalDemSelectionTest.java)对实际 Earth/GeoPoint sampler 的既有缓存路由、陆地覆盖、海底来源和未知行为作验证。没有新增虚假的 DEM 开关/provider，也没有为此改变已验收的地形采样算法。

## 当前跨面旅行组合

- **Fabric 1.20.1**：完整 prepare/remote cache/with-light packets/light/Indigo 或 Sodium mesh/commit 链已存在。服务端和客户端等待相同半径完整方形 packets；没有发现移除 packet gate 的依据，本轮保留。M11 缺当前逐帧录屏证据，继续 open；不因此撤销已有服务端生成/保存验收。
- **Fabric 1.21.1**：服务端生成与 runtime 已接入，但客户端没有 Cube receivers/staging/render 闭环，apron edit lifecycle 也未完整移植。`readyForStaging=true` 是 stub 而非实际编辑就绪证明。这是真实代码缺口，当前走 vanilla teleport fallback，不能称为原子零 hold。不是加一行 receiver 就完成的修正；本轮没有冒充已完成完整客户端移植。
- packet 预算为 8/tick；view 8 全范围 361 chunks 理想发送下界约 2.3 秒，view 32 为 4489 chunks/约 28.1 秒。预算是明确约束，不是本轮已证实的 hold 根因。详见[既有 zero-hold 记录](../cube-net-zero-hold-20261106/README.md)与[验收矩阵](../cube-net-acceptance-matrix-20261106/README.md)。

## 本轮验证

| 检查 | 当前结果 | 原始证据 |
|---|---|---|
| Fabric 1.20.1 扩展回归 | **656/656**，108 类，失败/错误/跳过均 0。复用前轮629项并新增27项。 | [统计](final/mc1201/regression.json)、[日志](final/mc1201/regression.log)、[原始XML](final/mc1201/regression-xml.zip)。 |
| Fabric 1.21.1 扩展回归 | **647/647**，102 类，失败/错误/跳过均 0。复用前轮620项并新增27项。 | [统计](final/mc1211/regression.json)、[日志](final/mc1211/regression.log)、[原始XML](final/mc1211/regression-xml.zip)。 |
| 新导出严格列清单 | **13,824 列、42,246,144 block states，0 差异**；实际原生调用重导出，不是只重放历史文件。 | [当前v2门](final/strict-travel-parity.json)、[新原始清单](final/fresh-parity-manifests.zip)。 |
| 七个原始 Earth/Cube biome cases | **7/0**；bare/BWh 两侧均 desert。 | [当前v2门](final/strict-travel-parity.json)为1.20.1 actual manifest；1.21.1 区域 provider 由六面原生规则测试覆盖，不混称其也导出同一份清单。 |
| 历史材料保留 | 原 13,824/0、7/1 的旧清单显式 `historical-v1` 重放 PASS，文件未重写。 | [历史重放](final/historical-replay.json)，原始旧 bare 差异仍保留。 |
| Python 控制器 | 8 comparator、14 既有 native、3 datapack、2 新 smoke admission，共 **27/27**。 | [8项](final/parity-controller-tests.log)、[14项](final/native-controller-tests.log)、[3项](final/datapack-tests.log)、[2项](final/travel-controller-tests.log)；不冒充实际 native phases。 |
| 真实新设置生成/独立重启 | **两版均 PASS**，各两次串行真实服务器启动/退出；完整 payload、保存设置、stored-LevelStem 和 immutable source 检查通过。 | [1201报告](final/mc1201/travel-smoke.json)、[1211报告](final/mc1211/travel-smoke.json)。 |

新增回归覆盖全部8种设置组合、旧40参数构造、两个 typed views/实际生成器字段、Earth私有原始 built-up/tree-feature 选择器对照、5格 anchor 的 int overflow/signed shift、composite 红蘑菇过滤，以及真实 private `placeCubeNetTrees` 的模式 dispatch。最后一项使用受控 WorldGenLevel，在所选 tree implementation 第一次访问世界时终止，证明进入实际分支和 canonical anchor；没有据此声称树已在真实世界完整落块。地质测试以实际 native cache 中的解析 MVT fixture验证 overlay，并用耗尽网络 gate 的 cache-only miss 证明不进入请求路径；它不是下载到真实远程地质 tile 的验收。

首次656项运行有3个新增测试预期错误（不是生产改动回归）：森林/Cfb 硬编码为 forest，但当前1.20.1 CSV实际是 old_growth_birch_forest；建成区开关误认为默认 true，但当前 Earth.DEFAULT 为 false，Optional Codec因此省略默认 false。修正测试、工具默认和报告后，两版本重跑通过，[首轮原始失败材料](final/attempt-1-new-test-assumptions.zip)保留。更早一次超出 Windows batch 命令长度，尚未启动 Gradle；后改为临时 init-script 选择列表，且只统计本次时间窗口的新鲜 XML，未拿旧629/620 XML冒充通过。

新 [旅行烟测控制器](../../../tools/run_cube_net_travel_smoke.py)复用已有 immutable-copy admission 和 owned process 清理：仅在新临时世界副本中接通 GeoPoint biome 与三个 nondefault bool，生成此前不存在的真实 GIS FULL chunk并原生保存；移走**副本** dimension override 后，在独立 JVM 从已存 LevelStem 解码并验证区块/biome/NBT 和生成器 profile。历史源世界/cache保持字节不变。该检查不宣称视觉跨缝、全部树木放置或自然结构通过。

本轮副本源为2026-10-26已留存的真实 Mapterhorn/ESA GIS 世界/cache，seed=2025402140；新目标 chunk=(616262,-351597)。保存的 nondefault bool 为 `custom_trees=false`、`huge_red_mushrooms=true`、`climate_based_built_up_terrain=true`。1.21.1 的原生 DataFixer 升级仅发生于临时副本，历史 DataVersion3465 源未改。每版 generate 和 runtime_reload 的 block/biome/nativeNBT digest 各自完全相同；两版本间原生状态ID和NBT不同不作为错误比较。

| 版本 | generate→reload 原生NBT SHA256 | 进程与清理 |
|---|---|---|
| 1.20.1 | `03d693135d51f57cd89c04f751ef2af4417bd6cd15a5eaaecb3e9fd6ccc3dc9b` | launcher PID 33640→40524，各exit0；native stop清空runtime。 |
| 1.21.1 | `e33e4d87832cf58d5e6f54efbb39105a6fb8b42eeba95e00e797c7eac1836fb7` | launcher PID 5548→9948，各exit0；native stop清空runtime。 |

PID字段来自Gradle/wrapper，不冒称Minecraft JVM PID；独立native启动由串行完整退出及 owned JobObject 子树清理保证。原始日志的恢复→attach→prepareLevels→tick→probe→stop顺序被控制器断言。Java全源快照为1057文件，两个运行报告绑定同一快照；它不冒称涵盖Python或所有构建产物。[最终汇总](final/summary.json)另绑定实际JAR和独立证据文件SHA。

## 回退与使用影响

- 当前修改只影响未来生成区域，不自动重生成已有 FULL chunks。新旧相邻区块可能因已纠正 biome/地质 default/建成区 cover 形成环境差异；测试用全新副本或未生成区域。
- 球面 overlay 单独回退：`-Dtellus.sphericalGeology.macrostrat=false` 后重启。全局 `-Dtellus.macrostrat.enabled=false` 继续有效。
- 树木/built-up 可在 Cube biome source 的 `settings` 写入 `custom_trees`、`huge_red_mushrooms`、`climate_based_built_up_terrain`。显式 false 不被默认值覆盖。
- `-Dtellus.worldgen.pipeline=legacy` 只回退内部统一管线，不撤销这些共用分类、Codec、树木/地表规则或地质 default。源码回退必须使用本轮精确 before 材料，勿 reset 到 HEAD 破坏既有未提交工作。
- [源码前后与回退包](final/source-and-rollback.zip)、[差量补丁](final/source-delta.patch)、[源码SHA](final/source-sha256.json)、[隔离回退演练](final/rollback-check.json)绑定本轮20个源码/测试/工具/既有审计文档路径（本 README另存，不在包内）。真实工作树 reverse-check 通过；文本补丁在 **LF规范化的临时副本**完成语义回退，新文件移除也被验证；4个既有文件存在 CRLF/LF差别，不宣称补丁字节精确。ZIP `before/` 在临时副本逐文件恢复原字节并校验通过，保留本轮前所有未提交改动。
- 分支 `feat/cube-net-parity`、HEAD `1c35ccbb2441fd146ceb28ee35700f684dec8ed3`、真实 index SHA256 `bae6810ce206f7dc0defb2c8e7165750ec848381488d4584ad527ee6d592a18c` 保持。原生 EarthChunkGenerator、EarthGeneratorSettings、两版本EarthBiomeSource的字节SHA与前轮完成快照一致；未stage/commit/reset/switch。
