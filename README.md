# MockVerse —— 基于 A2A / MCP 的多智能体面试陪练平台

> 面向 Java 后端求职者的 AI 模拟面试系统：面试官 Agent 出题追问、RAG 知识库 Agent 答疑、
> 简历诊断 Agent 结构化点评、MCP 判题工具真实编译运行代码。
> 四个 Spring Boot 服务，**三个通过 A2A 协议互联**，判题服务以 **MCP 工具**形式接入。

**10 个容器一条命令起全**（五个基础设施 + 四个服务 + Nginx），浏览器打开 `http://localhost/`
即可开局、对话、判题。

---

## 一、架构

```
                         ┌──────────────────────────────┐
   浏览器 / 手机  ──────▶ │  Nginx :80                   │
                         │  ├─ /            前端静态页  │
                         │  ├─ /api/*       反向代理    │
                         │  ├─ /a2a/*       A2A 入口    │
                         │  └─ SSE 路径单独关缓冲       │
                         └──────────┬───────────────────┘
                                    │
              ┌─────────────────────┼─────────────────────┐
              ▼                     ▼                     ▼
   ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
   │ interview :8081  │  │ knowledge :8082  │  │ resume :8083     │
   │ 编排树根          │  │ RAG 知识库       │  │ 简历诊断          │
   │ root_coordinator │  │ knowledge_agent  │  │ resume_agent     │
   │  ├ interviewer   │  │                  │  │                  │
   │  │  (进程内)      │  │                  │  │                  │
   │  ├ knowledge ────┼──┼─▶ A2A (JSON-RPC) │  │                  │
   │  └ resume ───────┼──┼──────────────────┼──┼─▶ A2A            │
   └────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘
            │ MCP (tools/call)    │ pgvector            │ MySQL
            ▼                     ▼                     ▼
   ┌──────────────────┐  ┌──────────────┐  ┌──────────────────┐
   │ judge :8084      │  │ PostgreSQL   │  │ MySQL :3307      │
   │ MCP Server       │  │ + pgvector   │  │ 会话/题库/历史    │
   │ 真 javac + java  │  │ 50 块语料     │  │ 5 张表            │
   └────────┬─────────┘  └──────────────┘  └──────────────────┘
            │ 消费 judge-request
            ▼
   ┌──────────────┐     ┌──────────────┐
   │ Kafka :9092  │     │ Redis :6379  │
   │ 异步判题队列  │     │ 缓存/锁/限流  │
   └──────────────┘     └──────────────┘
```

**两条判题链路并存**（同一个 `JavaJudge`）：

| 链路 | 路径 | 场景 |
|---|---|---|
| 同步（MCP） | interviewer_agent → `judge_code` 工具 → judge :8084 | Agent 在对话中自主判题（阻塞 0.6~10s） |
| 异步（Kafka） | `POST /api/interview/{sid}/judge` → `judge-request` → judge → `judge-result` → SSE 回推 | 前端判题面板（提交 271.8ms 返回 `PENDING`） |

---

## 二、快速启动（clone 即跑）

### 前置

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | **21** | 编译与运行 |
| Maven | 3.9+ | 构建四个 jar |
| Docker Desktop | 任意近期版本 | 起 10 个容器 |
| DeepSeek API Key | —— | 填进 `.env` |

### 步骤

```bash
git clone <repo-url> mockverse && cd mockverse

# 1) 配置（唯一需要手动填的一步）
cp docker/.env.example .env
#    编辑 .env，填 MYSQL_PASSWORD / PG_PASSWORD / DEEPSEEK_API_KEY

# 2) 构建 jar（镜像里不跑 Maven，见下方说明）
mvn package -DskipTests

# 3) 起全栈
docker compose up -d

# 4) 等就绪
docker compose ps
```

**耗时口径**（别把两个数混在一起）：
- **首次**：`compose up` 要构建 4 个服务镜像（每个约 35~40s）+ 起容器，合计**数分钟**；
- **之后**（镜像已构建、容器已存在）：`up -d` 约 **39s**，全健康再 7s（D26 冷启动实测）。

打开 **http://localhost/** —— 开局 → 对话 → 贴代码判题。

