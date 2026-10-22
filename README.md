# MockVerse —— 基于 A2A / MCP 的多智能体面试陪练平台

> 30 天实习求职冲刺项目 · 开始于 2026-10-22 (D1)

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
| a2a-java-sdk | （D5 使用后填写） | |
| mcp java-sdk | （D18 使用后填写） | |
| langchain4j | （D3 使用后填写） | |
| Spring Boot | （D8 建工程时填写） | |
| Ollama + 模型 | 0.40.8（qwen2.5:7b 拉取受代理 fake-ip DNS 污染阻塞，降级备胎，详见 docs/ollama-issue.md） | 2026-10-22 |
| DeepSeek API | deepseek-chat（deepseek-flash 引擎，2026-10-22 验证连通） | 2026-10-22 |

## 安全须知

API Key 一律通过环境变量注入（`DEEPSEEK_API_KEY`），**任何情况下不得提交到 Git**。
`.env` 已被 .gitignore 排除。

## 目录规划

```
mockverse/
├── docs/            # 架构图、E-R 图、面试 QA 手册
│   └── journal/     # 每日复盘日志
├── monolith/        # 第二周单体版（D8 起）
└── services/        # 第三周微服务版（D15 起）
```
