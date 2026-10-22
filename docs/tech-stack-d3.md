# MockVerse 技术栈实证检查报告（D3 附件）

> 检查日期：2026-10-24（D3 收官）
> 方法：`mvn dependency:tree` 导出 myfirst-agent 运行时依赖树（214 个 jar），逐层归类
> 用途：①简历技术栈的真实底账 ②后续开发的风险预警 ③面试"你了解 ADK 架构吗"的实证素材

## 一、已实证运行时技术栈

| 层 | 技术 | 版本 | 来源 | 状态 |
|---|---|---|---|---|
| 语言/运行时 | JDK | 21.0.5 LTS | Oracle | ✅ D1 |
| Agent 框架 | google-adk | 1.13.0 | S级①主体 | ✅ D3 三连测 |
| MCP 官方 SDK | io.modelcontextprotocol.sdk:mcp | 2.0.0（mcp-core + jackson3 绑定） | S级③，adk-java 传递 | ✅ 依赖树实证 |
| 模型桥接 | google-adk-langchain4j / langchain4j-core | 1.13.0 / 1.12.2 | contrib 适配器 | ✅ D3 实跑 |
| 模型客户端 | langchain4j-open-ai（→DeepSeek） | 1.12.2 | [OI] 兼容端点 | ✅ D3 实跑 |
| 响应式 | RxJava 3 / Project Reactor | 3.1.12 / 3.7.0 | adk / MCP SDK | ✅ / 🟢未直接用 |
| JSON | Jackson 2.20.2 + Jackson 3.0.4 | 双版本隔离共存 | 各 SDK 自带 | ⚠️ 见风险1 |
| 工具库 | Guava 33.5.0 | — | adk 依赖 | ✅ |
| 日志 | SLF4J 2.0.17 | — | 门面模式 | ✅ |

## 二、三个新发现（计划外情报）

1. **adk-java core 是"全家桶"**：传递引入 Anthropic Java SDK 2.15.0、google-cloud-speech/aiplatform、
   docker-java 3.7.0、gRPC 1.76.2 全套。jar 偏大但功能无碍；面试讲 ADK 架构时的"真读过"证据。
2. **Jackson 2/3 双版本共存**：MCP SDK 2.0.0 用 tools.jackson 3.0.4（新 groupId），
   其余用 com.fasterxml 2.20.2。**D18 写 MCP 判题服务时注意序列化 API 差异**。
3. **docker-java 已在 classpath**：adk core 内置沙箱执行能力，D18 判题服务若做"代码沙箱"
   可能零新依赖（届时验证）——潜在加分项。

## 三、计划技术栈落地进度

Spring Boot 3.5(D8) / MySQL(D8) / pgvector(D10) / Kafka(D20) / Redis/Nacos/Nginx(D22-23) 均按计划待进场；
MCP 判题服务因 SDK 已就位，D18 风险下调。

## 四、结论

- 三件套实证闭环，无漂移（与 audit-s-level.md 一致）
- 无依赖冲突（Jackson 双版本为有意隔离；RxJava/Reactor 并存为 Reactive Streams 兼容设计）
- 依赖树原始日志：/tmp/dep-tree.log（如需复查可重新执行 mvn dependency:tree）
