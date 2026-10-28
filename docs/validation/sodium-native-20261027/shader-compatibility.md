# 光影与 Sodium 原生 seam 兼容性验证（2026-10-27）

测试实例固定为 Minecraft 1.20.1、Tellus 0.8.4、Sodium 0.5.13、Iris 1.7.6、Indium 1.0.36。使用独立 Prism 数据目录和 `:104` Xvfb，OpenGL 实际渲染器是 Mesa llvmpipe（OpenGL 4.5）。因此本报告验证异常、资源重载和 seam 控制流，不代表 RTX 驱动上的性能或全部 shader 稳定性。

光影包从 Complementary Development 的官方 GitHub release 获取：<https://github.com/ComplementaryDevelopment/ComplementaryReimagined/releases>。

Iris 版本核验使用 Iris 官方 Modrinth 项目的 1.20.1 版本列表：<https://modrinth.com/mod/iris/versions?g=1.20.1>。列表中 `1.7.6+1.20.1` 是当前可用的最高 1.20.1 构建；本机 JAR 与该构建的 SHA-256 为 `9eb15e563e0c9ae6eff15b7863f8432dd290cf9f724c7373e9b15b14aa829ed5`。

## 结果

| 光影包 | SHA-256 | Iris 启动 | seam 测试 | 异常结论 |
|---|---|---|---|---|
| `ComplementaryReimagined_r5.5.1.zip` | `20897248a5fe91b33bf7fd4b75959c30aa2003df195297979a410ee37d3bb62f` | 已加载 | 正向、反向 native promotion 均完成；F3+A、F3+T 完成 | Iris 1.7.6 在创建 Tellus 维度管线时抛出 `RuntimeException: Unknown variable: BIOME_PALE_GARDEN`，Iris 随后继续渲染；这是 Iris 1.7.6 与较新光影包的 uniform 兼容问题，不是 Tellus seam 代码抛出的异常 |
| `ComplementaryReimagined_r5.2.2.zip` | `3b0cd5c36afebdcd74521a447ca5f2b0d6f9c1c8c8416a9f20d9bedc271a8e1c` | 已加载 | 正向 native promotion 完成；F3+T 完成 | 未观察到 shader pipeline 的 `Unknown variable` 或其他 Java 异常；仍有 llvmpipe 的 GLSL 未初始化警告 |

两次包测试均出现的 401、profile key pair、Distant Horizons 类加载警告来自离线账户或测试实例没有安装 DH，不属于 Tellus 渲染路径。光影实际生效的证据是 Iris 的 `Using shaderpack` 日志以及保存的渲染截图。

## 最新 Iris 构建复验

为了排除旧 Iris 构建因素，建立了独立 Prism 实例并重新下载 `1.7.6+mc1.20.1`。该文件与原测试实例中的 Iris JAR 完全相同，且没有更高的 1.20.1 构建可供升级。使用会触发异常的 r5.5.1 重新启动后，异常仍定位在 Iris 1.7.6 的 `CustomUniforms` / `IrisRenderingPipeline`：`Unknown variable: BIOME_PALE_GARDEN`。F3+T 后没有重复产生新的该异常，说明它发生在首次 shader pipeline 创建阶段，而不是 seam 切换导致的异常。

这次复验仍完成了：

- 正向 seam：`sections=1296`、native renderer promotion、`shadingTurns=3`；
- 反向 seam：`sections=1296`、native renderer promotion、`shadingTurns=0`；
- F3+A：worker 停止并重新启动，无 native 错误；
- F3+T：ResourceManager 完成重载，无新增 shader Java 异常。

复验日志：[`r55-iris-1.7.6-latest-cube-net.log`](r55-iris-1.7.6-latest-cube-net.log)。复验截图：[`r55-iris-1.7.6-latest-forward.png`](r55-iris-1.7.6-latest-forward.png)、[`r55-iris-1.7.6-latest-reverse.png`](r55-iris-1.7.6-latest-reverse.png)。


## Distant Horizons 组合验证（2026-10-27）

