# S 级三件套符合度审计（D3 后中期审计）

> 审计日期：2026-10-24（D3 收官时）
> 审计问题：MockVerse 项目主体是否真实建立在三个 S 级项目之上？
> 审计方法：不凭调研印象，直接查证 pom 依赖与源码 import（证据链可复核）

## 结论总表

| S 级项目 | 与主体的关系 | 证据 | 符合度 |
|---|---|---|---|
| google/adk-java | **主体骨架**，全部业务 Agent 用它构建 | D2 构建 core 7:14 通过；D3 三连测实跑；labs/deepseek-agent 归档 | ✅ 完全符合（已实跑） |
| modelcontextprotocol/java-sdk (MCP) | **通过 adk-java core 传递依赖真实接入** | core/pom.xml 显式依赖 `io.modelcontextprotocol.sdk:mcp` 2.0.0；core 源码 McpAsyncToolset.java import `io.modelcontextprotocol.client.McpAsyncClient`（官方 SDK 的客户端类） | ✅ 符合（D4 跑 mcpfilesystem 即实证） |
| a2aproject/a2a-java | **adk-java 不依赖它**——adk-java 的 a2a 模块是自带实现（自研 JSON-RPC + AgentCard 解析） | 全仓库 pom.xml grep `a2aproject` 零命中；google-adk-a2a 依赖仅 guava/jackson/rxjava 等基础库 | ⚠️ 关系最弱，需修正定位 |

## 重要纠错（推翻此前调研印象）

此前对 a2a/README.md 的调研转述称"A2AClient 默认 HTTP 实现（基于 A2A SDK）"——**与 pom 证据不符**。
adk-java 的 a2a 模块（含 webservice 子模块）是 Google 自带的协议实现，classpath 里不会自动出现 a2a-java。
因此：**"跑 adk-java 的 A2A 示例"≠"使用 a2a-java 项目"**。简历表述必须据此修正。

## 对 30 天计划的影响与修正

1. **D5-D6 / D16-D17（A2A 化）**：用的全是 adk-java 自带 A2A 实现——保留不变（Spring Boot 传输层路线仍是最优解），但相关文档不再写"基于 a2a-java"。
2. **a2a-java 的真实角色**（降级为"协议参考 + 轻量实证"）：
   - extras（JPA 任务存储、Kafka 复制队列）仍作为 D20 的**设计参考**（源码阅读对象，非依赖）
   - **新增轻量任务（约 1 小时，安排进 D6）**：clone a2a-java 并跑通其 Hello World server/client，
     亲手对比官方 SDK 与 adk-java 内置实现的差异 → 简历可真实表述
     "对比了 A2A 协议的官方 Java SDK 与 adk-java 内置实现，选型后者（Spring Boot 传输层集成成本更低）"
     ——这句"选型对比"比单纯罗列技术更能经受面试追问。
3. **MCP java-sdk（D4/D18）**：D4 的 mcpfilesystem 直接实证；D18 独立判题 MCP Server 仍按计划用官方 SDK 独立构建（`io.modelcontextprotocol.sdk:mcp` 2.0.0，锁版本）。

## 版本锁定表补充（本次审计新增实证）

- io.modelcontextprotocol.sdk:mcp **2.0.0**（adk-java 父 pom 属性 mcp.version，m2 仓库已落地）

## 简历表述修正对照

| 原表述（有夸大风险） | 修正后表述（有实证支撑） |
|---|---|
| 基于 A2A 官方 Java SDK 的多智能体互联 | 基于 A2A 协议（adk-java 官方实现）的多智能体互联；对比评估过 a2a-java 官方 SDK |
| 集成 MCP 官方 Java SDK | ADK 的 MCP 工具层即构建于官方 MCP Java SDK 之上（core 传递依赖 2.0.0）；判题服务用官方 SDK 自建 |

**审计人**：浮浮酱 ｜ **下次复审**：D7 复盘时核对 D4-D6 是否补齐 a2a-java 实证
