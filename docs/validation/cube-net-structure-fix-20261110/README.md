# Cube-Net 原生结构缺陷补齐（2026-11-10）

## 本轮范围

本轮直接补齐上轮审计的三类生产缺陷：结构/OSM/地标碰撞坐标，海底神殿地形适配，地下结构重定位条件与随机深度。目标是 Fabric mc1201 与 mc1211 的现有 Cube-Net 生成器，不扩称 Sphere 全功能化、客户端接缝移植或其他 loader 已完成。

新增代码接入的是既有 `createStructures` 后处理路径，没有建立另一套 world generator、改变 Codec/schema、重设用户分支或替换 Minecraft 的保存队列。

## 实作变化

### 1. 结构碰撞

- [共享碰撞边界](../../../src/main/java/com/yucareux/tellus/worldgen/CubeNetStructureCollision.java)将原生 BoundingBox 的 engine-planar **方块中心**精确反解为各 canonical face 的 inclusive cell bounds，覆盖六面平移、旋转、反向轴和负坐标。
- 删除双版本把 `address.blockX/Z()` 再乘16的错误；不再将 planar bbox 直接交给 face-local rasterizer。
- 检查完整结构框，而不是裁剪到起始区块附近96格；按世界比例换算查询半径（米），保留非阻塞 OSM 查询。
- 终审补充查询预算：非阻塞仅限制等待，不保证同步工作有界。[tile准入](../../../src/main/java/com/yucareux/tellus/world/data/osm/OsmGeoQueryBudget.java)使用源初始化后实际解析的zoom，在至多两个Mercator窗口上以long计数，building/roads每类完整范围最多256个tile访问（roads/area各一遍）；超过预算不生成TileKey、不查询前缀、不缩小范围，返回empty+cacheMiss，保持未知/原start。禁用类别不初始化源，碰撞查询不再附带未使用的water查询；地标独立继续检查。
- 建筑、道路、road area 分别使用实际已有 footprint。部分 cache miss 但已有可用 feature 时仍检查；空结果不补造几何。
- 地标通过 canonical cell 的真实 GeoPoint 转到 Earth grid，再执行既有 landmark intersection；地标不再被“建筑和道路缓存同时为空”短路掉。
- 对普通展开网相邻面分别取交；空隙/apron 不擅自作为 canonical owner 栅格化。

### 2. 地下结构

[纯原生规则](../../../src/main/java/com/yucareux/tellus/worldgen/TellusStructureAdaptationRules.java)逐项提取当前 Earth 算法的条件和数值；[原生结构适配器](../../../src/main/java/com/yucareux/tellus/worldgen/CubeNetNativeStructureAdapter.java)对实际 `StructureStart` / `StructurePiece` 应用结果。

- stronghold：保留 ocean/too-shallow/terrain-shell 条件；目标深度 `42 + seededRandom(17)`。
- 普通 mineshaft：底部保护、14格 top cover、`30 + seededRandom(21)`。
- mesa mineshaft：Cube 没有 terrain-shell 开关，按 Earth 的 non-shell 条件保持原 start，不再强行使用普通矿井固定深度。
- ancient city：8格 cover 判定、`92 + seededRandom(29)`。
- trial chambers（原生版本实际注册时）：8格 cover 判定、`72 + seededRandom(25)`。
- 盐、坐标 hash 的 int overflow、signed shift、Java Random 深度语义及按序采样保持 Earth 原算法。随机坐标由 Cube canonical GeoPoint 转为 Earth grid，采样 footprint 仍保持实际 engine 坐标，不混用。
- 保留 BoundingBox upper-middle center、两条 far footprint edge、5格底部保护及世界上下界。
- 所有采样和决策在移动前完成。缺失坐标/未知高程不补作 minY 干地，保留原 start 和全部 piece 不动。

### 3. 海底神殿

- 迁入 Earth 的 margin8/step8/coreInset8、85% ocean、70% depth>=12、核心 `max(19, ySpan-4)` 深度准入。
- 通过球面真实水体类别区分 ocean/inland；使用当前有效 height scale/sea/profile 对地形与水面高度归一化。
- 根及逐 piece 按 `effectiveSeaLevel - 2` 调整，保留 world bounds clamp；开关 water=false 时仍遵循 Earth 既有的跳过水深检查但执行高度偏移行为。
- [MonumentBuilding Mixin](../../../src/main/java/com/yucareux/tellus/mixin/OceanMonumentBuildingMixin.java)由仅识别 Earth 改为公共 `TellusGeodeticPosition`，让 Cube 同样使用低于原版最小值的有效海平面；保留现有 root→child move 和 reload rebuild 修正。

