# Cube-Net 验收矩阵（2026-11-06）

本矩阵把接缝渲染与跨面运行的验收项拆成两层：**可自动断言的服务端/代码契约** 与 **必须人工执行的客户端观察**。自动层由 `tools/check_cube_net_acceptance.py` 执行，只能证明代码路径与版本门禁仍在位，不能证明渲染、移动或多人行为。

## 被测组合

| 维度 | 取值 |
| --- | --- |
| 游戏目标 | Fabric 1.20.1（`mc1201`），Java 17 |
| 原版渲染器 | Indigo（无渲染 mod） |
| Sodium | `0.5.13+mc1.20.1`（精确版本，见 `TellusRenderCompatibility.SUPPORTED_SODIUM_VERSION`） |
| Sodium 其他版本 | 预期 fail closed，不进入 staging |
| Iris / Indium | Iris 1.7.6 + Indium 1.0.36，分「关闭 shader」「开启 shader」两档 |
| Distant Horizons | `3.3.1-tellus.1` fork；`-Dtellus.dhCubeNetSeamLod=true` 时走 direct seam LOD，否则走 chunk 模式 |
| 维度 | `tellus:cube_net`（datapack 生成） |

## 自动层（无需客户端）

运行：

```powershell
python tools/check_cube_net_acceptance.py            # 静态检查，秒级
python tools/check_cube_net_acceptance.py --with-gradle   # 追加契约测试
```

2026-11-06 实测：`12/12 static checks passed`。

| 编号 | 断言 | 结果 |
| --- | --- | --- |
| A1 | 5 个 Cube-Net 契约测试文件在位（生成器 / transition payload / seam approach / boundary contract / cross-face consistency） | PASS |
| A2 | Sodium staging 仍钉在 `0.5.13+mc1.20.1` | PASS |
| A3 | DH direct seam LOD 仍由 `-Dtellus.dhCubeNetSeamLod` 控制 | PASS |
| A4–A5 | 客户端状态机保留 PREPARE 入口与 READY/commit 握手 | PASS |
| A6–A9 | 服务端注册 READY / ChunksReceived / Motion / Unsupported 四个 receiver | PASS |
| A10–A11 | 客户端注册 layout 与 boundary hint payload | PASS |
| A12 | 不支持的 Sodium 版本走 fail closed 分支 | PASS |
| G1 | `.\gradlew.bat :mc1201:test --tests "com.yucareux.tellus.worldgen.CubeNet*" --offline` | 由 `--with-gradle` 执行；Gradle 若判定 `UP-TO-DATE`，表示与上次成功运行一致 | PASS（2026-11-06 实测 `13/13`，任务 UP-TO-DATE） |

## 人工层（必须真实客户端）

| 编号 | 组合 | 判定标准 | 状态（2026-11-06） |
| --- | --- | --- | --- |
| M1 | Indigo，步行/冲刺双向跨面 | 无重复传送、无回退、位置单调前进 | 历史记录通过，本次未重跑 |
| M2 | Indigo，乘船双向跨面 | 玩家保持骑乘、船不消失、落地不穿模 | 历史记录通过，本次未重跑 |
| M3 | 保存编辑恢复 + 告示牌/箱子同步 | 编辑 overlay 不丢、容器界面可用 | 历史记录通过，本次未重跑 |
| M4 | GeoTP 传送到另一面 | 落地位置正确、进度条出现并在到达后消失 | **未验收** |
| M5 | Sodium 0.5.13 原生 staging 正/反向 | 无黑帧跳变、相机不翻转 | 历史记录通过，本次未重跑 |
| M6 | Sodium 非 0.5.13 版本 | 按 A12 分支 fail closed，不出现半渲染 | 未跑 |
| M7 | Iris 1.7.6，关闭 shader | 与 M5 同标准 | 历史记录通过，本次未重跑 |
| M8 | Iris 1.7.6，开启 shader | 与 M5 同标准 | **未验收** |
| M9 | DH chunk 模式 | 远处地形与近处接缝同向、无缺口 | 部分记录，未矩阵化 |
| M10 | DH direct 模式（`-Dtellus.dhCubeNetSeamLod`） | 同上，且日志出现 `path=direct seamAware=true` | 未跑 |
| M11 | 逐帧零跳变（zero-hold） | 切面帧间无位置空档 | **仍未通过（open；已加诊断与 READY packet 门控，待实机）** |

## 人工执行步骤

1. 用 datapack 建立 `tellus:cube_net` 维度：`/tellus cube_net datapack <name> <scale> <sea_level>`（或 `tools/create_cube_net_datapack.py`），重启世界。
2. 客户端按被测组合装好 mod（Sodium/Iris/DH 版本见上表）。
3. 传送到一条面边界（例如 `x = faceBlocks/2` 附近），按 W 步行或上船，持续跨过边界。
4. 记录证据到 `runs/<yyyyMMdd-HHmm>-<组合>/`：`client-latest.log`、`server-latest.log`、屏幕录制或逐帧截图、`SHA256SUMS`。
5. 判定：参照上表「判定标准」；任何重复传送、黑帧超过 1 帧、相机翻转、实体消失均记 FAIL，并在本文件更新状态。

## 已知限制

- 自动层是源码断言：Sodium 门禁是精确字符串匹配，升级 Sodium 必须同步修改 `TellusRenderCompatibility`，否则 A2 会先失败。
- DH direct 模式需要服务端同时满足 `generationGateAvailable()` 与生成器实现 `CubeNetLodSurface`，否则静默退回 chunk 模式（日志 `path=chunk`）。
- M11 的 zero-hold 是当前唯一已知未通过的视觉门槛；本轮新增的诊断和 READY packet 门控记录在 [`cube-net-zero-hold-20261106`](../cube-net-zero-hold-20261106/README.md)，在它之前，任何「矩阵全绿」的表述都不成立。
