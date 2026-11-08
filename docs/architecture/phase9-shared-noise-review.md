# Tellus Phase 9 Shared Spherical Noise 审阅

> 审阅对象：task-78 修正后的 `SphericalNoisePoint` helper与 task-79 shared spherical-noise reuse boundary。
>
> 审阅任务：task-79。
>
> 结论：**Phase 9 抽出一个合法、versioned、ECEF-derived `SphericalNoisePoint` value object，供 `CubeNetSurfacePolicy` 与 coordinate-context factory使用；它没有替换 `CubeNetBiomeSource.noiseCoordinates`，也没有改变该旧 biome path 的算法。task-78 修正 malformed helper 后，Lead 串行 targeted `CubeNetSurfacePolicyTest` 与 `TellusCoordinateContextContractTest` 均 `BUILD SUCCESSFUL`。Earth/Cube 已知 biome mismatch 与 Cube/Sphere canonical parity 保持不变。**

## 1. 实现范围

### 1.1 修正后的 helper

- `src/main/java/com/yucareux/tellus/worldgen/global/SphericalNoisePoint.java`

`SphericalNoisePoint` 是 immutable record，字段为：

- normalized ECEF unit vector：`unitX`、`unitY`、`unitZ`；
- canonical integer pair：`noiseX`、`noiseZ`；
- versioned `algorithmId`。

当前默认算法标识：`tellus:spherical-noise-v1-round`。

`fromGeoPoint` 的计算保持明确且有限：

```text
latitude  = radians(point.latitude)
longitude = radians(point.longitude)
unitX     = cos(latitude) * cos(longitude)
unitY     = sin(latitude)
unitZ     = cos(latitude) * sin(longitude)
noiseX    = round(unitX * 1_000_000 + unitY * 37_000)
noiseZ    = round(unitZ * 1_000_000 + unitY * 91_000)
```

构造器验证：

- unit coordinates必须 finite；
- vector长度必须为非零且在 `1.0E-9` tolerance内为 unit length；
- algorithm ID必须非空；
- 非有限或超出 int范围的 noise coordinate由 `safeCoordinate` 明确失败或 clamp到 int边界。

它不读取 source/cache/world，不产生随机状态，也不包含 elevation；同一水平 GeoPoint 的 elevation变化不改变 spherical noise identity。

### 1.2 使用点与明确非变更点

新的 spherical helper被以下 abstraction使用：

- `TellusCoordinateContextFactory` 在 Earth/canonical/apron/spherical context construction中生成 `SphericalNoisePoint`；
- `CubeNetSurfacePolicy.canonicalNoiseCoordinates` 通过 `SphericalNoisePoint.fromGeoPoint(point)`取得 seam-stable coordinates。

**明确未替换：**

- `src/main/java/com/yucareux/tellus/worldgen/CubeNetBiomeSource.java:761` 的私有 `noiseCoordinates`；
- 该旧方法仍直接执行 latitude/longitude → ECEF-like coordinate → `coordinateForClassification` 的既有逻辑；
- 本 task不把 `CubeNetBiomeSource` 的 cave noise或 biome path重定向到 `SphericalNoisePoint`；
- 不改变其 `caveNoise` 调用、hash seed、grid floor division或 biome classification。

因此本轮 shared spherical noise reuse只影响新增/已迁移的 coordinate-context与surface-policy seams，不把旧 biome source path隐式纳入迁移。

## 2. 严格语义边界

### 2.1 Coordinate identity

`SphericalNoisePoint`提供的是 spherical/ECEF-derived deterministic view，不是 Earth projected grid cell：

- 不替代 `EarthCompatibilityPoint`；
- 不替代 Cube-Net canonical owner/block address；
- 不改变 apron ownership或 seam projection；
- 不使用 planar coordinates作为 spherical owner；
- 不含 biome registry、material、writer或runtime lifecycle。

`algorithmId`使 future rounding/scale changes可被显式版本化，而不是静默改变 cache或parity identity。

### 2.2 Seam/date-line intent

ECEF-derived unit direction使 ±180° longitude在 canonicalized GeoPoint语义下保持连续；`GeoPoint`本身将 -180/180归一到同一边界。该 helper的 contract test验证 date-line equality与 unit-length invariant，但这不等于所有 cube-face seam的全局 terrain/biome parity已经证明。

### 2.3 CubeNetBiomeSource non-change

旧 `CubeNetBiomeSource.noiseCoordinates` 继续保留是有意的 legacy boundary：

