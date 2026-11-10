# 终审记录

只读独立复核检查了原生Earth对照、公共规则、两版接线、逐piece应用、神殿Mixin及验收口径；本文件是复核结论记录，不替代原始测试或实机报告。

1. 首轮发现P2：完整bbox查询在粗比例下可能构造百万级固定zoom tile列表。`NON_BLOCKING`未限制同步tile分配。
2. 补齐：`OsmGeoQueryBudget`在至多两个Mercator窗口以long累计inclusive访问数；两source初始化后使用实际queryZoom；每启用family完整查询上限256。超预算不截断、不改zoom、返回未知；road与area分别最多256访问，unused water不查询；地标继续独立执行。
3. 最终只读复核确认该P2的代码路径已关闭。最终运行测试/源码哈希以final目录为准，首轮615/606和首次烟测仅是预算修复前attempt1。
4. 未发现额外的seed/salt、signed overflow/shift、upper-middle center、far-edge envelope、reference/KEEP identity、MOVE、monument ocean/depth/sea/clamp错误。
5. 已知继承限制：原有村庄/府邸/营地surface filter仍把未知高程映射到minY；本轮保持原代码，该项不混同于新地下/神殿adapter的未知数据保留逻辑。自然starts、整座物化与独立进程重启仍未纳入实机完成声明。
