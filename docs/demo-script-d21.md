# 演示走查脚本（微服务版，D21）

> 用途：四服务 + Kafka 的完整能力演示。上一版 `demo-script-d14.md` 属于 tag `v0.1-monolith`
> （单体已退役），保留作历史对照。
> 分镜里的每条命令都在 D21 联调、**D22 完整走查**时实跑过，耗时取自当天日志。
> **D22 走查结论：五段全部通过**（2026-11-11，耗时见每段末尾的括号）。

> **⚠️ D23（2026-11-13）状态变更**：演示视频已按用户决定**从交付清单移除，不录制**。
> 本文档因此不再以"录屏脚本"的身份存在，但**保留为可执行的演示走查手册**——
> 分镜、命令、实测耗时、翻车点预案都是实证过的，随时可用于现场演示或回归。
> 文中的"录屏前"一律按"演示前"理解。

---

## 演示前准备

```bash
# 0. 新 shell 里 mvn/java 可能不在 PATH（D16 实测）
export PATH="/c/Program Files/Java/jdk-21/bin:/c/Program Files/JetBrains/IntelliJ IDEA 2024.2.2/plugins/maven/lib/maven3/bin:$PATH"

# 1. 起三个容器（Docker 引擎默认不开）
docker start mockverse-mysql mockverse-pg mockverse-kafka
docker ps --format "{{.Names}}\t{{.Status}}"      # 三个都 Up 才继续

# 2. 起服务：8082/8083 必须先于 8081（8081 启动时要拉它们的 AgentCard）
cd E:/cluadeworkbase/mockverse
# 堆上限不是随手写的：默认堆在 16G 机器上会让四个服务 + Docker 把内存吃满，
# CC 会因系统内存告急回收后台进程（D22 实测被回收过一次）
for s in knowledge-service resume-service judge-service interview-service; do
  case $s in
    judge-service)     HEAP=192m ;;
    interview-service) HEAP=320m ;;
    *)                 HEAP=256m ;;
  esac
  ( cd $s && set -a && . ../dev.env && set +a && nohup java -Xmx$HEAP -jar target/$s-0.1.0-SNAPSHOT.jar > ../.demo-$s.log 2>&1 & )
  sleep 12
done

# 3. 四服务健康检查（8084 是 /health，其余是 /api/health）
for p in 8081 8082 8083; do curl -s -o /dev/null -w "$p=%{http_code} " http://localhost:$p/api/health; done
curl -s http://localhost:8084/health; echo
```

**画面预置**：一个终端跑上面的健康检查，另一个终端准备 `curl -N`。

---

## 分镜（约 4 分钟）

### 0:00–0:25 开场

> "MockVerse，面向求职者的多智能体 AI 模拟面试平台。四个 Spring Boot 服务：
> 面试官 8081、知识库 8082、简历诊断 8083、判题 8084，之间用 A2A 协议通信，
> 判题走 MCP 工具，异步判题走 Kafka。下面从一场面试开始。"

**镜头**：`docker ps` + 四服务 health 全绿。

### 0:25–1:05 面试开场 + SSE 逐字输出

```bash
curl -s -X POST "http://localhost:8081/api/interview/start?userId=1&category=JAVA_BASIC"
# 记下 sessionId，然后：
curl -N -X POST "http://localhost:8081/api/interview/<sessionId>/answer/stream" \
  -H "Content-Type: application/json" --data-binary @docs/evidence/D22/fixtures/answer.json
```

**画面重点**：终端逐字蹦出（每秒十几个 chunk）。

> D22 实测：start 3.08s；SSE 整轮 1.81s / **63 个 chunk** / 首字 **1.21s**。
**口播**："回答是流式的，每个 chunk 带 author 归因——能看出这句话是哪个 Agent 说的。"

### 1:05–1:40 会话内问知识（跨进程 A2A 路由 + Agentic RAG）

```bash
# knowledge.json = {"answer":"先不答题，我想先问个知识：索引什么时候会失效？"}
curl -s -X POST "http://localhost:8081/api/interview/<sessionId>/answer" \
  -H "Content-Type: application/json" --data-binary @docs/evidence/D22/fixtures/knowledge.json
```

**口播**："coordinator 判断这是知识问题，通过 **A2A 协议**转给另一个进程的知识库 Agent
（8082）——它自己决定检索词，从 pgvector 取资料，答案带 [1][2] 引用。**这是跨进程调用，
不是进程内函数调用。**"

> D22 实测：5.02s / 391 字 / 带 `[1]` 引用。

**佐证镜头**（切到 8082 的日志）：
```bash
grep "远端\|knowledge_agent" .demo-knowledge-service.log | tail -3
```

### 1:40–2:30 判题闭环 + 等待提示（核心卖点）

```bash
# 让面试官抽一道算法题（题库里有带输入/输出样例的 ALGORITHM 题）
curl -s -X POST "http://localhost:8081/api/interview/start?userId=1&category=ALGORITHM"
# 提交一段"少迭代一次"的斐波那契代码
curl -N -X POST "http://localhost:8081/api/interview/<sessionId>/answer/stream" \
  -H "Content-Type: application/json" --data-binary @docs/evidence/D22/fixtures/code-wrong.json
```

