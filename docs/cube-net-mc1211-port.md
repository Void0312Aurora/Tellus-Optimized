# Cube-Net → Minecraft 1.21.1（Fabric）移植评估

> 分支：`feat/cube-net-parity`。本评估在保留 `60760b04a`（结构开关）、`fed70dced`（世界设置）、`2ff2616f7`（预载 + datapack 命令）的前提下完成。仅进行源码/编译检查，不启动客户端或服务器。

## 结论（评估优先）

服务端最小路径可行，建议采用“目标版本共享层 + 1.21.1 入口注册”的增量方式，而不是复制整套客户端实现。当前 `:mc1211:compileJava --offline` 基线已通过（2m09s）。最小服务端生成链的直接缺口是 `CubeNetChunkGenerator`、`SphereFaceChunkGenerator`、`SphereTerrainMaterializer`，以及 Cube-Net generator 调用的 apron 几何纯函数；这些代码可以放入 `shared/mc1211`，并复用根 `src/main` 中的地理、采样、地表、洞穴、建筑和预载实现。客户端 Sodium/DH seam、Sprint/传送 mixin 和 Cube-Net payload/runtime 维持后续阶段，不应阻塞本阶段。

本阶段的可编译目标是“生成器类与 Codec 可被 1.21.1 注册/编译”，不宣称已经完成运行时世界创建、跨面移动、apron 编辑同步或客户端渲染。

## 缺口与工作量

| 类/模块 | mc1211 当前状态 | 依赖/API 差异 | 工作量 |
|---|---|---|---|
| `CubeNetBiomeSource` | 已在 `src/main`，随 `post-1201` bridge 编译 | 1.21.1 使用 `MapCodec`；现有 `TellusBiomeSourceBridge` 与 `CODEC` 已适配，入口注册需用 `.CODEC.codec()` | 小 |
| `CubeNetLayout`、`CubeNetAddressAdapter`、`CubeNetApronResolver`、`CubeNetRemoteProjection`、`CubeNetSeamTransform` | 已在 `src/main` | 纯几何/数据代码，未依赖 1.20.1 ChunkGenerator 签名 | 小 |
| `CubeNetSurfacePolicy`、`CubeNetInlandWaterResolver`、`CubeNetOceanSurfaceResolver`、caves、`TellusStructurePolicy`、`TellusGeodeticPosition` | 已在 `src/main` | 复用 `MinecraftVersionCompat`、`WorldgenApi`；目标层现有兼容方法可继续使用 | 小 |
| `CubeNetPreloadAdapter`、`CubeNetLodGenerator` | 已在 `src/main` | LOD/预载属于共享路径；DH API 是 compileOnly，1.21.1 没有 Sodium 运行时依赖 | 小（服务端）/大（客户端渲染） |
| `SphereFaceChunkGenerator` | 仅 `mc1201/src/main` | 需 `MapCodec`/Codec 适配，并让 `createBiomes`、`fillFromNoise` 等 1.21.1 ChunkGenerator 合约通过；直接依赖 `SphereTerrainMaterializer` | 中 |
| `SphereTerrainMaterializer` | 仅 `mc1201/src/main` | 主要是 ChunkAccess/Heightmap/共享 `EarthChunkGenerator.ChunkSectionWriter`，可作为 `shared/mc1211` 版本实现 | 小-中 |
| `CubeNetChunkGenerator` | 仅 `mc1201/src/main`（约 2,923 行） | 大量逻辑可直接复用；已有 `createState`、`createStructures(..., levelKey)`、`CubeNetBiomeSource.CODEC.codec()` 等 1.21 表面，但仍调用 `SphereFaceChunkGenerator`、`SphereTerrainMaterializer` 和 apron helper | 大 |
| `CubeNetApronChunkSync` | 仅 `mc1201/src/main`，含 Fabric 生命周期、Ticket、LevelChunk 回写 | 生成器只需要其中 `canonicalChunkPosition`/`rotation` 两个纯函数；完整 sync 依赖 `CubeNetApronEditIndex`、`CubeNetApronMutationRouter`、block entity sync，需另阶段适配 | 小（提取纯 helper）/大（完整同步） |
| `CubeNetRuntime`、`SphereFaceRuntime`、Teleport/Vehicle/Apron mutation | 仅 `mc1201` | 依赖 1.20.1 payload 回调、玩家移动与 chunk transfer；1.21.1 networking 应改为 `PayloadTypeRegistry` + `CustomPacketPayload` | 大 |
| `CubeNet*Payload`（transition/layout/boundary 等） | 仅 `mc1201` | 1.20.1 `ServerPlayNetworking.registerGlobalReceiver(payload, payload, responseSender)` 与 1.21.1 `PayloadTypeRegistry.playC2S/S2C().register(TYPE, CODEC)`/新 receiver context 不同 | 中-大 |
| 客户端 `CubeNet*Renderer`/Sodium mixin/Sprint | 仅 `mc1201/src/client` | `mc1211` 无 Sodium compileOnly 配置；Sodium 内部类和 DH seam API 均需按 1.21.1 版本重新探测，不能由服务端编译验证 | 大 |
| `Tellus.java` 1.21.1 Fabric 入口 | 已注册 Earth + 现有 payload | 需追加 `cube_net` biome source 与 chunk generator；Cube-Net runtime/payload 注册暂不接入，避免改变 T1-T3 既定语义 | 小 |

## 关键 API 对照

