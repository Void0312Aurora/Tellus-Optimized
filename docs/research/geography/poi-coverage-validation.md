# 高价值地点隔离核验，2026-10-25

## 目的

核验 300 km 禁止区加 50 km 过渡带是否切入已登记的高价值地点。普通岛屿、
聚落、建筑和道路不自动构成否决条件；本轮只把登记的世界遗产作为已确认的
高价值参考点，后续还需要补充旅游景点和 Tellus 自有玩法 POI。

## 数据与方法

- 边界：`build/geography/results/quarantine-boundaries-32.geojson`，即当前
  n=128 实际整格隔离边界，不是理想圆帽。
- UNESCO：World Heritage List DataHub CSV，1,273 条记录；快照信息见
  [`poi-dataset-manifest.json`](poi-dataset-manifest.json)。
- 每条 UNESCO 记录使用 `geo_point_2d` 和串列遗产的 `components_list` 坐标，
  转换到每个拟合顶点的局部等积坐标后，与排除区和过渡区分别求交。
- OSM 当前快照仍是有限的西太平洋 `place`/`building`/`highway` 探针，不能
  用于证明没有旅游标签对象。

## 结果

扫描 1,273 条 UNESCO 记录，命中 2 条，均位于顶点 4（孟加拉湾）的
`TRANSITION` 区：

| 顶点 | 状态 | 地点 | 类型 | 国家 |
|---:|---|---|---|---|
| 4 | TRANSITION | The Sundarbans | Natural | Bangladesh |
| 4 | TRANSITION | Sundarbans National Park | Natural | India |

可复核输出：[UNESCO 命中明细](unesco-poi-intersections.csv)。原始输出保留在
`build/geography/results/unesco-poi-intersections.csv`。

## 解释

这两个地点不是普通聚落计数，而是正式登记的自然遗产/国家公园，具有明确的
旅游和保护价值。因此，如果过渡区在运行时也被视为不可用，300+50 km 方案
不能通过“没有高价值地点受影响”的严格门槛。

这项结果只证明存在已登记的高价值地点；其余 UNESCO 记录未命中不能证明没有
地方旅游景点。OSM 的完整旅游标签、Wikidata 地理实体和 Tellus 自有玩法 POI
仍需继续补齐。

## 可复现命令

```powershell
& build/geography/venv/Scripts/python.exe tools/geography/fetch_reference_pois.py

& build/geography/venv/Scripts/python.exe tools/geography/score_reference_pois.py `
  --unesco-csv build/geography/unesco-whc001.csv `
  --output build/geography/results
```

该脚本不联网，只读取已经保存的 UNESCO 快照和当前边界文件。

## 缩小禁止区的候选比较

保持 50 km 过渡带不变，对已有边界文件中的多个禁止半径重复评分。陆地数值
取 `coverage-scenarios.csv` 的完整分辨率、ice-front 行；POI 数值按 UNESCO
记录去重，命中状态取同一地点的最严格状态。

| 禁止半径 | 最小普通单元尺度 | 整格隔离陆地包络 km² | 隔离球面比例 | UNESCO 排除区 | UNESCO 过渡区 |
|---:|---:|---:|---:|---:|---:|
| 150 km | 0.5455 | 4.84 | 0.4061% | 0 | 0 |
| 175 km | 0.5584 | 2,510.05 | 0.4813% | 0 | 0 |
| 200 km | 0.5710 | 2,520.11 | 0.5851% | 0 | 0 |
| 250 km | 0.5944 | 9,967.63 | 0.7794% | 0 | 0 |
| 300 km | 0.6125 | 42,623.11 | 0.9759% | 0 | 2 |
| 360 km | 0.6333 | 112,967.95 | 1.2148% | 2 | 2 |

最小普通单元尺度来自同一份 n=128 隔离网格摘要；数值越高，表示切除顶点附近
低尺度单元越充分，不代表完整玩法验收。

50 km 和 100 km 方案的当前整格陆地包络为 0，但半径过小，暂不作为主设计
候选。按目前已登记高价值地点的严格门槛，300+50 和 360+50 应排除。若需要
保留更多数值缓冲，**250+50 是当前首选候选**：最小普通单元尺度为 0.5944，
且本次 UNESCO 没有命中；若优先减少陆地损失，**200+50 是低损失候选**，但
最小普通单元尺度降至 0.5710。两者都还不能替代完整 OSM 旅游标签、Wikidata
和玩法 POI 核验。

可复核输出：[候选汇总](unesco-poi-scenario-summary.csv) 和
[候选命中明细](unesco-poi-scenario-intersections.csv)。原始输出保留在
`build/geography/results/`。批量评分入口为
`tools/geography/evaluate_reference_poi_scenarios.py`。

## 裁决与下一步

本轮选定 **250 km 禁止区＋50 km 过渡带**。200＋50 km 只保留作备用对照，
300＋50 km 和 360＋50 km 不再继续投入。

截至 2026-10-26，250＋50 km 仍是设计裁决，运行时默认值与 datapack 工具尚未
从 300＋50 km 同步；已有边界测试不能记作 250＋50 km 的验收。已完成的运行时、
边界和地形验证以及下一步渲染工作见
[客户端验收记录](../../cube-net-client-acceptance.md)。OSM、Wikidata 和玩法 POI
覆盖仍是后续补充项。
