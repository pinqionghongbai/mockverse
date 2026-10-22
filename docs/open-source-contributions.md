# 开源贡献记录（简历素材）

> 本文件收录可写入简历、且有公开链接佐证的开源贡献。每条都必须"点开链接即真"。

## 1. google/adk-java — Issue #1686（2026-10-22）

**链接**: https://github.com/google/adk-java/issues/1686（open，署名 pinqionghongbai）

**简历一句话**（英文表述供参考）:
> Reported a Windows-specific defect to google/adk-java (Google's official Agent Development
> Kit): MCP stdio toolsets with npm-shim commands (`npx`) hang silently for a measured
> 900s before failing; provided a 25-line public-API reproduction, root-cause analysis
> (npm `.cmd` shims × Win32 CreateProcess × 5-min initializationTimeout × retry), and
> two concrete fix suggestions.

**中文面试话术**:
"我在跑通 ADK 的 MCP 示例时发现 Windows 上会静默挂死，深入排查后定位是 npm 的 cmd shim
与 Java ProcessBuilder 的兼容问题，叠加框架 5 分钟初始化超时和重试机制，实测会挂整整
15 分钟才报错。我把完整根因分析和最小复现提给了 Google 官方仓库，issue 目前是 open
状态，并附了两个修复方案，维护者响应后我计划提 PR。"

**技术细节支撑**: 见 qa-manual.md Q14/Q16 + labs/mcp-own-test/ReproToolset.java + docs/issue-final-adk-java.md

**后续动作（待办）**:
- [ ] 若维护者回应 → 跟进讨论 / 提 PR（需签 Google CLA，线上电子签）
- [ ] 若被合并或标记 → 更新本文件状态与简历表述
- [ ] 可选：在 issue 下追加评论，补充长跑实测的精确数据（900,857ms）与三次 retry 完整日志——"报告后继续深挖"在开源社区是加分姿态

## 2.（预留）后续 PR / 其他贡献
