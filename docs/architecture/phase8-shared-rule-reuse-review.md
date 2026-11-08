# Tellus Phase 8 Shared Surface Rule Reuse 审阅

> 审阅对象：task-70/73 revision 3 的 `TellusSurfaceVariantRoll` shared seam 与五个旧 surface call sites。
>
> 审阅任务：task-74。
>
> 结论：**task-70/73 将 surface-variant 调用统一委托到 `TellusSurfaceVariantRoll`，但没有复制或重写 `SurfacePatchNoise` 算法。五个调用点均保持严格 delegation：wrapper 的 `percent`/`covers` 直接调用既有实现并原样返回。等价测试覆盖多组负/零/正坐标、salt 与 coverage 边界；没有 Earth/Cube biome policy、generator lifecycle 或 parity comparator 接入变化。已知 biome mismatch 与 canonical parity baseline 保持不变。**

## 1. 实现范围

### 1.1 Shared seam

`src/main/java/com/yucareux/tellus/worldgen/TellusSurfaceVariantRoll.java` 提供两个无状态静态入口：

```java
public static int percent(int worldX, int worldZ, long salt) {
    return SurfacePatchNoise.percent(worldX, worldZ, salt);
}

public static boolean covers(int worldX, int worldZ, long salt, double coveragePercent) {
    return SurfacePatchNoise.covers(worldX, worldZ, salt, coveragePercent);
}
```

wrapper没有自己的 hash、seed、rounding、coverage clamp、cache、random source或边界修正规则；它是调用边界，不是第二套 surface algorithm。

### 1.2 五个调用点

当前 `TellusSurfaceVariantRoll` 在生产代码中恰有五个调用点：

1. `src/main/java/com/yucareux/tellus/worldgen/EarthChunkGenerator.java:11404`
   - exposed-rock cover：`covers(worldX, worldZ, EXPOSED_ROCK_SALT, MountainSurfaceRules.exposedRockPercent(slopeDiff))`。
2. `src/main/java/com/yucareux/tellus/worldgen/EarthChunkGenerator.java:11661`
   - alpine-meadow scree：`covers(worldX, worldZ, ALPINE_SCREE_SALT, MountainSurfaceRules.alpineMeadowScreePercent(slopeDiff))`。
3. `src/main/java/com/yucareux/tellus/worldgen/EarthChunkGenerator.java:11689`
   - generic surface variant：`percent(worldX, worldZ, SURFACE_VARIANT_SALT ^ (long)salt * -7046029254386353131L)`。
4. `src/main/java/com/yucareux/tellus/worldgen/EarthChunkGenerator.java:11693`
   - ocean-floor palette：`percent(worldX, worldZ, OCEAN_FLOOR_SALT)`。
5. `src/main/java/com/yucareux/tellus/worldgen/CubeNetSurfacePolicy.java:177`
   - cover-driven palette：`percent(earthX, earthZ, 23L)`。

调用点保留原有坐标、salt、coverage参数和分支位置；只替换调用入口从 `SurfacePatchNoise` 为 shared wrapper。

## 2. 严格委托边界

### 2.1 Algorithm ownership

`SurfacePatchNoise` 仍是算法行为权威：

- 既有 hash/distribution/clustering 不移动；
- wrapper不引入新 salt或坐标转换；
- wrapper不把 Earth 坐标改成 Cube-Net 坐标；
- wrapper不改变 `MountainSurfaceRules` coverage calculation；
- wrapper不参与 biome registry、sample provider、DecisionKernel、FeaturePlanner、Writer或runtime lifecycle；
- wrapper无可变状态和副作用。

这使 Earth 与 Cube-Net 共享同一个调用 seam，而不是共享一个未经验证的重写实现。

### 2.2 Call-site preservation

五个调用点的语义保持：

- Earth exposed-rock、alpine scree、generic variant、ocean floor继续使用原始坐标和 salt；
- Cube-Net cover palette继续使用 `earthX/earthZ` 和固定 salt `23L`；
- switch/palette、slope、climate、cover class和material mapping逻辑没有迁移；
- fallback/default palette没有改变；
- wrapper返回值直接进入原分支，没有中间转换。

因此该变更只建立统一 seam，不扩大调用范围，也不把 shared seam误当成完整 biome/terrain abstraction。

## 3. 等价测试证据

### 3.1 Test file