- 其 classification/cave noise行为作为旧 biome source authority；
- 不将新的 algorithm ID强行写入旧 biome cache key；
- 不改变既有 `coordinateForClassification` rounding/clamp语义；
- 不把 `CubeNetBiomeSource` 的任何 status或 biome mismatch归因于新 helper；
- 若未来迁移该方法，必须有 direct-oracle equivalence test和同一 parity comparator。

## 3. 测试证据

### 3.1 Lead targeted tests

Lead 已串行执行并报告 `BUILD SUCCESSFUL`：

- `CubeNetSurfacePolicyTest`；
- `TellusCoordinateContextContractTest`。

本轮审阅不另行启动 Gradle。

### 3.2 覆盖的 helper/context行为

`TellusCoordinateContextContractTest` 覆盖：

- GeoPoint latitude clamp与longitude boundary normalization；
- Earth compatibility projection与负坐标 floor语义；
- `SphericalNoisePoint` unit-length、determinism、algorithm version、elevation independence；
- date-line `-180/180` equality；
- invalid non-unit vector与blank algorithm ID拒绝；
- spherical owner face/chunk agreement；
- Cube-Net canonical/apron owner distinction；
- coordinate context owner-specific optional normalization与immutable value-object约束。

`CubeNetSurfacePolicyTest` 覆盖：

- missing slope保留 existing cover palette；
- steep canonical slope复用 bare-rock rule；
- snow使用 shared variable snow depth；
- date-line附近 canonical noise coordinates不跳变。

### 3.3 Evidence boundary

这些 targeted tests证明 helper与surface/coordinate contract的局部行为，不证明：

- `CubeNetBiomeSource.noiseCoordinates` 已替换；
- Earth/Cube biome全路径等价；
- cave noise旧路径与新路径等价；
- generator/runtime/lifecycle/storage接入；
- parity comparator已有新输出。

## 4. Biome mismatch 与 parity 保持

本次 shared spherical noise review 不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

`SphericalNoisePoint`只建立新 coordinate/surface seam，未修复、掩盖或重新分类 `bare/BWh`。`CubeNetBiomeSource.noiseCoordinates` 未变，因此不能把本轮 helper测试解释成 biome mismatch修复。

任何未来将旧 biome noise path迁移到该 helper的变更，都必须先证明：

1. direct-oracle坐标/seed equivalence；
2. cave/biome output equivalence；
3. canonical/apron seam coverage；
4. 同一 `13,824 / 0` 与 `7 / 1 bare/BWh` comparator门槛。

## 5. Legacy、未接入与回滚

### 5.1 Legacy behavior

- `CubeNetBiomeSource.noiseCoordinates`及其 cave/biome使用保持legacy authority；
- 新 helper不注册 generator、runtime、Common/server lifecycle、provider或writer；
- `CubeNetSurfacePolicy`仍是显式调用者，未自动替换旧 generator；
- 不改变 source/cache/save/payload/runtime；
- algorithm ID变化不得未经显式 migration更新 persistence/cache identity。

### 5.2 Rollback points

1. **Surface-policy rollback**：恢复 `CubeNetSurfacePolicy.canonicalNoiseCoordinates` 的旧直接计算（若保留旧实现副本）。
2. **Context rollback**：停止在新的 coordinate factory中生成/消费 `SphericalNoisePoint`，恢复原调用边界。
3. **Biome isolation rollback**：维持 `CubeNetBiomeSource.noiseCoordinates` 不变；任何误迁移立即恢复旧方法。
4. **Algorithm-version rollback**：保留 `tellus:spherical-noise-v1-round`，禁止静默改 scale/rounding；新算法使用新 ID并单独比较。
5. **Parity rollback**：任何 canonical或biome mismatch变化立即停止 shared-noise opt-in，输出 before/after coordinate/surface diff。

## 6. 审阅结论

Task-78/79 的 spherical noise boundary正确：malformed helper已修正为有验证的 immutable/versioned value object；`CubeNetSurfacePolicy`与 coordinate context可显式使用它；`CubeNetBiomeSource.noiseCoordinates`保持未变，避免未经等价验证的旧 biome/cave path迁移。Lead targeted `CubeNetSurfacePolicyTest` 与 `TellusCoordinateContextContractTest` 均通过。

已知 Earth/Cube biome mismatch `bare/BWh` 保持，Cube/Sphere canonical `13,824 / 0 mismatch` 保持。Task-79 review完成；未来若扩展到旧 biome source，必须先完成 direct-oracle equivalence、seam coverage和同一 parity comparator验证。