1. **BiomeSource/Codec**：1.21.1 `BuiltInRegistries.BIOME_SOURCE` 持有 `MapCodec<? extends BiomeSource>`，共享 `CubeNetBiomeSource.CODEC` 已是 `MapCodec`，入口直接注册它；调用 `.codec()` 会得到 `Codec` 并触发注册表泛型不匹配（已由编译验证）。1.20.1 的 `CubeNetBiomeSource.CODEC.codec()` 写法保持原样。
2. **ChunkGenerator codec**：1.20.1 generator 直接实现 `Codec<? extends ChunkGenerator>`；1.21.1 `BuiltInRegistries.CHUNK_GENERATOR` 持有 `MapCodec<? extends ChunkGenerator>`，`shared/mc1211/TellusChunkGeneratorBridge` 与 `CubeNetChunkGenerator.CODEC` 均为 `MapCodec`，入口直接注册。
3. **Structure state**：两版调用面不同。共享 bridge 保留 `createStructureState(...)` 内部实现；具体 generator 暴露 `createState(HolderLookup<StructureSet>, RandomState, long)` 以适配 1.21.1 调用，并在此注入结构开关过滤。
4. **Structure creation**：1.21.1 增加带 `ResourceKey<Level>` 的 `createStructures` 变体；共享 bridge 将旧签名转发到带 level key 的版本。Cube-Net 生成器已有对应重载，需保留，不要改结构开关语义。
5. **Chunk biome/noise methods**：1.20.1 与 1.21.1 的 `createBiomes`/`fillFromNoise` executor 包装方式由 bridge 分层；移植 generator 应复用 `shared/mc1211/TellusChunkGeneratorBridge` 的重载，不在两个目标复制主体。
6. **Registry**：1.21.1 入口用 `BuiltInRegistries.BIOME_SOURCE/CHUNK_GENERATOR` + `Registry.register`；这与当前 Earth 注册一致，且两个注册表都要求 `MapCodec`，因此 Cube-Net 的 biome source 与 chunk generator 均直接注册其 `CODEC`。
7. **Payload**：当前 mc1211 已采用 `PayloadTypeRegistry`；Cube-Net transition payload 仍是 1.20.1 专用，暂不把它们带入服务端最小编译，避免提前耦合客户端状态机。
8. **Sodium/DH**：`gradle/tellus-fabric.gradle` 仅给 `mc1201` 加 Sodium `0.5.13` compileOnly；DH 是所有 Fabric 目标的 compileOnly API，但 1.21.1 客户端 mixin/内部渲染类型没有适配。服务端生成链不引用这些客户端类。

## 增量实施与验证记录

- 已核对目标源码目录：`mc1211/src/main/java` 当前只有 Fabric 入口；客户端仅有 `TellusClient` 与版本 compat，Cube-Net generator/runtime/mixin 均缺失。
- 已核对共享目录：Cube-Net 几何、BiomeSource、surface/caves/inland-water、preload、DH LOD、结构策略和地理坐标接口均在根 `src/main`。
- 基线：`./gradlew.bat :mc1211:compileJava --offline` **通过**（2m09s，`BUILD SUCCESSFUL`）。
- 下一步最小移植：将 `SphereTerrainMaterializer` 与 `SphereFaceChunkGenerator` 放入 `shared/mc1211` 并按 MapCodec/1.21 ChunkGenerator 合约修正；再放入 `CubeNetChunkGenerator`，加入两个 apron 纯 helper，最后仅在 `mc1211/Tellus.java` 注册 Cube-Net biome/generator。
- 后置阶段：完整 apron edit sync、Cube-Net transition payload/runtime、客户端 Sodium/DH/Sprint/mixin 与真实世界运行验证。

## 阶段结论（本次落地）

- 已移植到 `shared/mc1211`：`SphereFaceChunkGenerator`、`SphereTerrainMaterializer`、`CubeNetChunkGenerator`、`CubeNetApronChunkSync`（仅生成器所需的纯 helper）与 `TellusChunkGeneratorBridge`，主体逻辑复用根 `src/main` 的地理、采样、地表、洞穴、建筑、预载与结构策略。
- 已在 `mc1211/Tellus.java` 注册 `tellus:cube_net` 的 biome source 与 chunk generator。
- 编译验证：`:mc1211:compileJava --offline` **BUILD SUCCESSFUL**；`:mc1201:compileJava --offline` 回归 **BUILD SUCCESSFUL**（up-to-date）。
- 仍未移植（后续阶段）：`CubeNetRuntime` 与 transition/layout/boundary payload、apron 编辑同步、传送/载具 mixin，以及客户端 Sodium/Iris/DH 接缝渲染。因此 mc1211 上目前是“可注册、可编译、可生成地形”的服务端最小路径，不是完整立方球体验。

## 阻塞点与不变项

剩余阻塞点集中在运行时与客户端层：1.20.1 的 payload/receiver 形态、apron 编辑索引与 block-entity 同步、玩家跨面状态机，以及 Sodium/DH 内部 API 的 1.21.1 适配；这些都不能由 `compileJava` 验证，需要各自的验收夹具。不得删除或回退前三个提交，也不改变结构开关、世界设置、预载和 datapack 命令语义。

## 编译命令

```powershell
.\gradlew.bat :mc1211:compileJava --offline
.\gradlew.bat :mc1201:compileJava --offline
```

不启动 Minecraft 客户端或服务器；不执行 `:build` 全量任务。
