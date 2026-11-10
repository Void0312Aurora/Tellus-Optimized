# 面切换方向光照验证（2026-11-03）

这组验证针对的是面切换后方向光照突变，不是区块光照包重复回放造成的亮度更新。测试环境为 Fabric 1.20.1、Sodium 0.5.13、Iris 1.7.6；Distant Horizons 渲染关闭，shader 开启，服务器时间固定为 3000，玩家通过正常移动跨过接缝。截图来自海面场景，不能作为同一方块的逐像素亮度 A/B，因此方向结论以运行时 Iris 向量和阴影基准矩阵为准。

## 实现

`CubeNetClientTransition.shadeQuarterTurns()` 保存当前 chart 的目标到源方向；`rotateCelestialWorld()` 对 Iris 世界空间太阳/月亮向量应用其逆旋转。`CubeNetIrisCelestialLightingMixin` 同时处理 Iris 的世界空间和视空间向量，`CubeNetIrisShadowMatricesMixin` 在 `ShadowMatrices.createBaselineModelViewMatrix` 返回后应用相同的目标到源旋转，使阴影贴图坐标系与方向向量一致。Iris 反射入口已做方法缓存，避免每帧重复查找类和方法。

## 运行时证据

| 阶段 | 文件 | chart turns | Iris 世界光向量 (x,y,z) | 阴影矩阵关键值 |
| --- | --- | ---: | --- | --- |
| POS_X 接缝前 | `iris-vector-source-time3000-20261103u.json` | 0 | `(56.737267, 82.34609, 0)` | `m02=1, m10=-1, m21=-1` |
| 正向跨到 NEG_Z 后 | `iris-vector-forward-time3000-20261103v.json` | 3 | `(-0, 82.34609, 56.737267)` | `m01=1, m10=-1, m22=1` |
| 反向回到 POS_X 后 | `iris-vector-reverse-time3000-20261103w.json` | 0 | `(56.737267, 82.34609, 0)` | `m02=1, m10=-1, m21=-1` |

目标面向量是源面向量按 `-shadeQuarterTurns` 的水平四分之一转：`turns=3` 时源向量 `(x,z)=(56.737,0)` 变为目标 `(0,56.737)`；反向提交恢复为原向量。阴影矩阵也在目标面采用对应的后乘旋转，回程恢复原矩阵。

服务器日志记录了正常移动的正向提交和反向提交；客户端 probe 的过渡状态均在提交后回到 `IDLE`，邻接区块指针 `neighborMismatch=0`。固定条件下的帧统计如下：

* 正向窗口：720 帧，平均 16.95ms，p95 19.86ms，最大 44.69ms，超过 50ms 为 0。
* 反向窗口：720 帧，平均 17.20ms，p95 19.54ms，最大 292.26ms，超过 50ms 为 1；该长帧与目标区块重新准备相邻，未形成秒级停顿。

这组探针还验证了 shader 运行时确实读到了旋转后的向量，而不是只检查 `ClientLevel.getShade`。在 Iris shader 管线中，`directionalShade.factors` 由 shader 覆盖为 1.0，因此不能用它们推断最终像素亮度。

## 构建

`:mc1201:test`、`:mc1201:compileClientJava`、`:mc1201:remapJar` 和 `git diff --check` 均通过；JUnit XML 总计 987 tests、0 failures、0 errors、11 skipped。最终 jar SHA-256：

`0a60ff1a57776413a974cc2523f2aae834eb1db5906042d0ed767b16f933d3b8`

游戏内向量证据使用的是同一语义改动、反射缓存前的构建；反射缓存只减少 Iris 方法查找开销，随后最终构建重新通过完整测试。截图与 JSON 保留在本目录。

随后安装最终 jar 并恢复 `ComplementaryReimagined_r5.5.1.zip` 做了静态运行检查；`final-original-pack-20261103x.json` 报告 `shaders=true`、`shadeQuarterTurns=0`，Iris 世界光向量和阴影矩阵均正常。该 120 帧窗口平均 16.66ms、p95 18.79ms、最大 26.10ms。

## 尚未覆盖的范围

本验证没有把同一个可见方块固定在切换前后做逐像素亮度差分，也没有覆盖非 Iris shader、Distant Horizons 自身的远景光照或异常跳跃/传送。当前结论是 Iris 太阳/阴影方向在 chart 切换和逆向回程中保持一致；最终像素亮度还需针对具体 shader 的材质和阴影设置做专门 A/B。