### 验证是否真的起来了

```bash
# 一条命令验十容器 + 四端点 + Redis/Nacos/Kafka
PYTHONUTF8=1 python -I docs/evidence/D26/local_acceptance.py

# 或手动
curl http://localhost/healthz/interview   # 应返回 status=UP
```

### 关于"镜像里不跑 Maven"

`docker/service/Dockerfile` 用 `COPY ${JAR_FILE}` 把宿主机构建的 jar 放进镜像。
**这是刻意的取舍**：容器内构建 Maven 在 16G 机器上会吃满内存（D22 实测进程被系统回收，
没有异常栈、只有进程消失）。代价就是多一步 `mvn package`。

> 想跳过这一步：把 Dockerfile 改成多阶段构建（`FROM maven:3.9-eclipse-temurin-21` 先构建）。
> 未采用是因为它会显著拉长首次 `compose up` 的时间，且对本地开发没有收益。

### 首次启动会自动做三件事

| 事项 | 机制 | 位置 |
|---|---|---|
| **建库建表**（5 张表） | MySQL 入口脚本：`docker-entrypoint-initdb.d/01-schema.sql` | `docker-compose.yml` 挂载 `interview-service/.../db/schema.sql` |
| **灌题库**（13 道） | `QuestionSeeder`：按题干幂等去重，启动时补种 | `interview-service` |
| **灌语料**（50 块） | `KnowledgeService.ensureIndexed()`：库空才 embedding（本地 ONNX） | `knowledge-service` |

> ⚠️ 建表脚本只在 **`mysql-data` 卷为空**时执行。已有库不会覆盖——
> 想从零重来：`docker compose down -v`（**会删数据**）。

---

## 三、技术选型与理由

| 选型 | 选择 | 为什么不是别的 |
|---|---|---|
| **Agent 框架** | `google/adk-java` 1.13.0 | 官方维护、`LlmAgent` + `FunctionTool` + Runner 三件套完整；A2A 集成层开箱可用 |
| **协议** | **A2A**（JSON-RPC 0.3.0） | Agent 间标准互操作；有服务发现（AgentCard）与任务状态机，比裸 HTTP 语义完整 |
| **工具协议** | **MCP**（Streamable HTTP） | 工具独立进程/服务，可动态发现；判题服务可单独部署与降级 |
| **模型** | **DeepSeek `deepseek-chat`** | `deepseek-reasoner` **不支持 function calling**（实测），多 Agent 工具链路下 chat 是唯一可行项；延迟也是 chat 更优（4.8s vs 5.8s） |
| **模型接入** | **LangChain4j 桥接** | 换模型只动 1 行 `model()` + 2 个依赖（Gemini→DeepSeek 已验证） |
| **Web 框架** | Spring Boot 3.3.1 | 与 MyBatis-Plus 3.5.7 的 `spring-boot3-starter` 稳定配套（不用 4.0.x） |
| **ORM** | MyBatis-Plus 3.5.7 | 单表 CRUD 零 SQL；`selectPage` 等坑已记录在 QA 手册 |
| **向量库** | **pgvector**（PostgreSQL 16） | 一个库同时存关系数据与向量，不引入独立向量数据库 |
| **Embedding** | **bge-small-zh-v1.5**（本地 ONNX，512 维） | **不依赖外部 API**：DeepSeek 无 `/embeddings` 接口、Ollama 在本机被 DNS 问题卡死 |
| **异步队列** | **Kafka 3.9.0**（KRaft 单节点） | 判题要跨进程 + 可重放；手动 ack 保证"判完才算处理完" |
| **缓存/锁/限流** | **Redis 7.2** | 题库候选集缓存（cache-aside）+ 会话级分布式锁 + SSE 限流，一个组件三件事 |
| **注册中心** | **Nacos 2.4.3** | 服务注册 + 动态配置（`max-rounds` 可热更） |
| **前端** | **零依赖原生 JS**（1281 行） | 只有四个功能（开局/对话/知识/判题），引框架的构建链比手写代价大 |
| **部署** | **Docker Compose**（10 容器） | 单机演示够用；K8s 未做（见已知局限） |

---

## 四、项目结构