## 验证层级

### 当前代码/引擎回归

- [碰撞测试](../../../src/test/java/com/yucareux/tellus/worldgen/CubeNetStructureCollisionTest.java)：反射调用各目标版本的真实 generator collision adapter；六面×建筑/道路/road area 的相交与不相交；复现旧算式的空裁剪，cache miss/partial results、地标坐标、边界/gap 和 query 单位。
- [规则测试](../../../src/test/java/com/yucareux/tellus/worldgen/TellusStructureAdaptationRulesTest.java)：19项测试，使用独立旧 Earth oracle 与 Java Random 对照；保留调用 trace、深度、条件、fit/ratio/core/far-edge/vertical 边界。
- [原生适配集成](../../../src/test/java/com/yucareux/tellus/worldgen/CubeNetNativeStructureAdapterTest.java)：调用真实 generator 后处理，使用真实 chunk/start/piece API 核对每个 piece 偏移、references、KEEP identity、海水/浅水/干地准入、37/88非默认sea、未知高程不变；trial按目标版本注册表实际存在与否覆盖。
- [查询预算测试](../../../src/test/java/com/yucareux/tellus/world/data/osm/OsmGeoQueryBudgetTest.java)：8项，含实际两源小范围TileKey枚举对照、date-line合计、全世界zoom16/20溢出、极区与粗比例cap；碰撞集成测试另验证真实源整世界zoom20不排队/不填cache、未知仍可命中地标、disabled/null sources不查询。
- 重跑原 native-runtime-default 的完整选定迁移 cohort，再附加结构类；精确命令、逐类计数和原始 XML 另存本目录，避免用历史结果代替本轮运行。

| 本轮实跑 | mc1201 / Fabric 1.20.1 | mc1211 / Fabric 1.21.1 |
| --- | --- | --- |
| 最终迁移及结构回归（含查询预算） | 104类、629/629，零失败/跳过 | 98类、620/620，零失败/跳过 |
| `assemble`（含 remapJar / remapSourcesJar） | PASS | PASS |
| 实际神殿 Mixin 烟测 | PASS；31初始children / 32原生重建children，均整体Y+17 | PASS；31 / 32，均整体Y+17 |
| 低海平面 redirect | 实际 Cube generator，37/63→37 | 实际 Cube generator，37/63→37 |
| 新 FULL chunk 原生保存 | 完整snapshot与磁盘NBT一致 | 完整snapshot与磁盘NBT一致 |
| 原世界/cache/Java源码 | 实跑前后完整manifest不变 | 实跑前后完整manifest不变 |

原生版本重建算法与最初构造器的房间图不要求相同；烟测分别对照同一原生重建算法的未移动与移动对象，因此31/32不是丢失/重复piece的判据。本轮重新导出的严格列清单13,824/0差异，七个原始biome cases/一个既有bare/BWh差异；Python隔离控制器契约14/14通过。

### 实际服务器 Mixin 烟测

[可复跑控制器](../../../tools/run_native_structure_smoke.py)复用现有只读源 admission、fresh copy、owned process、timeout、native generation/save 与源 world/cache/code manifests。实际启动 Minecraft，读取已被 Mixin 变换的 MonumentBuilding：

1. 用受控原生对象核对 root 和所有 child room 的17格精确位移；
2. 调用原生 `regeneratePiecesAfterLoad`，核对重建 root/room Y 相对未移动对照保持17格位移；
3. 反射实际变换后的 sea redirect，使用真实 Cube generator 验证37/63分支返回37；
4. 周边既有 generate probe 在一次性世界副本生成/保存一个此前不存在的真实 GIS FULL chunk，并核对完整原生 NBT。

该证据**是实际服务器里的受控原生对象/Mixin 烟测，不是自然结构生成验收**。probe对象本身没有放置方块，也没有自然建立 start；外围 generate/save 的写入仅限 disposable copy。

