# Cube-Net structure parity（2026-11-06）

本文逐项记录 Cube-Net 与原生 Earth 结构行为的对照。EarthChunkGenerator 保持不变；实现位于 `mc1201` 与 `shared/mc1211` 的 CubeNetChunkGenerator，公共采样谓词位于 `src/main`。

| 缺口 | Cube-Net 实现 | 状态/限制 |
| --- | --- | --- |
| 1 村庄坡度与水域过滤 | `filterCubeNetSurfaceStructureStarts` 使用 canonical `surfaceDataAtPlanar`，按 4-block 采样、最大高差 6，并按 8-block 水域采样。 | 已实现；无实机结构截图。 |
| 2 林地府邸坡度 | 同一过滤器按 6-block 采样、最大高差 8，识别 `mansion` 与 `woodland_mansion`。 | 已实现；无实机结构截图。 |
| 3 要塞垂直适配 | `retargetCubeNetUndergroundStarts` 计算最小地表、`verticalPlacementBounds`，目标深度 42；放不下作废，否则 piece 整体 Y 偏移。 | 已实现安全 fit/offset；随机深度和完整 Earth 强随机策略未复制。 |
| 4 废弃矿井重定位 | 识别 `mineshaft*`，使用 clearance/depth（mesa 保留较浅 clearance），放不下作废并整体平移。 | 已实现基础安全偏移；未复制 Earth 的完整 mesa 深度随机细节。 |
| 5 远古城市重定位 | 识别 `ancient_city`，目标深度 92，使用地下保护 bounds。 | 已实现基础安全偏移；未实机验证结构内部生成。 |
| 6 试炼密室重定位 | 识别 `trial_chambers`，目标深度 72，使用地下保护 bounds。 | 已实现基础安全偏移；未实机验证结构内部生成。 |
| 7 海底神殿 | Cube-Net 未复制 Earth 的水深/海洋比例与海平面偏移调整。 | **deferred**：需要水域数据与完整 monument piece 边界的专门实机验证；本轮不伪造实现。 |
| 8 OSM 建筑/道路/地标碰撞 | 非阻塞 `sampleFeatures(..., OsmQueryMode.NON_BLOCKING)`；building/road/road-area 用 `CubeNetFeatureRasterizer` 投影到 canonical face，landmark 用 `TellusLandmarks.intersecting`。cache miss 且对应结果为空时跳过过滤。 | 已实现；沿用 Earth 的“cache miss 不阻塞、不误删”语义。 |
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

## 验证

- `.\gradlew.bat -PtellusTargets=mc1201,mc1211 :mc1201:compileJava :mc1211:compileJava --offline`：PASS。
- `.\gradlew.bat -PtellusTargets=mc1201,mc1211 :mc1201:test --tests "com.yucareux.tellus.worldgen.CubeNet*" --offline`：PASS。
- 新增 `CubeNetStructurePlacementTest`：纯逻辑坡度、水域及远边采样测试通过。
- 未启动 MC、未执行结构实机截图/录屏；因此海底神殿保持 deferred，其他实现项也不宣称视觉验收通过。

## 待实机验证

准备带结构的 datapack 世界，分别在 1.20.1 与 1.21.1 生成：村庄、府邸、废弃营地、要塞、矿井、远古城市、试炼密室、海底神殿。检查结构是否只在合法地表/水域出现、地下 piece 是否整体落在高度范围内、地图查询是否不触发未知结构加载，并保留客户端/服务端日志与结构坐标。