```
mockverse/
├── STATUS.md                # ★ 项目状态快照（新会话唯一入口）
├── docs/                    # 架构 / E-R / QA 手册 / 每日 journal / 部署文档
├── interview-service/       # 8081 面试官 Agent（编排树根 + SSE + 异步判题提交）
├── knowledge-service/       # 8082 RAG 知识库 Agent（pgvector 直连）
├── resume-service/          # 8083 简历诊断 Agent（结构化输出）
├── judge-service/           # 8084 MCP 判题 Server（javac/java）+ Kafka 消费者
├── web/                     # 前端（零依赖静态页，Nginx 托管）
├── docker/                  # Dockerfile / Nginx / pg init / .env.example
├── docker-compose.yml       # 一键拉起（10 容器）
└── pom.xml                  # 父 POM（四服务 + 统一依赖版本）
```

绝对路径与历史基线：`labs/` 下有各里程碑代码归档；单体基线在 tag `v0.1-monolith`（只读参考）。

---

## 五、接口速查

| 用途 | 方法 / 路径 | 说明 |
|---|---|---|
| 开会话 | `POST /api/interview/start?userId=1&category=&difficulty=` | 返回 `sessionId`（**字符串**，雪花 ID） |
| 同步回答 | `POST /api/interview/{sid}/answer` | 一次性返回 |
| 流式回答 | `POST /api/interview/{sid}/answer/stream` | SSE：`chunk` / `tool` / `done` / `error` |
| 提交判题 | `POST /api/interview/{sid}/judge` | 立即返回 `submissionId + PENDING` |
| 判题结果流 | `GET /api/interview/{sid}/judge/stream` | **先订阅再提交** |
| 判题轮询 | `GET /api/interview/{sid}/judge/{submissionId}` | SSE 不可用时的兜底 |
| 知识检索 | `GET /api/knowledge/search?q=` | 返回 Top-K 块与分数 |
| 简历诊断 | `POST /api/resume/diagnose` | 三维评分 + 问题清单（JSON） |
| 健康 | `GET /api/health` / `/healthz/*` | 前端状态灯与容器探针 |
| MCP | `POST /mcp`（8084） | `initialize → tools/list → tools/call` |
| A2A | `POST /a2a/v1`（8081-8083） | `message/send` / `message/stream` |

---

## 六、已知局限（面试主动讲，不是不知道）

1. **无鉴权**：HTTP / A2A / MCP 端点任何人可调。对外暴露前需网关 + Basic Auth。
2. **判题不是沙盒**：候选代码仍能读写宿主文件系统与网络；防护是临时目录 + 资源上限 + 超时。
3. **单实例**：判题提交状态与 SSE 订阅在进程内存，多实例会"提交在 A、结果消费在 B"。
4. **幂等是进程内的**：进程重启后 Kafka 重投会重判一次。
5. **无缓存命中率统计**：只证明了 cache-aside 两条分支（命中 delta=0 / 冷缓存 delta=1）。
6. **未压测**：没有 QPS / 并发数据。
7. **移动端只验过视口**（390×844），未在真机点过。
8. **Kafka 是单分区**（自动创建，默认值 1），并行度为 0。

完整清单与证据见 `docs/resume-d27.md` 第六节。

---

## 七、文档索引

| 想了解 | 看 |
|---|---|
| 现在做到哪、下一步做什么 | `STATUS.md` |
| 架构与实证边界 | `docs/architecture.md` |
| 数据库设计 | `docs/db-design.md` |
| 部署细节与坑 | `docs/deploy-d24.md` |
| 内网穿透（SSE 会被缓冲） | `docs/deploy-d26-tunnel.md` |
| 面试问答（70 条） | `docs/qa-manual.md` |
| 简历话术（每句有实证） | `docs/resume-d27.md` |
| 30 问自攻 | `docs/d28-self-interview.md` |
| 每日踩坑记录 | `docs/journal/` |
| 复现脚本 | `docs/evidence/` |

---

## 八、安全须知

API Key 一律通过环境变量注入（`DEEPSEEK_API_KEY`），**任何情况下不得提交到 Git**。
`.env` / `dev.env` 已被 `.gitignore` 排除。