- `src/test/java/com/yucareux/tellus/worldgen/TellusSurfaceVariantRollTest.java`

`percentAndCoverageRemainExactDelegates` 对如下输入笛卡尔积逐项比较：

- salts：`0L`、`23L`、`Long.MIN_VALUE`、`0x5DEECE66DL`；
- coordinates：`-1_000_003`、`-97`、`0`、`1`、`7_919`、`2_000_123`；
- coverage：`0.0`、`10.0`、`35.0`、`65.0`、`100.0`。

断言为：

```java
assertEquals(SurfacePatchNoise.percent(x, z, salt),
    TellusSurfaceVariantRoll.percent(x, z, salt));
assertEquals(SurfacePatchNoise.covers(x, z, salt, requested),
    TellusSurfaceVariantRoll.covers(x, z, salt, requested));
```

这同时覆盖负坐标、零坐标、跨大范围坐标、极端 salt 和 coverage 边界；测试直接以旧算法作为 oracle，证明 wrapper没有改变结果。

### 3.2 Evidence boundary

等价测试证明的是 wrapper-to-algorithm delegation，不证明：

- Earth/Cube terrain全列输出等价；
- biome climate/cover policy完整等价；
- generator lifecycle或 runtime adapter已接入；
- Minecraft storage、save/reload、renderer或 parity output已改变。

后续若增加第三方调用点，应先加入相同 direct-oracle等价测试，再允许调用范围扩展。

## 4. Biome mismatch 与 parity 保持

本次 shared surface rule reuse 不改变 golden baseline：

- Cube/Sphere canonical columns：`13,824`，`0 mismatch`；
- Earth/Cube biome：`7 cases`，`1 mismatch`，已知 `bare/BWh`。

`TellusSurfaceVariantRoll`只委托 `SurfacePatchNoise`，不修复、不掩盖、不重新分类 `bare/BWh`。Earth/Cube biome mismatch仍是已知基线；不能把 wrapper等价测试结果解释为 biome parity修复。

五个 call sites 尚未形成新的 generator/runtime registration、pipeline writer或 parity output路径；若未来 wrapper调用导致任何新增 mismatch，应立即回滚对应 call-site替换，保留旧直接调用并输出 before/after surface diff。

## 5. Legacy、生命周期与回滚

### 5.1 Legacy behavior

- 旧 SurfacePatchNoise算法仍是实际行为权威；
- wrapper替换不注册 generator、runtime、Common/server lifecycle或 provider；
- Earth/Cube generator继续按原有调用时机计算 surface variant；
- 不改变 legacy source/cache/save/payload/runtime；
- 删除 wrapper并恢复五个直接调用即可回滚到变更前结构。

### 5.2 Rollback points

1. **Single-call-site rollback**：恢复任一调用点为 `SurfacePatchNoise.percent/covers`。
2. **Shared seam rollback**：移除 `TellusSurfaceVariantRoll`并恢复五个直接调用；算法字节路径不变。
3. **Policy rollback**：若后续 wrapper扩展语义，先停用新入口，不改 Earth/Cube policy。
4. **Parity rollback**：任何 canonical或biome comparator变化均停止 shared seam opt-in，保留旧 direct calls。
5. **Lifecycle rollback**：本变更没有 lifecycle registration；无需 detach或 Common hook cleanup。

## 6. 与其他 abstraction 的边界

该 seam 不应被误认为已完成以下迁移：

- `CubeNetSurfacePolicy` 到 DecisionKernel 的完整 surface profile；
- Earth/Cube biome source统一；
- FeaturePlanner/Writer surface placement；
- Phase 6 pipeline consumer；
- Phase 7 runtime registry/factory；
- Phase 8 lifecycle bridge/Common hooks。

它只提供一个可测试、可复用的 deterministic surface roll delegation point。

## 7. 审阅结论

Task-70/73 的五个 call-site替换保持严格委托，`TellusSurfaceVariantRoll`的两个方法均原样转发到 `SurfacePatchNoise`；等价测试以旧算法为 oracle，覆盖坐标、salt和coverage边界。该变更没有 generator/runtime/lifecycle注册，也没有改变 default legacy行为。

已知 Earth/Cube biome mismatch `bare/BWh` 保持，Cube/Sphere canonical `13,824 / 0 mismatch` 保持。Task-74 review完成；后续扩展必须继续以 direct-oracle等价测试和同一 parity comparator为门槛。
