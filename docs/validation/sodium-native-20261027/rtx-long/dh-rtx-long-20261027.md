# DH 长距离生成与 RTX 性能验证（2026-10-27）

## 测试范围

- 实例：`tellus-native-shader-dh-fork-rtx-long-20261027`。
- Minecraft 1.20.1、Tellus 0.8.4、Sodium 0.5.13、Iris 1.7.6、Tellus DH fork 3.3.1-tellus.1、Complementary Reimagined r5.5.1。
- 实际 OpenGL：NVIDIA Corporation / GeForce RTX 3090 / driver 580.178.04。
- DH 约束：实时更新半径 512 chunks，生成和同步距离 8192 blocks，生成边界 512 chunks，客户端请求速率 20/s，原版视距 4。

## 长距离生成

使用全新的活动客户端缓存，保留旧缓存作为备份；客户端先传送到 `(8192, 8192)`，再到 `(4096, 4096)`。服务端日志确认创建了 overworld DH world-generation queue，Tellus startup gate 释放；客户端接收服务端配置，随后出现正常的 DH request rate-limit 重排，没有失败请求。

收束后的 SQLite 记录如下：

| 数据库 | 文件大小 | FullData | ChunkHash | PosX/PosZ 范围 |
|---|---:|---:|---:|---|
| 服务端 overworld | 130,539,520 B | 105,144 | 867 | -128..159 / -128..159 |
| 客户端 overworld | 83,857,408 B | 61,580 | 317 | -64..159 / -64..159 |

这证明远景数据生成、服务端保存、网络传输和客户端落盘均生效。一次传送后服务器出现 `2665ms / 53 ticks behind`，说明在这个距离与生成计划下，DH 会产生明显服务器追赶延迟；没有崩溃，关闭时 world-gen queue 报告 `0 incomplete tasks`。

## RTX 渲染性能

在同一客户端、同一视角、同一光影下，仅切换 DH LOD 渲染开关，预热后取 1 秒采样：

| 状态 | FPS（样本数） | RTX 功耗 | SM 利用率 | VRAM |
|---|---:|---:|---:|---:|
| DH 关闭 | 平均 300.6（16） | 215.3 W | 50.5% | 6422 MiB |
| DH 开启 | 平均 185.8（21） | 223.2 W | 46.8% | 6445 MiB |

DH 开启时 FPS 从约 301 降到约 186，且 GPU 功耗和显存占用上升；因此 DH LOD 确实进入了实际 RTX 渲染路径。截图 [`phase2-dh-on.png`](phase2-dh-on.png) 的 F3 还显示 188 FPS、RTX 3090、Sodium renderer、DH 3.3.1-tellus.1 和 OpenGL Rendering API，远景队列 `Failed: 0`。

## 错误与边界

Iris 1.7.6 在 r5.5.1 重载时反复记录 `BIOME_PALE_GARDEN` uniform 未知变量，但 shader pipeline 继续工作，没有引发 DH 或客户端崩溃。Sodium 记录 NVIDIA threaded optimizations workaround；这会限制绝对性能解释，但不影响本次同机对照。

本轮长距离测试使用 `minecraft:overworld`。新建的测试世界没有加载 Tellus cube-net/sphere datapack 维度，所以还没有把 8192 格长距离生成结论外推到 Tellus 球面维度；这部分需要单独装入球面测试世界后复测。

完整日志和监控文件：[`phase1-server.log`](phase1-server.log)、[`phase1-server-debug.log`](phase1-server-debug.log)、[`phase1-client.log`](phase1-client.log)、[`phase2-fps.csv`](phase2-fps.csv)、[`phase2-dmon-controlled.log`](phase2-dmon-controlled.log)。结构化数据：[`dh-rtx-long-20261027.json`](dh-rtx-long-20261027.json)。
