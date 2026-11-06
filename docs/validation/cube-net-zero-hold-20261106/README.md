# Cube-Net zero-hold 诊断与门控（2026-11-06）

本记录针对 M11「逐帧零跳变（zero-hold）」。目标是把“切面帧间出现空档”的候选拆成可观察的生命周期事件，并让提交前的客户端数据条件可判定。本文不把静态编译或历史录屏当成当前 M11 通过证据。

## 候选定位

| 候选 | 代码观察 | 结论/处理 |
| --- | --- | --- |
| 目标 chart 的 3x3 mesh 未就绪 | Indigo 在 READY 前要求目标 `ViewArea` 的待编译 section 完成；Sodium 0.5.13 的 `CubeNetSodiumWorldRendererMixin` 用 `readyRadius=1` 检查目标中心 3x3 内所有垂直 section 的 `isBuilt()`。 | 保留既有 mesh 门控。另增加完整目标区域 packet 门控，避免 READY 早于 live payload。 |
| chart 切换前旧 chart 被释放 | `commitPrepared` 在同一个客户端主线程回调中先安装 live chunk payload，再调用 Indigo `tellus$installStagedView` 或 Sodium `tellus$commitStagedRenderer`；Sodium 还保留旧 manager 作为反向缓存。 | 没有发现可观测的 commit 前释放；旧 view/manager 的释放发生在新 owner 安装阶段。 |
| 相机/朝向只在 commit 帧重算 | commit 包先写入权威 pose 并同步 old pose，随后切 renderer、更新 `shadeQuarterTurns`、请求 Iris 历史清理，并调用 `CubeNetUnfoldClient.cameraMoved`。 | 原子主线程顺序已存在；需要实机帧证据确认相机和远景实现没有晚一帧。 |
| 光照/heightmap/区块包跨帧 | vanilla packet handler 已处理带光照 chunk；READY 前 `tick()` 等待 light queue 清空。commit 只把 block/biome/heightmap payload 写回 live cache，不重复回放完整 light packet。 | 新增 `packetsCoverArea`：目标 radius 内每个 chunk 都必须有已记住的 with-light packet，才发送 ChunksReceived/READY。 |
| Sodium / Indigo 差异 | Indigo 将预编译 `ViewArea` 替换进 LevelRenderer，并同步首轮 cull；Sodium 将预编译 RenderSectionManager promote，并在 commit 后继续构建 halo。 | 共享 packet 门控；保留两个 renderer 的既有 mesh/manager 门控，不改变后续 halo 行为。 |

## 可测量诊断

客户端 JVM 增加：

```text
-Dtellus.cubeNet.debugHold=true
```

默认值为 false，不输出额外的逐帧诊断。打开后，`CubeNetClientTransition` 在以下事件输出 `Cube-net zero-hold` 行：

- `phase=prepare`：收到 prepare，含 `prepareTick`、`nanoTime`、目标 chunk 数、light queue 状态。
- `phase=ready`：发送 READY，含从 prepare 到 ready 的 wall-clock 时长。
- `phase=commit`：开始安装目标 chart，含 ready→commit 时长。
- `first-render-frame`：`WorldRenderEvents.END` 首次回调，含 `frame=1`、commit→frame 的毫秒/tick、相机位置和 `chunkStatistics`。
- 随后最多 8 行 `committed frame=1..8`，可对比每帧的可见 chunk 统计、camera yaw/pitch、shade turns 和 unfold 状态。

判定时按同一 transition UUID 对齐事件：首个 `committed frame` 必须存在；`frame=1` 不得出现空/黑场或 0 个应见 section；帧序列不得出现跨面位置回退、相机翻转或目标地形消失。`commitToFrameTicks`/`commitToFrameMs` 是调度延迟测量，不等同于视觉 PASS；视觉 PASS 仍需要真实客户端录屏/逐帧截图。

## 复现步骤（需要实机）

1. 构建当前 `mc1201` 客户端，在测试客户端 JVM 参数加入 `-Dtellus.cubeNet.debugHold=true`。
2. 使用已有 `tellus:cube_net` datapack 测试世界；分别准备 Indigo 与 Sodium `0.5.13+mc1.20.1`，再分别测试正向、反向步行和冲刺。不要使用 `/tp` 代替移动跨 seam。
3. 录制原生分辨率视频，并保留客户端 `latest.log`、服务端日志、Git revision 和 SHA256。
4. 从每个 transition UUID 提取 prepare/ready/commit/first-render-frame 以及 frame 1–8；同步观察 marker、船/玩家、手持物、相机和光照。
5. 判定：任何 frame 1 空 terrain/黑帧、位置回退、相机翻转、跨一帧以上的目标 mesh 缺失均为 FAIL；只有双向、两 renderer、步行/冲刺都满足才可更新 M11 为 PASS。

## 已改代码

- `e39b4e206`：在 `CubeNetClientTransition` 加入 `tellus.cubeNet.debugHold` 门控的 prepare/ready/commit/first-render-frame 时间戳和 8 帧统计；默认路径不新增诊断帧日志。
- `b2f670daf`：READY 前用 `packetsCoverArea` 检查 transition radius 内全部 with-light chunk packet 已被 `receivedChunk` 记住；缺包时继续保持旧 chart，不发送 ChunksReceived/READY。这样 commit 的 live-cache promotion 不会因为 packet 记录缺失而出现空档。

## 验证状态

- `./gradlew.bat :mc1201:compileJava --offline`：PASS（2026-11-06，本地离线缓存）。
- `./gradlew.bat :mc1201:test --tests "com.yucareux.tellus.worldgen.CubeNet*" --offline`：待本轮执行。
- Minecraft 客户端未启动；没有新增实机录屏或逐帧截图，因此 M11 仍是 **open**。

## 仍待实机验证

需要实机执行上述步骤与判定标准，至少覆盖 Indigo、Sodium 0.5.13、正/反向、步行/冲刺；重点检查 `first-render-frame` 的 chunk statistics、相机 yaw/pitch、marker 连续性、光照是否跨帧跳变。没有这组证据前，不把本次 packet 门控标成视觉通过。
