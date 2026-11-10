# Cube-Net structure parity（2026-11-10更新）

本文逐项记录 Cube-Net 与原生 Earth 结构行为的对照。EarthChunkGenerator 保持不变；实现位于 `mc1201` 与 `shared/mc1211` 的 CubeNetChunkGenerator，公共采样谓词位于 `src/main`。

| 缺口 | Cube-Net 实现 | 状态/限制 |
| --- | --- | --- |
| 1 村庄坡度与水域过滤 | `filterCubeNetSurfaceStructureStarts` 使用 canonical `surfaceDataAtPlanar`，按 4-block 采样、最大高差 6，并按 8-block 水域采样。 | 已实现；无实机结构截图。 |
| 2 林地府邸坡度 | 同一过滤器按 6-block 采样、最大高差 8，识别 `mansion` 与 `woodland_mansion`。 | 已实现；无实机结构截图。 |
| 3 要塞垂直适配 | 共享 Earth 条件、`42+random(17)` 深度、salt/coord hash、完整 footprint envelope 与 fit/clamp；逐 native piece 整体移动。 | 规则/原生适配回归通过；未知地形保持原 start，不虚构高程。 |
| 4 废弃矿井重定位 | 普通矿井迁入 cover14、`30+random(21)`；mesa 按 Earth non-shell 条件保持原 start。 | 已补齐当前 Cube 的 non-shell 行为；未引入新的 terrain-shell 设置。 |
| 5 远古城市重定位 | 共享 Earth cover8、`92+random(29)` 与 salt/fit 行为。 | 规则/原生适配回归通过；自然内部物化验收单列。 |
| 6 试炼密室重定位 | 共享 Earth cover8、`72+random(25)` 与 salt/fit 行为；按实际版本是否注册覆盖。 | 1.21.1 原生适配回归通过；自然内部物化验收单列。 |
| 7 海底神殿 | 已迁入 margin/core/85%海洋/70%深水规则、effectiveSeaLevel-2 偏移和 bounds；Mixin识别公共 Tellus地理生成器。 | 从 deferred 更新为代码/回归已实现；真实服务器 Mixin 烟测与自然结构验收分开。 |
| 8 OSM 建筑/道路/地标碰撞 | 修复 `address.blockX/Z()*16` 双缩放和 planar/face-local 混用；六面分别 inclusive 取交；完整bbox的米制查询；地标转 Earth grid；保持非阻塞及已有部分结果可用。 | 六面×三类 footprint、旧算式复现、gap/cache/landmark 回归通过；自然结构过滤待专项验收。 |
| 9 废弃营地可用性 | 识别所有 `abandoned_camp*` 路径；结构集不存在时自然无 start，存在时逐 piece 检查水域与 `supportsRigidFloor`，不满足则作废。 | 已实现 1.20.1 结构存在性守卫。 |
| 10 `findNearestMapStructure` | `skipKnownStructures=true` 直接返回 null；其余查询走父类。 | 已实现探险地图保护；未启动 MC。 |
| 11 远古城市结构生群系查询 | CubeNetBiomeSource 提供嵌套 begin/end query depth；findNearestMapStructure 识别 ancient_city 查询后启用 probe，地下深度返回 deep_dark，避免完整表面/洞穴数据路径。 | 已实现批处理开关；probe 阈值是 Cube-Net 的纯几何近似，需实机结构定位验证。 |

## 两个生成器变体

- `mc1201/src/main/java/com/yucareux/tellus/worldgen/CubeNetChunkGenerator.java`
- `shared/mc1211/src/main/java/com/yucareux/tellus/worldgen/CubeNetChunkGenerator.java`

两者都包含 A/B/C/E 的过滤与门控；mc1211 的版本差异仅保留其自身 `ChunkGenerator` API 形状。

## 阶段提交

- `22f9a4d21`：村庄/府邸/废弃营地纯地形过滤及共享纯函数测试。
- `56c9acc85`：OSM 建筑、道路、道路区域碰撞过滤。
- `b66c569fd`：地标碰撞过滤补齐。
- `444bfb073`：要塞、矿井、远古城市、试炼密室 fit 检查与安全 Y 偏移。
- `43589556a`：mc1201 map lookup 保护与 ancient-city 查询批处理。
- `fad54a843`：mc1211 map lookup parity port。

## 2026-11-06历史验证

- `.\gradlew.bat -PtellusTargets=mc1201,mc1211 :mc1201:compileJava :mc1211:compileJava --offline`：PASS。
- `.\gradlew.bat -PtellusTargets=mc1201,mc1211 :mc1201:test --tests "com.yucareux.tellus.worldgen.CubeNet*" --offline`：PASS。
- 新增 `CubeNetStructurePlacementTest`：纯逻辑坡度、水域及远边采样测试通过。
- 未启动 MC、未执行结构实机截图/录屏；因此海底神殿保持 deferred，其他实现项也不宣称视觉验收通过。

## 2026-11-10补齐与验证

上节保留历史记录，不作为当前状态。当前双版本编译和扩展后的原迁移 cohort（含终审查询预算）：mc1201 **629/629**，mc1211 **620/620**，零失败/跳过。

本轮确实启动真实服务器检查神殿 root/child move、原生 reload rebuild、Cube低海平面分支，并生成/保存一个真实 GIS 新 FULL chunk；这是受控原生对象/Mixin烟测，非自然结构 starts 或客户端视觉验收。精确状态、原始报告和回退边界见[本轮验证](validation/cube-net-structure-fix-20261110/README.md)。

## 待实机验证

准备带结构的 datapack 世界，分别在 1.20.1 与 1.21.1 生成：村庄、府邸、废弃营地、要塞、矿井、远古城市、试炼密室、海底神殿。检查结构是否只在合法地表/水域出现、地下 piece 是否整体落在高度范围内、地图查询是否不触发未知结构加载，并保留客户端/服务端日志与结构坐标。
