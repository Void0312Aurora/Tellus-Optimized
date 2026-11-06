# Cube-Net 对接状态

本文只汇总当前接口边界；字段和行为细节以[生成器文档](cube-net-generator.md)及源码为准。

## 已对接

- datapack 工具已将 `CubeNetBiomeSource.Settings` 的洞穴、Deep Dark、地下深度、矿脉、地质石块、洞穴到达地表、道路和建筑开关暴露为 CLI 参数。
- `CubeNetChunkGenerator` 从 biome source settings 解析这些开关，并将洞穴生成设置传给 `TellusBiomeGenerationSettings`。
- Overture `subtype=ocean` 是海洋分类权威；非阻塞查询的未完成/失败状态保留为 `UNKNOWN`，并由 `CachingGeodeticOceanAuthority` 做有界地理缓存。
- Cube-Net 实时管理器识别 `tellus.cubeNet.realtimeTime`、`tellus.cubeNet.realtimeWeather`、`tellus.cubeNet.historicalSnow` 三个 opt-in 系统属性。
- GeoTP 地图入口和 `/tellus weather` 状态查询已在命令/UI路径中存在；实时天气、时间和历史积雪仍由运行时数据与系统属性决定，不由 datapack settings 决定。

## 待对接或需单独验证

- 运行时属性没有对应的 datapack JSON 字段；若需要按世界保存，应设计独立的持久化配置协议。
- GeoTP 的传送准备、缓存命中和跨面运行时表现需要客户端/服务器验收，不能仅由生成器 `--help` 或 JSON 编码验证。
- 实时天气与历史积雪依赖 Open-Meteo、地理锚点和客户端 payload；离线或数据未就绪时的回退应继续按运行时代码观察。
- 道路/建筑的跨面投影、原版生成过滤与洞穴采样属于不同路径；不要把 LOD、原生列、apron 或 OSM 查询的行为概括成“始终同一决策”，除非对应代码路径已逐项验证。