**画面重点**：流里先出现 `{"author":"interviewer_agent","tool":"judge_code"}`，界面显示
"正在判题…"，1.5~1.9 秒后第一段文字才开始出现。

> D22 实测：TOOL **1747ms** → 首字 **3529ms**（间隔 **1782ms**）→ DONE 4180ms；
> 判题本身 `matched=false, 719ms`。

**口播**："判题是**真的编译运行**——8084 用 javac 编译、java 运行，返回的是客观事实：
能不能编译、实际输出是什么。判题要 0.6~10 秒，这段时间流里会先推一个 tool 事件，
所以界面不会假死。注意它说的话：'实际输出 34，与样例 55 不符，循环少迭代了一次'——
**指出了错在哪，但没有把答案写出来**。"

> **三个请求体都已归档**在 `docs/evidence/D22/fixtures/`（`answer.json` / `knowledge.json` /
> `code-wrong.json`），直接 `--data-binary @docs/evidence/D22/fixtures/xxx.json`，不用自己拼。
> `code-wrong.json` 必须是"**真的错**"的那一版（`a=0,b=1` + `i<n`）——写成 `a=1,b=1`
> 就变成正确代码了（n=10 输出 55，`matched=true`），详见该目录 README 的 ⚠️ 说明。

**佐证镜头**（切到 8084 日志）：
```bash
grep "judge_code:" .demo-judge-service.log | tail -2
# judge_code: className=Fib, compiled=true, ran=true, timedOut=false, exit=0, matched=false, 636ms
```

### 2:30–3:10 异步判题（Kafka）

```bash
# 提交立即返回，不等判题
curl -s -X POST "http://localhost:8081/api/interview/1/judge" \
  -H "Content-Type: application/json" -d '{"code":"public class H{public static void main(String[] a){System.out.println(42);}}","expectedOutput":"42"}'
# → {"status":"PENDING", "submissionId":"...", ...}   实测 102~161ms

# 结果从 SSE 推回来
curl -N "http://localhost:8081/api/interview/1/judge/stream"
# → data: {"submissionId":"...","status":"COMPLETED","result":{"compiled":true,"outputMatched":true,...}}
```

**口播**："这条路径是异步的：提交一百多毫秒返回'判题中'，任务进 Kafka，
判完结果再推回来。同一条消息重复投递也只判一次——用提交 ID 做幂等。"

> D22 实测：提交 **160.8ms** → SSE `COMPLETED matched=True` → 轮询一致。

**佐证镜头**：
```bash
grep "重复消息，跳过判题" .demo-judge-service.log | tail -2
```

### 3:10–3:45 杀进程重启，接着聊（会话恢复）

1. `Ctrl+C` 杀掉 8081——**故意让观众看见进程死**
2. 重启：`cd interview-service && java -jar target/interview-service-0.1.0-SNAPSHOT.jar`（约 30s，口播过渡）
3. 直接继续作答：

```bash
curl -s -X POST "http://localhost:8081/api/interview/<sessionId>/answer" \
  -H "Content-Type: application/json" --data-binary @docs/evidence/D22/fixtures/answer2.json
```

**口播**："进程内存里的会话没了，但面试还能接着聊——历史在 MySQL，每轮回放重建上下文。"

> D22 实测：重启后 `round=3`（重启前 2 轮），回复里主动引用"你上一轮把 String 不可变
> 答成了 HashMap"——**记忆确实接上了**，不是只回了个空壳。

### 3:45–4:00 收尾

> "四个服务、A2A 跨进程编排、MCP 判题、Kafka 异步、SSE 流式。
> 代码在 github.com/pinqionghongbai/mockverse。"

---

## 已知可控风险（演示前知道，翻车不慌）

| 风险 | 概率 | 预案 |
|---|---|---|
| coordinator 路由摇摆（问知识被判给面试官） | 有（D12 起就存在） | **新开会话 + 把意图说清楚**（"这是一个知识问题，请查知识库：…"）；D17/D19 都实测过 |
| 抽到八股题而不是算法题 | 有 | `start` 带 `category=ALGORITHM` 指定类别 |
| SSE 首字偶发慢（DeepSeek 波动） | 低 | 重试即可；实测均值 1.7~3.4s |
| 简历诊断分数抖动 | 固有 | **别念具体分数**，只说"不高" |
| Kafka 没起 | 低 | 提交会 2.3s 返回 `FAILED`（不是挂住），重启容器即可 |
| **"错误代码"其实没错** | 有（D22 踩过） | 判据看 8084 日志的 `matched=` 字段，**不要凭代码像不像**；fixture 内容见 1:40 那段 |
| **系统内存不足，服务被回收** | 有（D22 踩过） | 用 `-Xmx192m`（判题）/ `-Xmx320m`（面试）起，别用默认堆；演示前先 `docker ps` + 四服务 health 确认 |

## 诚实边界（演示时不要说的话）

- 不说"生产可用"——InMemory 记忆、无鉴权、判题不是容器级沙盒（D25 计划内）
- 不把单次延迟当性能承诺（数字是单次观测，不是压测结论）
- 不说"跨进程多轮对话"——A2A 的 contextId 复用有 ADK 上游 bug，远端调用是无状态的
- 不说"A2A 支持断线续订"——`tasks/resubscribe` 未实现
