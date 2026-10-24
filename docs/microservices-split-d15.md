# MockVerse 微服务拆分方案（D15 决策入库）

> 对应手册 D15 上午：拆分决策——三服务的 artifactId/端口/职责边界，父 POM 统一依赖版本；
> 方案图入库。下午的机械拆分（复制代码、三服务分别启动）依据本文件执行。
> 状态标记：✅ 已执行（D15）/ ⏳ 待执行（D16-D21）。

---

## 一、目标架构（D21 终态）

```
                          ┌─────────────────────────┐
                          │   interview-service     │ 8081
   HTTP(SSE) ──────────▶   │  coordinator(进程内)    │
                          │   └ interviewer_agent   │
                          │        │ A2A (JSON-RPC) │ ──────┐
                          └─────────────────────────┘        │
                                    │ A2A                     │ A2A
                    ┌───────────────┴──────────┐  ┌──────────▼──────────┐
                    │    knowledge-service     │  │    resume-service   │
                    │       8082 (D16 起挂      │  │      8083 (D16 起挂  │
                    │  AgentCard, A2A 化)      │  │  AgentCard, A2A 化) │
                    │  knowledge_agent         │  │  resume_agent       │
                    │  + search_knowledge 工具 │  │  + diagnose_resume  │
                    └───────────┬──────────────┘  └──────────┬──────────┘
                                │                            │
                        pgvector(5433)                MySQL(3307)
                    50 篇语料 512 维向量              resume_diagnosis 表

  D18-D19 新增: judge-mcp-server (MCP, judge_code 工具)
                ← interviewer_agent 经 MCP 调用 (模仿 mcpfilesystem 接法)
```

D15 当天只做**机械拆分**：三服务各自能启动、各自领域的端点本地可用；
**跨服务调用还没有**（知识/简历意图在 interview-service 内暂答"拆分中"，
D17 用 RemoteA2AAgent 接回——这是"哪怕功能还没解耦"的诚实含义）。

## 二、三服务决策表

| 服务 | artifactId | 端口 | 职责边界 | 本服务内 Agent/工具 | 数据 | 进程依赖 |
|---|---|---|---|---|---|---|
| 面试官服务 | `interview-service` | 8081 | 面试主流程：start / answer(同步+SSE) / history / health | root_coordinator + interviewer_agent + pick_question | MySQL：interview_session、chat_message、question | MySQL、DeepSeek |
| 知识库服务 | `knowledge-service` | 8082 | RAG 问答：search / ask / reindex / health | knowledge_agent(D17 起)+search_knowledge 工具 | pgvector：knowledge_chunks 语料 50 篇 | pgvector、DeepSeek(0.2)、本地 ONNX embedding |
| 简历诊断服务 | `resume-service` | 8083 | 简历诊断：diagnose / history / health | resume_agent(D17 起)+diagnose_resume 工具 | MySQL：resume_diagnosis | MySQL、DeepSeek(0.3+json) |

**端口依据**：8081 自 D8 起注释即"目标架构面试官服务端口"，不变；8082/8083 顺延。
**包名**：`com.mockverse.interview` / `com.mockverse.knowledge` / `com.mockverse.resume`
（monolith 的 `com.mockverse.monolith` 保留在单体里不动，tag v0.1-monolith 是参考基线）。

## 三、父 POM 决策

- 仓库根新增 `mockverse-parent`（packaging=pom）：`<parent>` 直挂 spring-boot-starter-parent
  3.3.1（沿用单体 pom 里 D6 的教训注释：BOM properties 只有 parent 关系才继承）。
- `dependencyManagement` 不另设——版本统一走 **properties 集中声明**，与单体逐字一致
  （版本锁定表纪律：**禁止中途变更依赖版本**，父 POM 只做集中，不做升级）：
  `mybatis-plus 3.5.7 / google-adk 1.13.0 / langchain4j 1.12.2 / embeddings 1.12.2-beta22 / Java 21`。
