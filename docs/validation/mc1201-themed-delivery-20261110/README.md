# Fabric 1.20.1 主题交付与复现（2026-11-10）

## 当前完成范围

当前先考虑 **Minecraft/Fabric 1.20.1**。按全球旅行工程的实际地图/地形影响口径，区域生成与约定修正已完成：生物群系与雪/峡谷邻域、结构适配、树木/建成区地表设置、地质默认，以及实际生成、原生保存、独立进程重启和回退。原生所有细节、未用 loader 或 1.21.1 客户端移植不是当前完成前提。

M11 逐帧零跳变仍是独立的视觉体验验收项；已有服务端生成链和受控完整 payload 验收保持通过，不据此声称 M11 已通过。[旅行验收记录](../cube-net-travel-parity-20261110/README.md)和[最终结果](../cube-net-travel-parity-20261110/final/summary.json)为功能与证据依据。

## 主题历史的保留方式

交付分支为 `feat/cube-net-parity`，目标远端为 `origin`（`Void0312Aurora/Tellus-Optimized`）。本轮普通推送该功能分支，不重写远端历史，也不把功能分支自动合入 `main`。

先前停留的 `1c35ccbb2441fd146ceb28ee35700f684dec8ed3` 是恢复链的直接祖先。其后的 **40 个有序主题提交**已保存在本地恢复分支，最新为 `543eb1ede435b748e962d136de9db9768170aadf`；本轮直接承接原提交，保留作者、顺序和验收时序，不重新压成一个包含全部实现的大提交。这些提交按顺序形成可用基础，**不宣称每个历史提交都独立构建或可任意 cherry-pick**。

该链覆盖坐标与 settings/runtime 抽象、采样/决策/feature/write、地形与地下生产接线、非空原生生命周期、存档事务/硬中断恢复、实际生成对照、海平面存档兼容和可回退默认接入。已有成对的 1.21.1 源码及历史验收保留，不开展新的客户端移植，也不删掉共享实现的一半。

恢复链之后按三个源代码主题、一个证据保真主题及一个交付主题组织：

1. **结构适配与有界查询**：canonical/face 坐标、地下与神殿规则、whole-feature tile 准入，以及对应测试、真实 Mixin 烟测、失败尝试和精确回退材料。
2. **球面地质默认**：Macrostrat 默认/退出开关、GLiM 兜底与 cache-only 行为，以及对应回归。
3. **旅行地形与设置**：分类坐标、雪/峡谷邻域、树木/红蘑菇/建成区选项、Codec/原生保存、datapack 和严格 parity。包含已纠正的 DEM 审计，而非添加空壳 provider。
4. **验收原始字节保真**：为验证目录关闭 Git 换行转换，按本机留存原件存入 Git blob，使证据 SHA 在其他平台的克隆中仍可核对；保留三个既有 DFU 历史材料，不更改其 payload。
5. **1.20.1 收口与复现入口**：本说明、架构当前入口、仓库相对的测试选择脚本，以及临时 javac 参数文件忽略规则。

共享生成器文件的结构阶段取自已保留的 `after/` 精确快照，再在后一个主题中接入旅行阶段；不是按最终整文件随意暂存，从而把后续行为夹入第一个主题。主题提交身份与新鲜复核结果保存在本目录交付清单。

## 从当前仓库复现 1.20.1 回归

在仓库根目录运行：

```powershell
.\gradlew.bat '-PtellusTargets=mc1201' `
  --init-script tools/cube-net-travel-regression.init.gradle `
  :mc1201:test --rerun-tasks --offline --no-parallel --max-workers=1 --console=plain
```

[可移植选择入口](../../../tools/cube-net-travel-regression.init.gradle)读取仓库内[保留的测试选择列表](../cube-net-travel-parity-20261110/final/mc1201/selected-tests.json)，避开 Windows 的长命令限制；原验收目录内带绝对 TEMP 路径的 init/command 文件继续作为**历史执行原件**保留，不要求另一台机器使用相同用户名或临时目录。

`--offline` 依赖本机已有 Gradle/Minecraft/Fabric 缓存。首次准备依赖时可按本工程通常的在线构建方式准备，之后再运行同一测试组。真实 GIS 生成/重启控制器还需要明确提供外部历史世界、缓存与 provenance；Git 仓库保留其完整报告、hash、原生 payload 和日志，但不把原始世界、缓存或本机恢复会话打包推送。克隆仓库本身不等于重建了所有外部 GIS 输入。

## 文件保留与排除

- 原始 XML、SNBT、JSONL、压缩日志、失败尝试、来源 manifests 与源码 rollback ZIP 是已引用的验收材料，按原 bytes 保留；不因扩展名像构建产物就整类删除。
- 验证目录由根目录 [Git attributes](../../../.gitattributes) 的 `-text` 规则禁止换行转换。交付前实际用 Git blob bytes 核对旅行证据哈希；这与仅核对当前Windows工作树文件不同。原始源码 SHA 绑定当时的源码字节，不把它误当所有机器源码换行方式的保证。
- `.gradle`、`build`、服务器目录、运行世界、缓存、JAR/编译输出不纳入本轮待提交清单；本机构建 JAR 仍可单独使用，不作为源码提交中的二进制包。
- 根目录的临时 `javac.*.args` 加入忽略规则，原文件留本地；没有删除用户文件。
- 恢复提交中三个历史 DFU 验收文件在工作树缺失，本轮按已有 Git 对象恢复，不误提交成删除。
- 对待提交文本及 ZIP/gzip 做了凭据模式检查，未发现匹配；这不是完整的秘密不存在证明。证据保留用户名、路径、localhost 端口、seed/坐标、进程 ID 等测试元数据，以保持可追溯性。
- 此前报告的 HEAD/index hash 是**当时验收基线**，提交后不再冒称它是当前 HEAD。原始验收文件和其证据校验表不为新提交身份而重写。
