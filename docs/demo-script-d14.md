# D14 演示视频脚本（3 分钟单体版）—— 已归档

> ⚠️ **本脚本对应 tag `v0.1-monolith`（单体已 D21 退役）**，当前版本的演示脚本见
> `docs/demo-script-d21.md`。保留本文件是为了"视频里那套命令还能照着复现"。

# D14 演示视频脚本（3 分钟单体版）

> 用途：tag `v0.1-monolith` 的配套演示视频，录屏工具任选（OBS / Win+G）。
> 录前准备：两个库起着、应用起着（命令见文末）；终端字体调大（观众要看清逐字输出）。
> 分镜里每条命令都经过 D14 联调实测（2026-11-04，见 D14 日志第二节）。

---

## 分镜

### 0:00–0:20 开场（对着镜头或字幕卡）

> "MockVerse，面向求职者的多智能体 AI 模拟面试平台。一个会话里有三个 Agent：
> 面试官出题追问、知识库答疑、简历诊断——coordinator 自动路由。
> 下面 3 分钟，从面试开始。"

终端预置：`docker ps` 显示两个容器在跑（MySQL + pgvector），`curl health` 返回 200。

### 0:20–1:00 面试开场 + SSE 逐字追问（核心卖点）

```bash
curl -s -X POST "http://localhost:8081/api/interview/start?userId=1&category=JAVA_BASIC"
# 拿到 sessionId 后：
curl -N -X POST "http://localhost:8081/api/interview/<sessionId>/answer/stream" \
  -H "Content-Type: application/json" --data-binary @answer.json
```

**画面重点**：终端逐字蹦出回答（每秒十几个 chunk），观众肉眼可见"流式"。
**口播**："回答是流式的，首字 2 秒内，逐字输出；每个 chunk 都带 author 归因。"

### 1:00–1:30 会话内问知识（多 Agent 路由 + Agentic RAG）

面试进行中，不答题、插一个问题：

```bash
curl -s -X POST "http://localhost:8081/api/interview/<sessionId>/answer" \
  -H "Content-Type: application/json" --data-binary @knowledge.json
# knowledge.json = {"answer":"先不答题，我想先问个知识：索引什么时候会失效？"}
```

**口播**："coordinator 识别出这是知识问题，转给知识库 Agent——它自己决定检索词
（把'索引什么时候会失效'改写成'索引什么时候失效'），从 pgvector 知识库取回资料，
回答带 [1][2] 编号引用。"

**佐证**（可选镜头，切到 history 接口）：
```bash
curl -s "http://localhost:8081/api/interview/<sessionId>/history"
```
归因里能看到 `knowledge_agent`——不同的话是不同的 Agent 说的。

### 1:30–2:10 简历诊断（结构化输出）

```bash
curl -s -X POST "http://localhost:8081/api/resume/diagnose" \
  -H "Content-Type: application/json" --data-binary @resume.json
```

**口播**："贴一段简历，Agent 强制 JSON 输出——总分、三个维度分、问题清单逐条带
原文引用，落库 MySQL。**分数按简历实际质量给，平庸简历落在及格线以下**，不是恭维型的玩具。"

> ⚠️ **别念具体分数**（D15 再检查实测）：同一份"平庸简历"D11 给 52、D14 给 42、
> D15 复测给 52——抖动 + 简历文本差异叠加，念死数字会和屏幕对不上。指着屏幕说
> "看，分数不高，问题逐条带原文引用"即可。

### 2:10–2:50 杀进程重启，接着聊（会话恢复，压轴）

1. 面试进行中，`Ctrl+C` 杀掉应用（或新终端 taskkill）——**故意让观众看见进程死**
2. 重启：`mvn -B spring-boot:run`（等 health 200，约 18 秒，口播过渡）
3. 直接继续作答：

```bash
curl -s -X POST "http://localhost:8081/api/interview/<sessionId>/answer" \
  -H "Content-Type: application/json" --data-binary @answer2.json
```

**口播**："进程内存里的会话已经没了，但面试还能接着聊——历史存在 MySQL，
每轮回放重建上下文。追问锚定的还是重启前的回答。"

### 2:50–3:00 收尾字幕卡

> "单体版到此。多 Agent 以 A2A 协议拆成微服务、MCP 判题工具、真实前端——
> 详见 github.com/pinqionghongbai/mockverse（v0.1-monolith）"

---

## 录前准备清单

```bash
# 0. ⚠️ 先确认三个微服务没在跑（D15 拆分后它们与单体同占 8081/8082/8083）
netstat -ano | grep -E ":(8081|8082|8083) " | grep LISTENING
#    有输出就先停掉，否则单体起不来（报 Port 8081 already in use）
#    注意：单体与 interview-service 都用 8081，二者互斥

# 1. 起两个库
docker start mockverse-mysql mockverse-pg

# 2. 起应用
cd E:/cluadeworkbase/mockverse/mockverse-monolith
export MYSQL_PORT=3307        # 必须
set -a; . ./dev.env; set +a
mvn -B spring-boot:run        # 等 http://localhost:8081/api/health 返回 200（实测冷启 14s / 重启 12s）

# 3. 预备 UTF-8 请求体文件（避免 curl 中文 GBK 坑，D11 教训）
#    answer.json / knowledge.json / resume.json / answer2.json 按分镜准备好

# 4. 分辨率 1080p+，终端字体 ≥16pt；录完检查逐字段是否清晰
```

## 已知可控风险（录前知道，翻车不慌）

| 风险 | 概率 | 预案 |
|---|---|---|
| 开场轮 coordinator 越权自己念题（不转移） | ~20%（3/10） | 无实害——输出幂等正确，只是归因是 root_coordinator；介意就重录一次 |
| SSE 首字偶发慢（DeepSeek 波动） | 低 | 重试即可；实测均值 ~2s |
| 简历诊断分数抖动（D11 52 / D14 42 / D15 52） | 固有 | 口播**不念具体分数**（见分镜 1:30 的警示框），只说"不高" |

## 诚实边界（不要在视频里说的话）

- 不说"微服务"——现在是单体（编排是进程内 transfer，不是跨进程 A2A）
- 不说"生产可用"——InMemory 记忆、无鉴权（D25 计划内）
- 不念具体延迟数字当性能承诺——单次观测（chat 4.8s / 首字 2s），不是压测结论