在独立 Prism 实例 `tellus-native-shader-dh-5.5.1-test` 中加入 Distant Horizons `3.3.3-1.20.1`（Fabric/Forge JAR，SHA-256 `a537a71171d935540b56b7598d0de2c106f6f5026336c713d89a1d9ea0ea14e3`），其版本页面标注支持 Minecraft 1.20.1 和 Fabric：[Distant Horizons 3.3.3-1.20.1](https://modrinth.com/mod/distanthorizons/version/6UnEfsRQ)。Tellus、Sodium、Iris、Indium 和 r5.5.1 保持与上面的固定版本一致。

启动组合通过：DH 识别为 `3.3.3 (API 7.2.0)`，Tellus integration 已启用；`DHLevel`、SQLite 数据库和 OpenGL LOD renderer 均成功创建，Iris 的 DH event handlers 也已绑定。该实例在 Mesa llvmpipe 下运行，F3 显示 DH `3.3.3`、`Rendering API: OpenGL`；数据库在测试后包含 `FullData=77`、`ChunkHash=334`，说明 DH 已接收并保存本次世界数据。

正向 seam 使用 `tellus_test:cut`：服务器确认目标分块后，客户端记录 `sections=1296`、`Sodium renderer promoted`、`render chart committed`，并将 `shadingTurns` 从 0 切换为 3；反向 seam 使用 `tellus_test:return`，同样完成 `sections=1296` 和 native promotion，`shadingTurns` 从 3 回到 0。两次提交后均有连续帧记录，未出现 DH 崩溃、Sodium stage 销毁或 Iris shader Java 异常。F3+A 重新构建 chunk，F3+T 完成资源重载；重载后日志只出现正常的资源管理器和 worker 重启记录。

DH/Iris 启动阶段仍有一条需要保留的兼容性日志：`Unexpected; somehow the Opaque + Translucent pass ran with shaders on.` 随后 DH 继续完成 shader vertex/fragment 加载、GLProxy 创建和 LOD renderer 初始化，测试过程中没有升级为崩溃或 seam 回退。另有 Iris 设置 `enableDhFog` / `enableSsao` 的 deprecated API 警告，以及 llvmpipe GLSL 未初始化警告；它们没有阻止渲染提交。Tellus 还明确记录当前 DH 构建不能使用 Tellus-managed terrain downloads，将在 startup generation 释放后走 legacy download path，这意味着本轮验证覆盖的是客户端 DH 初始化、数据接收、LOD OpenGL 绑定和 seam 往返，不代表 Tellus 专用远景下载协议已经完成。

DH 证据：[`dh-iris-tellus-latest.log`](dh-iris-tellus-latest.log)、[`dh-tellus-server.log`](dh-tellus-server.log)、[`dh-debug-before-reload.png`](dh-debug-before-reload.png)、[`dh-after-reload.png`](dh-after-reload.png)、[`dh-reverse.png`](dh-reverse.png)。以上段落记录的是 llvmpipe 组合验证。随后已在独立 RTX Prism 实例中完成长距离 DH 与性能补测，结果见 [`rtx-long/dh-rtx-long-20261027.md`](rtx-long/dh-rtx-long-20261027.md)：服务端和客户端 DH SQLite 均持续写入，固定视角下 DH 开启约 186 FPS、关闭约 301 FPS。

## Tellus DH fork 修复复验（2026-10-27）

按仓库内固定提交和补丁重建了 `3.3.1-tellus.1` Fabric 1.20.1 fork：loader
`f0cefb7a994a670ca653e3dd52ff345275018724`、core
`b0a5f3506364485eb9e4cd94b5343304adb6c88c`，并新增
[`core-render-order.patch`](/home/void0312/Workshop/tellus-optimized/compat/distant-horizons/patches/core-render-order.patch)。该补丁保持 DH 的
`DhApiBeforeRenderEvent` 参数上下文完整，等待 Iris 更新
`deferTransparentRendering` 后重新计算 `renderPass`，避免首帧使用过时的
`OPAQUE_AND_TRANSPARENT`。

`api:test`、`core:test` 和 `fabric:remapJar` 均通过，jar SHA-256 为
`3ac76b11c9395601240b6d38c41e18b524e4efe19cf4d2bd72097b65607029c9`。独立 Prism
实例 `tellus-native-shader-dh-fork-3.3.1-test` 实机启动后显示
`Distant Horizons 3.3.1-tellus.1 (API 7.1.0)`，Tellus-managed terrain download
能力检测成功；日志中不再出现 `Opaque + Translucent` 错误，也不再出现之前的
Tellus-managed download 回退警告。

同一实例在 r5.5.1、Iris 1.7.6、Sodium 0.5.13 下完成：正向和反向各
`sections=1296` native promotion，`shadingTurns=0→3→0`，以及 F3+A、F3+T。
仍存在的 `BIOME_PALE_GARDEN` 是光影包 uniform 问题，和 DH fork 修复属于不同调用链。
证据：[`dh-fork-iris-tellus-latest.log`](dh-fork-iris-tellus-latest.log)、
[`dh-fork-tellus-server.log`](dh-fork-tellus-server.log)、
[`dh-fork-after-reload.png`](dh-fork-after-reload.png)。

### 错误来源定位

两条日志不是同一个问题。

- `Unexpected; somehow the Opaque + Translucent pass ran with shaders on.` 的直接来源是 Iris 1.7.6 的 `net.irisshaders.iris.compat.dh.LodRendererEvents`。Iris 在 `DhApiBeforeRenderEvent` 中设置 DH 的 `deferTransparentRendering`，但 DH 3.3.3 的 `ClientApi` 先用上一个状态计算 `renderPass`，之后才触发这个事件；首次渲染时默认值为 false，于是先形成 `OPAQUE_AND_TRANSPARENT`，随后 Iris 已经发现 shader pipeline 为 active，便在 `DhApiBeforeRenderPassEvent` 记录该错误。这个顺序冲突发生在 Iris↔DH 的渲染事件协议，Tellus 和 seam 代码不在调用链中。日志中该错误只出现一次，后续 shader 加载和 seam 往返继续完成。
- `Tellus-managed terrain downloads are unavailable...` 则是 Tellus 的主动能力检测。`DistantHorizonsIntegration.generationGateAvailable()` 反射查找 `IDhApiWorldGenerator.getGenerationAvailability(...)`；官方 DH 3.3.3 的接口没有这个 Tellus 扩展方法，于是 Tellus 按设计切换 legacy 远景生成路径。仓库中的 `compat/distant-horizons` 补丁给 DH 3.3.1-tellus.1 增加了该方法，因此这不是 seam 失败，也不是 API 主版本不兼容，而是安装了官方 DH 包而不是 Tellus 专用 DH 包。

对应代码证据：[`DistantHorizonsIntegration.java`](/home/void0312/Workshop/tellus-optimized/src/main/java/com/yucareux/tellus/integration/distant_horizons/DistantHorizonsIntegration.java:89)、[Iris `LodRendererEvents.java`](https://github.com/IrisShaders/Iris/blob/1.20.1/src/main/java/net/irisshaders/iris/compat/dh/LodRendererEvents.java#L82-L90)、[Iris pass 检查](https://github.com/IrisShaders/Iris/blob/1.20.1/src/main/java/net/irisshaders/iris/compat/dh/LodRendererEvents.java#L263-L276)、[DH render-pass 计算](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/3.3.3/core/src/main/java/com/seibel/distanthorizons/core/api/internal/ClientApi.java#L512-526) 和 [DH 事件触发顺序](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/3.3.3/core/src/main/java/com/seibel/distanthorizons/core/api/internal/ClientApi.java#L595-603)。


## 社区交叉证据

这不是本地 Tellus 独有的日志：

- Iris 官方 issue [#2776](https://github.com/IrisShaders/Iris/issues/2776) 在 Minecraft 1.21.1、Iris 1.8.8 上记录了完全相同的 `inPaleGarden` / `BIOME_PALE_GARDEN` / `CustomUniforms` 堆栈；报告者说明有异常调用栈但暂未导致崩溃。
- Iris 官方 issue [#3326](https://github.com/IrisShaders/Iris/issues/3326) 在 Iris 1.8.14-beta.1 上再次报告同一错误，并同时出现 `BIOME_SULFUR_CAVES`；报告者说明即使没有加载光影也会记录该警告。
- 公开日志 [EIuJwfr](https://mclo.gs/EIuJwfr) 和 [Fy0eRrN](https://mclo.gs/Fy0eRrN) 使用 Minecraft 1.20.1、Iris 1.7.6，堆栈与本机一致，均落在 `ExpressionResolver`、`CustomUniforms` 和 `IrisRenderingPipeline`。

Complementary 的当前 `shaders.properties` 也明确包含 `uniform.float.inPaleGarden=...BIOME_PALE_GARDEN...`，所以触发点是光影包表达式引用了当前 Minecraft/Iris 组合中不存在的 biome 常量。社区记录显示这是 Iris uniform 解析的已知兼容问题，且在更高 Iris 版本中仍有报告；它通常表现为警告和回退，是否导致崩溃取决于其他模组和光影路径。

## 对其他渲染功能的实际影响

对 `r5.5.1` 的 shader 源码做了引用追踪：`inPaleGarden` 只出现在 `shaders/lib/atmospherics/volumetricLight.glsl`，用于夜间体积光强度、体积光场景强度和光束倍率；它没有被 TAA、普通阴影、水体反射、彩色照明、IPBR、实体材质或 seam 坐标变换路径使用。Iris 1.7.6 的 `CustomUniforms` 对单个 uniform 解析失败进行捕获并继续构造其余 uniforms，因此本次日志表示 Pale Garden 特殊光束失效，不表示整个 shader pipeline 被关闭。

Tellus 代码和资源中没有注册 `minecraft:pale_garden`，当前 Minecraft 1.20.1 也没有该原版 biome。因此在本项目目标环境中，这个 uniform 的正常语义本来应当始终为 0；实际损失是未来若加入同名 biome 或升级到包含该 biome 的 Minecraft 版本时，5.5.1 的特殊光束不会生效。已完成的 Prism 日志还显示 shaderpack 继续加载、正反向 native promotion、资源重载和 seam 往返均完成；日志中的云 shader uniform、方块 ID 映射和离线账户错误是独立警告，不能归因于 `inPaleGarden`。

## PR 与错误归属

Iris 曾合并过与 Complementary biome 检查相关的历史 PR：[\#875](https://github.com/IrisShaders/Iris/pull/875) 和 [\#898](https://github.com/IrisShaders/Iris/pull/898)。它们解决的是早期通用 biome uniform 支持，不能覆盖当前新增的 `BIOME_PALE_GARDEN`。当前同一错误的 [\#2776](https://github.com/IrisShaders/Iris/issues/2776) 与 [\#3326](https://github.com/IrisShaders/Iris/issues/3326) 仍开放，没有关联的合并修复。开放的 [\#3362](https://github.com/IrisShaders/Iris/pull/3362) 目标是 1.21.1 的其他 Iris-exclusive uniforms，改动文件也没有加入 Pale Garden 或 Sulfur Caves 常量。

本机堆栈可以闭合到三层：Complementary 的 `shaders.properties` 表达式 → Iris `ExpressionResolver` 解析未知标识符 → `CustomUniforms` 构造期间由 `IrisRenderingPipeline` 记录异常。Tellus 的 seam 逻辑不在这条调用链上，且同一进程随后完成 native promotion 和往返切换。

因此不需要为了修复 Tellus 而 fork Tellus。若要研究上游修复，应建立独立的 Iris 1.20.1 实验分支：让不存在的 biome 常量解析为不会命中的保留值或让该 uniform 安全跳过，并用 r5.5.1、r5.2.2、无光影和含真实 mod biome 的矩阵验证。这个修复应回到 Iris 上游，而不是把 Iris 修改嵌入 Tellus。

## seam 证据

- r5.5.1 正向：`sections=1296` → `Sodium renderer promoted` → `render chart committed`，坐标从源面切到目标面，`shadingTurns=3`。
- r5.5.1 反向：同样完成 `sections=1296` promotion，`shadingTurns=0`，回到源面。
- r5.2.2 正向：同样完成 `sections=1296` promotion 和目标面提交。
- F3+A 重新排队 chunk worker；F3+T 重新加载 ResourceManager。测试后没有出现 native stage 销毁、GL managed-state、或 seam 回退异常。

## 结论与后续

当前原生 seam 适配在启用 Iris 光影时能完成准备、上传、提交和往返切换。最新可用的 Iris 1.7.6 复验仍复现 `r5.5.1` 的 Pale Garden uniform 异常，因此根因仍是光影包与 Iris/Minecraft 版本组合的兼容性；Tellus seam 路径没有抛出该异常。当前选择保留 `r5.5.1`，把该异常记录为已知的局部回退，并继续验证其新增画质功能；`r5.2.2` 作为无该警告的回退基线保留。前述 llvmpipe 结论不包含 RTX 性能；RTX 原生 OpenGL 的补测已单独记录，仍保留本报告的 shader 兼容性边界。

日志摘录：[`r55-iris-cube-net.log`](r55-iris-cube-net.log)、[`r52-iris-cube-net.log`](r52-iris-cube-net.log)。
截图：[`r55-active.png`](r55-active.png)、[`r55-forward.png`](r55-forward.png)、[`r55-reverse.png`](r55-reverse.png)、[`r52-forward.png`](r52-forward.png)。


## RTX 长距离 DH 补测（2026-10-27）

独立实例 `tellus-native-shader-dh-fork-rtx-long-20261027` 使用 Tellus DH fork `3.3.1-tellus.1`、Iris 1.7.6、r5.5.1 和 RTX 3090 完成长距离生成及同视角性能对照。DH 配置为生成/同步 8192 blocks、生成边界 512 chunks；从全新活动缓存传送到 8192 和 4096 坐标后，最终服务端 `FullData=105144`、客户端 `FullData=61580`，客户端 F3 显示远程生成请求 `Failed: 0`。固定视角 FPS 对照为 DH 关闭约 301、开启约 186；开启时 NVIDIA 监控约 223 W、6.4 GiB VRAM。服务端曾出现一次 2665 ms / 53 ticks 追赶延迟，关闭时 world-gen queue 报告 0 incomplete tasks。

该补测仍运行在 `minecraft:overworld`；Tellus cube-net/sphere 维度未加载，不能把长距离结论直接外推到球面维度。`BIOME_PALE_GARDEN` 仍是 Iris 1.7.6 与 r5.5.1 的已知局部 uniform 警告，未导致崩溃。完整证据见 [`rtx-long/dh-rtx-long-20261027.md`](rtx-long/dh-rtx-long-20261027.md) 和 [`rtx-long/dh-rtx-long-20261027.json`](rtx-long/dh-rtx-long-20261027.json)。
