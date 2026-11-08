# Tellus 生成器统一抽象设计

本目录记录 Earth、Cube-Net 与 Sphere-Face 生成器的统一抽象审计和迁移方案。当前文档阶段只新增设计资料，不改变生成行为、Codec、存档格式或运行时协议。

## 设计文档

- [`tellus-generator-settings-runtime.md`](tellus-generator-settings-runtime.md)：统一只读 settings view、runtime 生命周期和 optional capabilities；保留 legacy fallback 与 runtime override 优先级。
- [`tellus-coordinate-context.md`](tellus-coordinate-context.md)：区分 GeoPoint、Earth compatibility coordinates、spherical noise coordinates、face-local/planar view 和 canonical/apron owner。
- [`tellus-decision-kernel.md`](tellus-decision-kernel.md)：设计 sample provider、generation context、column decision kernel、feature planner 和 Earth/Cube/Sphere writer。
- [`tellus-migration-plan.md`](tellus-migration-plan.md)：阶段 0–8 的实施顺序、改动范围、验收、回滚点和风险。

## Lead 汇总结论

### 当前架构判断

- 数据源和部分局部规则已经共享。
- 三个生成器仍是独立执行器。
- `TellusChunkGeneratorBridge` 是 Minecraft API bridge，不是统一生成器基类。
- 统一方向应采用组合，不建立大型 `TellusChunkGeneratorBase`。
- 生成器应各自保留坐标适配、所有权、写块和 Minecraft 生命周期；共享纯采样、决策和 feature plan。

### 固定坐标语义

| 类型 | 权威用途 |
|---|---|
| `GeoPoint` | 球面物理位置、地理数据源、经纬度特性查询 |
| `EarthCompatibilityPoint` | EarthProjection/Earth raster/classifier 兼容输入 |
| `SphericalNoisePoint` | 跨日期线和跨 face 连续的确定性噪声 |
| `Face/Canonical owner` | Sphere/Cube-Net 真实数据所有权 |
| `Planar view/apron` | Cube-Net 展开视图或 derived mirror，不得成为第二 owner |

### 统一抽象链

```text
SettingsView / RuntimeView
          ↓
CoordinateContext
          ↓
SampleProvider
          ↓
DecisionKernel
          ↓
FeaturePlanner
          ↓
Column/Feature Writer
          ↓
Runtime Adapter / Minecraft lifecycle
```

### 已锁定的行为基线

- Cube-Net/Sphere-Face：13,824 columns，0 mismatch。
- Earth/Cube-Net biome：7 cases，1 mismatch，已知 `bare/BWh` 为 Earth `desert`、Cube-Net `stony_peaks`。
- 迁移期间保留 `UNKNOWN`/unavailable 语义、canonical owner/apron mirror、legacy fallback、runtime override 优先级和 M11 open 状态。

### 推荐落地顺序

1. 只读 `TellusSettingsView` / `TellusRuntimeView` adapter，不改变 Codec。
2. 引入 typed `TellusCoordinateContext`，先解决坐标语义混用。
3. 为 Earth 与 Cube-Net 建立可注入 `TellusSampleProvider`。
4. 抽取 `TellusDecisionKernel`，先覆盖 biome/surface/water/strata。
5. 抽取 owner-aware `FeaturePlanner`。
6. 将 Earth/Cube/Sphere 写入实现分别适配到 writer。
7. 最后迁移 realtime、DH、Voxy、managed terrain 和 seam runtime 管理器。

本阶段不继续扩大测试面，现有 parity 结果作为迁移期间的 golden baseline；每个实现阶段都必须保留旧路径和可回滚点。
