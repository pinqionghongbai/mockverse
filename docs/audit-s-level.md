# S 级三件套符合度审计（D3 后中期审计）

> 审计日期：2026-10-24（D3 收官时）
> 审计问题：MockVerse 项目主体是否真实建立在三个 S 级项目之上？
> 审计方法：不凭调研印象，直接查证 pom 依赖与源码 import（证据链可复核）
>
> ⚠️ **本文档有 D7 复审勘误，以文末【D7 复审】一节为准。** 下面正文中关于 a2a-java 关系与 `webservice` 子模块的表述已被推翻，保留原文仅为留存审计演进过程。

## 结论总表

| S 级项目 | 与主体的关系 | 证据 | 符合度 |
|---|---|---|---|
| google/adk-java | **主体骨架**，全部业务 Agent 用它构建 | D2 构建 core 7:14 通过；D3 三连测实跑；labs/deepseek-agent 归档 | ✅ 完全符合（已实跑） |
| modelcontextprotocol/java-sdk (MCP) | **通过 adk-java core 传递依赖真实接入** | core/pom.xml 显式依赖 `io.modelcontextprotocol.sdk:mcp` 2.0.0；core 源码 McpAsyncToolset.java import `io.modelcontextprotocol.client.McpAsyncClient`（官方 SDK 的客户端类） | ✅ 符合（D4 跑 mcpfilesystem 即实证） |
| a2aproject/a2a-java | ~~**adk-java 不依赖它**——adk-java 的 a2a 模块是自带实现（自研 JSON-RPC + AgentCard 解析）~~ ❌ **已被 D7 复审推翻**：`google-adk-a2a` 直接依赖 `io.github.a2asdk:a2a-java-sdk-*:0.3.2.Final`，ADK 只是集成层 | 原证据 grep 的是新 groupId `a2aproject`，漏了老 groupId `io.github.a2asdk`；见文末【D7 复审】 | ✅ 实际为**强依赖**（协议层用官方 SDK） |

## 重要纠错（推翻此前调研印象）

此前对 a2a/README.md 的调研转述称"A2AClient 默认 HTTP 实现（基于 A2A SDK）"——**与 pom 证据不符**。
~~adk-java 的 a2a 模块（含 webservice 子模块）是 Google 自带的协议实现，classpath 里不会自动出现 a2a-java。~~
❌ **D7 复审推翻**：`a2a/pom.xml:19` 明确声明 `<a2a.sdk.version>0.3.2.Final</a2a.sdk.version>`，
`RemoteA2AAgent.java` import 的 `io.a2a.client.Client` / `io.a2a.spec.AgentCard` 全部来自该 SDK——
**a2a-java 一直在 classpath 上**，只是版本老（0.3.x）且 groupId 是 `io.github.a2asdk`。
另：`a2a/webservice` 子模块**不存在**（`a2a/` 下只有 README/pom/src/target）。

## 对 30 天计划的影响与修正

1. ~~**D5-D6 / D16-D17（A2A 化）**：用的全是 adk-java 自带 A2A 实现~~ → ❌ **D7 复审修正**：用的是 **ADK 集成层 + 官方 SDK 0.3.x 线**。Spring Boot 传输层路线**没有官方模块可借**（`a2a/webservice` 不存在），第三周须自己写 Controller 调 `AgentExecutor`。
2. **a2a-java 的真实角色**（~~降级为"协议参考 + 轻量实证"~~ → D7 复审修正为"**同一个 SDK 的另一条版本线**"）：
   - extras（JPA 任务存储、Kafka 复制队列）仍作为 D20 的**设计参考**（源码阅读对象，非依赖）
   - ~~"对比了 A2A 协议的官方 Java SDK 与 adk-java 内置实现，选型后者"~~ → ❌ **这句不能写**，会被拆穿。正确表述见 `docs/a2a-comparison-d6.md` 第六节。
3. **MCP java-sdk（D4/D18）**：D4 的 mcpfilesystem 直接实证；D18 独立判题 MCP Server 仍按计划用官方 SDK 独立构建（`io.modelcontextprotocol.sdk:mcp` 2.0.0，锁版本）。

## 版本锁定表补充（本次审计新增实证）

- io.modelcontextprotocol.sdk:mcp **2.0.0**（adk-java 父 pom 属性 mcp.version，m2 仓库已落地）

## 简历表述修正对照

