# Cube-Net 跨面坐标与采样契约（2026-11-03）

这份记录冻结当前跨面工作的几何约定。它描述的是地形生成的输入坐标和
归属规则，不改变地形高度、噪声或生物群系参数。

## 坐标约定

每个物理面使用连续坐标 `u,v ∈ [0, faceBlocks]`。方块 `(x,z)` 的采样点是
`(x+0.5,z+0.5)`。标准平面展开由 `CubeNetLayout` 固定；下表中的
`origin` 以面大小为单位，`uAxis` 和 `vAxis` 是平面 X/Z 中的单位轴。

| 面 | origin (X,Z) | uAxis (X,Z) | vAxis (X,Z) |
| --- | --- | --- | --- |
| POS_X | (2,-1) | (0,1) | (-1,0) |
| NEG_X | (-1,0) | (0,-1) | (1,0) |
| POS_Y | (0,-1) | (1,0) | (0,1) |
| NEG_Y | (0,1) | (1,0) | (0,1) |
| POS_Z | (0,-2) | (1,0) | (0,1) |
| NEG_Z | (1,1) | (-1,0) | (0,-1) |

世界生成的默认配置为 `faceBlocks=10008576`、`chartSizeBlocks=1024`、
`expansionOrder=3`、截断误差上限 `0.25m`。这些值来自
`SphereWorldModel.forWorldScale(1.0)`；测试不会把它们写入世界配置。

## 24 条有向物理边

`reverse` 表示沿边的坐标是否需要反向。`planar` 表示该物理边是否已经
在当前平面展开中直接相邻；其中 10 条有向边（5 条无向边）是直接相邻，
其余 14 条通过 seam/apron 处理。

| source | side | target | target side | reverse | planar |
| --- | --- | --- | --- | ---: | ---: |
| POS_X | WEST | POS_Z | EAST | no | no |
| POS_X | EAST | NEG_Z | WEST | no | no |
| POS_X | NORTH | NEG_Y | EAST | yes | no |
| POS_X | SOUTH | POS_Y | EAST | no | yes |
| NEG_X | WEST | NEG_Z | EAST | no | no |
| NEG_X | EAST | POS_Z | WEST | no | no |
| NEG_X | NORTH | NEG_Y | WEST | no | no |
| NEG_X | SOUTH | POS_Y | WEST | yes | yes |
| POS_Y | WEST | NEG_X | SOUTH | yes | yes |
| POS_Y | EAST | POS_X | SOUTH | no | yes |
| POS_Y | NORTH | POS_Z | SOUTH | no | yes |
| POS_Y | SOUTH | NEG_Z | SOUTH | yes | yes |
| NEG_Y | WEST | NEG_X | NORTH | no | no |
| NEG_Y | EAST | POS_X | NORTH | yes | no |
| NEG_Y | NORTH | NEG_Z | NORTH | yes | yes |
| NEG_Y | SOUTH | POS_Z | NORTH | no | no |
| POS_Z | WEST | NEG_X | EAST | no | no |
| POS_Z | EAST | POS_X | WEST | no | no |
| POS_Z | NORTH | NEG_Y | SOUTH | no | no |
| POS_Z | SOUTH | POS_Y | NORTH | no | yes |
| NEG_Z | WEST | POS_X | EAST | no | no |
| NEG_Z | EAST | NEG_X | WEST | no | no |
| NEG_Z | NORTH | NEG_Y | NORTH | yes | yes |
| NEG_Z | SOUTH | POS_Y | SOUTH | yes | yes |

接缝映射先把 source edge coordinate 按 `reverse` 变换，再沿 target side
放置 penetration。反向查找必须返回原面、原边和原 edge coordinate；顶点
同时命中两条边时不做隐式排序。

## 采样与 apron 归属

采样链路固定为：

1. `CubeNetAddressAdapter` 把平面 chunk/block 地址解析为一个 canonical
   `SphereBlockAddress`。
2. `SphereWorldModel.exactSurfacePoint` 或 chart 展开把 canonical 面坐标
   转为同一个球面 `GeoPoint`。
3. `SphereChunkSampler` 只接收该 `GeoPoint`，因此相邻面的边界不会各自
   使用一套噪声坐标。
4. `CubeNetChunkGenerator.fillFromNoise` 先填 canonical face；切边 apron
   只复制目标 canonical face 的列，不重新采样。
5. `CubeNetApronResolver` 和 `CubeNetRemoteProjection` 对超过一个合法
   owner 的凹角返回空结果，禁止按遍历顺序选面。

这意味着地形生成阶段只需要保证 canonical `GeoPoint` 上的高程、覆盖物和
水面采样是确定性的；面间连续性由同一物理点和同一 owner 规则保证。

## 验证结果

新增测试位于
`src/test/java/com/yucareux/tellus/worldgen/CubeNetCrossFaceConsistencyTest.java`，
包括：

- 24 条有向边的边界点和 8 个顶点在 exact cubed-sphere 上重合；
- 每条 seam 在 0.125、1、8 方块穿透深度内与 canonical target face 对齐，
  误差上限为 `1e-3m`；
- 同一个物理边界点从两个面进入 `SphereChunkSampler` 时得到相同的解析
  地形值；
- 标准展开网的 8 个凹角 cut-strip overlap 保持无 owner。

验证命令：

```text
./gradlew -PtellusTargets=mc1201 :mc1201:test --no-daemon
```

结果：`991 tests, 0 failures, 0 errors, 11 skipped`。

这组工作没有调整真实 DEM、高程缩放、噪声频率、生物群系或水面参数；下一步
可以在这个契约之上单独讨论地形生成策略。