历史 GIS 源存档的 `WorldGenSettings.generate_features=0`，且 Cube biome source 固定 plains。本轮报告明确记录实际 world structure 总开关与 `naturalStructureAcceptance=false`；不把0个自然结构当修复成功，也不通过 `/place structure` 冒充自然过滤路径。

## 行为变化与未覆盖

- 修复只影响今后生成的结构 starts，不自动重生成或移动用户已有 FULL chunks。
- 新区块中，过去漏掉的 OSM 冲突现在可能被过滤，地下结构 Y/是否重定位、神殿准入与 Y 会与旧简化 Cube 产生**预期差异**。因此不能要求“所有包含结构的新区块与旧缺陷实现仍逐字节相同”。
- 基础 Cube/Sphere 13,824列、42,246,144方块状态的既有 golden baseline 继续保持；七个 biome cases 中 bare/BWh 已知差异保留，本轮没有顺带修改 biome。
- 完整自然结构准入、整座神殿物化、保存后独立进程重启的房间与方块检查仍单列为后续实机验收。共享规则和实际原生适配测试不替代它。
- 1.21.1 apron编辑/客户端缺口、Sphere空阶段、设置全覆盖和其他loader不纳入本轮完成声明。

## 回退

本轮两版Cube和新增查询接线涉及的源文件，修改前字节位于系统临时目录 `tellus-structure-fix-20261110-1942/before`。Mixin和原probe的before版本由已存在的 `recovery/tellus-native-runtime-default` 恢复引用提取，标为checkpoint-blob来源，不冒称采集过这两个文件最初工作树的字节/行尾。before SHA256和来源随最终材料保存，真实分支/HEAD/index保持原状。

新规则是 generation bugfix，UNIFIED 与 LEGACY 都通过同一 `createStructures` 后处理生效。`-Dtellus.worldgen.pipeline=legacy` 只回退统一链，并不会撤销本次结构修复；撤销本次修复需恢复本轮精确源码副本并重建。已保存地形不作隐式回滚。

旧 [默认验收](../native-runtime-default/README.md)及其哈希清单保留为原检查点证据，不改写成当前源码哈希。本轮新的源码清单、回归和烟测结果应以同目录 validation 记录为准。

精确的[最终源码及回退ZIP](final/source-and-rollback.zip)、[最终增量补丁](final/source-delta.patch)和[最终前后源码SHA256](final/source-sha256.json)在final目录。`git apply --reverse --check`只作检查，未执行回退；需要回退时先核对SHA及并发改动，仅操作本轮16个Java路径，不使用整棵工作树reset。临时目录之外同样保留回退字节，不依赖临时目录永久存在。

隔离副本实际演练发现Git反向补丁会对Mixin/probe产生LF→CRLF转换：规范化行尾后文本完全相同，但逐字节断言首次失败。随后分别验证“补丁语义反向正确”和“按ZIP before字节精确恢复”通过，并在[最终汇总](final/summary.json)的`patchEOLConversions`中保留两条路径。真正的字节精确恢复以ZIP before及SHA256为准，不以Git自动行尾转换后的文件冒充。演练仅作用于独立临时目录，未回退真实工作树。

## 原始材料入口

- [最终汇总](final/summary.json)、[最终双版本构建日志](final/assemble.log)、[控制器契约日志](final/controller-tests.log)。
- [mc1201最终逐类回归](final/mc1201/regression.json)、[XML ZIP](final/mc1201/regression-xml.zip)、[实机报告](final/mc1201/structure-smoke.json)、[原生generate报告](final/mc1201/generate.json)、[实机日志](final/mc1201/generate.server.log)。
- [mc1211最终逐类回归](final/mc1211/regression.json)、[XML ZIP](final/mc1211/regression-xml.zip)、[实机报告](final/mc1211/structure-smoke.json)、[原生generate报告](final/mc1211/generate.json)、[实机日志](final/mc1211/generate.server.log)。
- [最终新导出parity清单](final/fresh-parity-manifests.zip)、[严格比较结果](final/strict-parity.json)、[构建产物摘要](final/artifact-sha256.json)。
- [预算补充前第一轮原始材料](attempt-1-before-query-budget.zip)及本目录顶层原始报告是历史attempt1（615/606）；最终材料位于final，不用第一轮Java哈希替代最终源码验收。
- [独立终审及P2关闭记录](review.md)。
