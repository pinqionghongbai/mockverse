# 开源贡献记录（简历素材）

> 本文件收录可写入简历、且有公开链接佐证的开源贡献。每条都必须"点开链接即真"。

## 1. google/adk-java — Issue #1686（2026-10-22）

**链接**: https://github.com/google/adk-java/issues/1686（open，署名 pinqionghongbai）
**进展**: 2026-10-22 10:21 追加首条技术评论（issue 正文 + follow-up 评论两部分构成完整证据链）

**评论补强内容**（构成完整贡献的三块）:
1. 精确化 retry 语义：MAX_RETRIES=3 实为 3 次总尝试（1 初始 + 2 重试），900,857ms = 3×300s 严丝合缝
2. 复现完整性：附件说明 + Windows-only 边界声明（Linux/macOS 不受影响）
3. **跨生态对齐分析**（命中该仓库 CONTRIBUTING 的"与 adk-python 对齐"原则）:
   Python MCP SDK 在 SDK 层有 `get_windows_executable_command`（shutil.which 解析 .cmd/.bat/.exe/.ps1），
   Java MCP SDK 的 StdioClientTransport 无对应物 → adk-java 继承了缺陷 →
   建议修复可落在本仓库 DefaultMcpTransportBuilder 或上游 Java MCP SDK，实现与 Python 生态对齐

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
- [ ] 签 Google CLA（https://cla.developers.google.com/，线上电子签，提 PR 前必须）
- [ ] 若维护者回应 → 跟进讨论 / 提 PR
- [ ] PR 路线（若走 adk-java 侧）: fork → 改 DefaultMcpTransportBuilder（~15 行：Windows 检测 + shutil.which 式解析）→
      记得 CONTRIBUTING 三条硬规则：google-java-format 自动格式化、**单提交**（amend/rebase -i）、AI 初稿需人类精炼
- [ ] 若被合并或标记 → 更新本文件状态与简历表述
- [ ] 定期回访 issue（建议每 2-3 天，D7/D14 复盘时顺带检查）

## 2.（预留）后续 PR / 其他贡献