- `<modules>`：三个新服务。**mockverse-monolith 不纳入 modules**——它是 v0.1-monolith 的
  参考基线（tag 锁定），保持独立可构建；若纳入父 POM 反而制造"它也要跟着 modules 演进"
  的耦合。单体退役时机 = D21 联调通过后。

## 四、机械拆分原则（D15 下午执行口径）

1. **复制不抽象**：DeepSeekModelConfig / HealthController / ApiExceptionHandler 等
   共用代码直接复制到各服务再裁剪（各服务只留自己需要的 bean——面试 0.7 双模型、
   知识 0.2、简历 0.3+json）。不建 common 模块（YAGNI：D21 前共享形态未稳定，
   过早抽象会把"机械拆分"变成"重构马拉松"）。
2. **编译器驱动裁剪**：cp 整树 → 删不归属文件 → `mvn compile` 收敛。
3. **coordinator 的裁剪**：interview-service 保留 root_coordinator + interviewer_agent
   两个节点（保住 D12 的归因/兜底代码路径），knowledge_agent/resume_agent 从树中
   摘除；coordinator 提示词同步裁剪——知识/简历意图回复"该能力已拆分至独立服务，
   A2A 接入中"，不假装还在。D17 用 RemoteA2AAgent 把两个子 Agent 远程接回。
4. **共库不共表**：三服务共用同一 MySQL 库（mockverse），各自只碰自己的表；
   knowledge-service 完全不依赖 MySQL（pgvector 手动连接，不参与 Spring DataSource
   自动配置——其 health 检查 pgvector 而非 Spring DataSource）。
5. **冒烟 runner 不迁移**：smoke/ 包留在单体（它依赖多 Agent 全树与故障注入，
   拆分期没有完整宿主）；三服务的回归方式 = HTTP 实测各自核心端点（D15 下午验收）。

## 五、迁移路线（本方案在 D16-D21 的演化）

| Day | 动作 | 本方案对应点 |
|---|---|---|
| D16 | 三服务接入 adk-java a2a/webservice，AgentCard 可 curl | 表二的三张卡；knowledge/resume 的 agent 化 |
| D17 | interview 用 RemoteA2AAgent 调远端知识库/简历 Agent | 第三节 coordinator 摘除点的"接回" |
| D18-D19 | MCP 判题 Server + judge_code 工具接入 | 架构图新增节点 |
| D20 | Kafka 提交队列 | interview-service 侧新增 |
| D21 | 全链路联调 + v0.2 | 单体退役判定点 |

## 六、已知风险（拆分当天盯住）

- **DeepSeekModelConfig fail-fast**：三服务启动都要 `DEEPSEEK_API_KEY` 环境变量
  （缺了直接启动失败，这是 D3 起的设计，不是 bug）。
- **知识意图的体验回退**：D15-D16 期间在面试会话里问知识，会得到"拆分中"话术——
  这是诚实标注的过渡态，D17 结束。验收时不算缺陷，但要写进 journal 不装看不见。
- **三 JVM 同机资源**：三个 Spring Boot 进程 + ONNX embedding 常驻（knowledge）；
  本机 16G+ 内存无压力，但启动顺序按 interview → knowledge → resume 逐个验，
  不并行（D15 验收求"分别启动"，不追求并发压测）。

## 七、D15 执行结果（2026-11-05 下午，验收实证）

| 项 | 结果 |
|---|---|
| 父 POM 聚合编译 | ✅ `mvn -B compile` 4/4 BUILD SUCCESS（8.3s） |
| interview-service 8081 | ✅ health 200（MySQL 8.0.46 已连接）；start 出题 JVM/HARD；SSE 逐字 chunk 带 `author` |
| knowledge-service 8082 | ✅ health 200（pgvector available，chunks=50）；search 命中「HashMap 的扩容机制」 |
| resume-service 8083 | ✅ health 200（MySQL 已连接）；diagnose 总分 62 + 三维度 JSON |

**与方案的偏差（如实记录）**：

