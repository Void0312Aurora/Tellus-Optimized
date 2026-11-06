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

1. **BiomeSource/Codec**：1.21.1 `BiomeSource.codec()` 返回 `MapCodec`，共享 `TellusBiomeSourceBridge` 已提供 `MapCodec`；`CubeNetBiomeSource.CODEC` 在注册处取 `.codec()`。1.20.1 入口使用 `CubeNetBiomeSource.CODEC.codec()` 同样保持兼容。
2. **ChunkGenerator codec**：1.20.1 generator 直接实现 `Codec<? extends ChunkGenerator>`；1.21.1 应实现 `MapCodec<? extends ChunkGenerator>`。`shared/mc1211/TellusChunkGeneratorBridge` 已覆盖该入口。
3. **Structure state**：两版调用面不同。共享 bridge 保留 `createStructureState(...)` 内部实现；具体 generator 暴露 `createState(HolderLookup<StructureSet>, RandomState, long)` 以适配 1.21.1 调用，并在此注入结构开关过滤。
4. **Structure creation**：1.21.1 增加带 `ResourceKey<Level>` 的 `createStructures` 变体；共享 bridge 将旧签名转发到带 level key 的版本。Cube-Net 生成器已有对应重载，需保留，不要改结构开关语义。
5. **Chunk biome/noise methods**：1.20.1 与 1.21.1 的 `createBiomes`/`fillFromNoise` executor 包装方式由 bridge 分层；移植 generator 应复用 `shared/mc1211/TellusChunkGeneratorBridge` 的重载，不在两个目标复制主体。
6. **Registry**：1.21.1 入口用 `BuiltInRegistries.BIOME_SOURCE/CHUNK_GENERATOR` + `Registry.register`；这与当前 Earth 注册一致。Cube-Net biome source 的 MapCodec 需 `.codec()`，generator 的 1.21 codec 由 `MapCodec` bridge 提供。
7. **Payload**：当前 mc1211 已采用 `PayloadTypeRegistry`；Cube-Net transition payload 仍是 1.20.1 专用，暂不把它们带入服务端最小编译，避免提前耦合客户端状态机。
8. **Sodium/DH**：`gradle/tellus-fabric.gradle` 仅给 `mc1201` 加 Sodium `0.5.13` compileOnly；DH 是所有 Fabric 目标的 compileOnly API，但 1.21.1 客户端 mixin/内部渲染类型没有适配。服务端生成链不引用这些客户端类。

## 增量实施与验证记录

- 已核对目标源码目录：`mc1211/src/main/java` 当前只有 Fabric 入口；客户端仅有 `TellusClient` 与版本 compat，Cube-Net generator/runtime/mixin 均缺失。
- 已核对共享目录：Cube-Net 几何、BiomeSource、surface/caves/inland-water、preload、DH LOD、结构策略和地理坐标接口均在根 `src/main`。
- 基线：`./gradlew.bat :mc1211:compileJava --offline` **通过**（2m09s，`BUILD SUCCESSFUL`）。
- 下一步最小移植：将 `SphereTerrainMaterializer` 与 `SphereFaceChunkGenerator` 放入 `shared/mc1211` 并按 MapCodec/1.21 ChunkGenerator 合约修正；再放入 `CubeNetChunkGenerator`，加入两个 apron 纯 helper，最后仅在 `mc1211/Tellus.java` 注册 Cube-Net biome/generator。
- 后置阶段：完整 apron edit sync、Cube-Net transition payload/runtime、客户端 Sodium/DH/Sprint/mixin 与真实世界运行验证。

## 阻塞点与不变项

本评估没有发现阻止“服务端最小可编译”的 API 阻塞；主要代价是 generator 本身很大，且完整 runtime/client 路径不能在当前阶段一次移植。不得删除或回退前三个提交，也不改变结构开关、世界设置、预载和 datapack 命令语义。编译失败时保留当前半成品，并把具体 javac 首条错误、受影响类和下一适配层写入本文件。

## 编译命令

```powershell
.\gradlew.bat :mc1211:compileJava --offline
.\gradlew.bat :mc1201:compileJava --offline
```

不启动 Minecraft 客户端或服务器；不执行 `:build` 全量任务。