| 原表述（有夸大风险） | 修正后表述（有实证支撑） |
|---|---|
| 基于 A2A 官方 Java SDK 的多智能体互联 | 基于 A2A 协议（adk-java 官方实现）的多智能体互联；对比评估过 a2a-java 官方 SDK |
| 集成 MCP 官方 Java SDK | ADK 的 MCP 工具层即构建于官方 MCP Java SDK 之上（core 传递依赖 2.0.0）；判题服务用官方 SDK 自建 |

**审计人**：浮浮酱 ｜ **下次复审**：D7 复盘时核对 D4-D6 是否补齐 a2a-java 实证

---

# 【D7 复审】本审计的两处错误（2026-10-27）

> 上面 D3 的审计结论**有两处事实错误**，保留原文作为审计痕迹，在此更正。
> 触发：D7 周复盘时按"文档必须能被 pom/源码复核"的标准重查。

## 错误 1：`google-adk-a2a` 并非"仅依赖基础库"

原文（第 13 行）："google-adk-a2a 依赖仅 guava/jackson/rxjava 等基础库" ❌

**实际 `a2a/pom.xml` 依赖：**

```xml
<dependency>
  <groupId>io.github.a2asdk</groupId>
  <artifactId>a2a-java-sdk-spec</artifactId>
  <version>${a2a.sdk.version}</version>   <!-- 0.3.2.Final -->
</dependency>
<dependency>
  <groupId>io.github.a2asdk</groupId>
  <artifactId>a2a-java-sdk-client</artifactId>
  ...
</dependency>
```

且 `RemoteA2AAgent.java` 直接 import `io.a2a.client.Client` / `io.a2a.spec.AgentCard` —— 这些正是官方 A2A Java SDK 的类（0.3.x 线的 `io.a2a.*` 命名空间）。

**D3 审计为什么错**：当时 grep 的是 `a2aproject` 关键字（新版 groupId），而老版 SDK 的 groupId 是 **`io.github.a2asdk`**，漏网。

## 错误 2：`a2a/webservice` 子模块不存在

原文（第 18、23、28 行）多次提到 "webservice 子模块" / "Spring Boot 传输层路线"。❌

**实际核查（adk-java 1.13.0，HEAD `aab51ef`）：**

- `a2a/` 下只有 `README.md`、`pom.xml`、`src/`、`target/`，**无 webservice 目录**
- `a2a/src/main` 下 13 个 java 文件，仅 4 个包：`agent` / `common` / `converters` / `executor`
- `grep -rin "spring" a2a/src a2a/pom.xml` → **零命中**
- README 提到的 `A2ASendMessageExecutor`、`A2AClient` 类**均不存在**

**结论**：`a2a/README.md` 描述的是比 1.13.0 更新（或不同分支）的代码结构。
（克隆为 `--depth 1` 浅克隆，无法用 git history 判断是"后来新增"还是"从未存在"。）

## 更正后的结论总表

| S 级项目 | 与主体的关系 | 符合度 |
|---|---|---|
| google/adk-java | 主体骨架 | ✅ 完全符合 |
| MCP java-sdk | 经 adk-java core 传递依赖接入（2.0.0） | ✅ 符合 |
| a2aproject/a2a-java | **adk-java 的 a2a 模块依赖其 0.3.2.Final**（groupId 为 `io.github.a2asdk`，包名 `io.a2a.*`）；1.x 线（`org.a2aproject.sdk`）为独立实证对象 | ✅ **实际关系比 D3 判断的更紧密** |

## 对计划的影响（修正 D3 的判断）

| D3 原判断 | D7 修正 |
|---|---|
| "Spring Boot 传输层路线仍是最优解" | ❌ adk-java 1.13.0 无 Spring Boot 传输模块；第三周需**自己写 Spring Boot 绑定**（调用 `AgentExecutor`） |
| "classpath 里不会自动出现 a2a-java" | ❌ 一直在 classpath 上（0.3.2.Final），只是版本老、包名不同 |
| "adk-java 的 a2a 模块是自带实现" | ❌ 是**集成层**，协议层用官方 SDK |

**正面影响**：简历可写"理解 A2A SDK 的版本演进（0.3.x → 1.x 报文不互通），并基于 ADK 集成层完成跨进程 Agent 协作"——比"用了某框架"有深度得多。

**审计人**：浮浮酱 ｜ 复审依据：`docs/a2a-comparison-d6.md` 第一节证据链
