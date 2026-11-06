# Cube-Net deferred 世界设置：min/max altitude 判定

## min_altitude / max_altitude

- **判定**：继续 deferred。两项是 EarthGeneratorSettings 的地形数据裁切语义，不是 Cube-Net 生成器自身的坐标边界。Cube-Net 世界的可用垂直区间由 datapack 的 `dimension_type`（`min_y`、`height`，以及对应的 `logical_height`）拥有；生成器的 `min_y` / `height` 必须与该维度类型一致。
- **实现现状**：不新增会与维度类型重复的 Cube-Net altitude 字段。Java writer 使用固定的 section-aligned `min_y=-2032`、`height=4064`；Python writer 在写盘前校验 `min_y`、`height` 的 section 对齐、范围及 `sea_level` 包含关系。两个生成器 codec/构造器也会在加载期校验垂直区间，非法值以明确异常终止，而不是截断。
- **限制**：当前 datapack API 不能用 `min_altitude` / `max_altitude` 表达 DEM 高程裁切；直接把它们映射成生成器截断会让 `dimension_type` 与材质化列的边界产生歧义。需要可配置 altitude 时，应由 datapack 同时调整 `dimension_type.min_y` / `height`，并由加载期校验确保 generator 与 dimension type 完全一致。
- **验证**：待补充完整 deferred-settings 文档与离线构建验证。