1. **knowledge-service 需要 `spring-boot-starter-jdbc`**：`PgVectorStore` 用 `JdbcTemplate` +
   `HikariDataSource`，这两个类来自 spring-jdbc。方案说"不参与 Spring DataSource 自动配置"
   ——实现方式是在 `KnowledgeApplication` 上 `exclude = DataSourceAutoConfiguration.class`，
   而不是不引入依赖。pom 里确实没有 mysql/mybatis，这一点与方案一致。
2. **知识意图过渡态话术**：实测两条知识类消息都归因 `interviewer_agent`，由面试官答
   "不在本服务范围"——诚实性成立，但不是方案预期的 coordinator 直接话术。D17 接回后消失。
   详见 `docs/journal/D15-2026-11-05.md` 五节。
3. **三服务未并行启动过**：验收按方案逐个起、逐个停，不做并发压测（D15 范围外）。

**收工审查补充（三查，错误路径）**：knowledge-service 的 health 最初在运行期
pgvector 宕机时返回 500（`available` 只在启动时置位），与注释契约"200 + DEGRADED"
矛盾；检索/reindex 也是 500 而非 503。已修（`DataAccessException` → 503 语义，
health 报 DEGRADED，不毒化 `available` 以保住 Hikari 自愈）。修复后实测：
pg 停 → health 200 DEGRADED / search·reindex 503；pg 恢复 → 不重启应用即自愈。
详见 `docs/journal/D15-2026-11-05.md` 十一节。


**收工审查补充（八查，MySQL 故障路径）**：interview-service / resume-service 原先用
Spring Boot 默认的 Hikari `connectionTimeout=30s`，MySQL 宕机时 `/api/health` 与
`/api/interview/start` 各阻塞 **30.1 秒**才返回（与 knowledge-service pgvector 池的
3 秒快速失败差 10 倍）。已在两个服务的 `application.yml` 加
`spring.datasource.hikari.connection-timeout: 3000`；修复后实测 **3.0~3.4 秒**，
MySQL 恢复后**不重启应用** 2 秒内自愈，start / history 均 200。
详见 `docs/journal/D15-2026-11-05.md` 十六节。

**收口后整改（2026-11-05，主人指示）**：

| 项 | 结果 |
|---|---|
| DB 故障语义 | interview/resume 新增 `@ExceptionHandler(DataAccessException.class)`，按根因分流：连接类 → **503**、`DataIntegrityViolationException` → **409**、其余 → 500 ProblemDetail。实测 MySQL 宕机：start/history 503（3.0~3.1s），恢复 2s 自愈，业务 400 未误伤 |
| health 语义 | 三服务统一为 `status: UP / DEGRADED`（HTTP 仍 200），细节在 `database` / `vectorStore` 字段 |
| **Bug H** | MySQL 未就绪时 interview-service 因 `QuestionSeeder`（`ApplicationRunner`）抛异常**启动即退出**；已改为失败只 WARN、降级启动。代价：恢复后不重启不会补种种子数据 |
| 死代码 | 删除拆分时整树复制带来的 `entity/User.java`、`mapper/UserMapper.java`（服务内 0 引用） |
| 服务隔离实证 | 只停 MySQL：interview/resume `DEGRADED` + 端点 503，**knowledge-service 仍 `UP` 且 search 200** |

详见 `docs/journal/D15-2026-11-05.md` 二十节。

**整改后再检查（2026-11-05，Bug I）**：`QuestionSeeder` 的 10 条种子数据是逐条 insert、无事务，
中途失败会留下"半套题库"，而下次启动的 `selectCount > 0` 判断会认为"已有数据"直接跳过 →
题库永久残缺且日志看起来完全正常（静默降级）。已用 `TransactionTemplate` 包成原子操作
（不能直接加 `@Transactional`——同类内部调用不走代理）。用临时库 `mockverse_seedtest` 验证：
空库灌入 10 条、二次启动幂等跳过，验证后已清理。另修 3 处小瑕疵（注释错位、日志打 null、口径错误），
并挂账 `reindex` 的 TRUNCATE 非原子（见 STATUS）。详见日志二十一节。
