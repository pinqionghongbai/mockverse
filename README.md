# MockVerse —— 基于 A2A / MCP 的多智能体面试陪练平台


## 项目简介

为找实习的求职者提供 AI 模拟面试陪练：面试官 Agent 出题追问、知识库 Agent（RAG）答疑、
简历诊断 Agent 结构化点评、判题工具（MCP）点评代码。多个 Agent 以独立微服务形式部署，
通过 A2A 协议（JSON-RPC）互联。

## 核心依赖（原仓库位置）

| 组件 | 仓库 | 用途 |
|---|---|---|
| google/adk-java | https://github.com/google/adk-java | Agent 编排主骨架 |
| a2aproject/a2a-java | https://github.com/a2aproject/a2a-java | A2A 协议互联（JSON-RPC） |
| modelcontextprotocol/java-sdk | https://github.com/modelcontextprotocol/java-sdk | MCP 判题工具服务 |
| langchain4j | https://github.com/langchain4j/langchain4j | RAG + 模型接入 |

## 版本锁定表（D1 填写，中途禁止变更）

| 组件 | 版本 | 锁定日期 |
|---|---|---|
| JDK | 21.0.5 LTS (Oracle) | 2026-10-22 |
| Maven | 3.9.8 | 2026-10-22 |
| Git | 2.49.0.windows.1 | 2026-10-22 |
| Docker | 29.4.1 | 2026-10-22 |
| google-adk | 1.13.0（源码克隆至 E:\cluadeworkbase\adk-java，2026-10-23） | 2026-10-23 |
| a2a-java-sdk | 两条版本线：**0.3.2.Final**（`io.github.a2asdk`，A2A 协议 0.3.0；adk-java a2a 模块的底层 SDK，本项目主链路，示例覆写为 0.3.0.Beta1）vs **1.4.0.Final**（`org.a2aproject.sdk`，A2A 协议 1.0，D6 跑通 Hello World 实证）。两者报文不互通 | 2026-10-26 |
| mcp java-sdk | **2.0.0**（adk-java core 传递依赖，m2 已落地；D3 审计实证） | 2026-10-24 |
| langchain4j | 1.12.2（父 POM 显式 `<version>` 锁定；桥接模块 `google-adk-langchain4j` 1.13.0，2026-10-24 验证通过，D9 复用） | 2026-10-24 |
| Spring Boot | **3.3.1**（`<parent>` 引入；选 3.3.x 而非 4.0.x 是为了与 MyBatis-Plus 3.5.7 的 `spring-boot3-starter` 稳定配套） | 2026-10-28 |
| MyBatis-Plus | **3.5.7**（`mybatis-plus-spring-boot3-starter`，Boot 3 专用，不要用 Boot 2 的 `mybatis-plus-boot-starter`） | 2026-10-28 |
| MySQL | **8.0**（Docker 容器 `mockverse-mysql`，映射 3307；本机 3306 另有 MySQL 26.7 实例，避免冲突） | 2026-10-28 |
| pgvector | **pgvector/pgvector:pg16**（Docker 容器 `mockverse-pg`，映射 5433；扩展版本 0.8.7） | 2026-10-31 |
| Embedding 模型 | **bge-small-zh-v1.5**（本地 ONNX，随 `langchain4j-embeddings-bge-small-zh-v15:1.12.2-beta22` 分发，512 维，不依赖外部 API） | 2026-10-31 |
| Ollama + 模型 | 0.40.8（qwen2.5:7b 拉取受代理 fake-ip DNS 污染阻塞，降级备胎，详见 docs/ollama-issue.md） | 2026-10-22 |
| DeepSeek API | deepseek-chat（deepseek-flash 引擎，2026-10-22 验证连通） | 2026-10-22 |

## 安全须知

API Key 一律通过环境变量注入（`DEEPSEEK_API_KEY`），**任何情况下不得提交到 Git**。
`.env` 已被 .gitignore 排除。

## 目录规划

```
mockverse/
├── STATUS.md            # ★ 项目状态快照（新会话/新工作日唯一入口）
├── docs/                # 架构图、E-R 图、面试 QA 手册
│   └── journal/         # 每日复盘日志
├── labs/                # 各里程碑代码归档（源模块在 adk-java/examples/ 下）
├── interview-service/   # 8081 面试官 Agent（A2A 服务端 + SSE + 异步判题提交）
├── knowledge-service/   # 8082 RAG 知识库 Agent（pgvector 直连）
├── resume-service/      # 8083 简历诊断 Agent
├── judge-service/       # 8084 MCP 判题 Server（javac/java）+ Kafka 判题消费者
├── dev.env              # 本地库连接（被 .gitignore 排除，勿提交）
└── pom.xml              # 父 POM（四服务 + 统一依赖版本）

注：D8 起的单体 mockverse-monolith 已在 D21 退役，基线保留在 tag `v0.1-monolith`。
    取回方式：`git checkout v0.1-monolith`（只读参考，不再维护）。
```
