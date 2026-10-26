# 验证脚本归档（docs/evidence）

这里放**journal 里引用过的复现脚本**。

## 为什么需要这个目录

`.gitignore:28` 的 `.d??tmp/` 把当日验证脚手架整体排除在仓库外——那些目录里混着大量
一次性输出（`*.txt` / `*.json` 响应体、日志片段），入库会让仓库变脏，所以当时的选择是
"结论写进 journal，脚手架不入库"。

问题出在 **journal 的可复算性**上：D20 日志第六查写着"外部人重跑脚本即可复算"，
但脚本本身是 gitignored 的——这句话当时是空的。这个目录就是补这个洞。

## 归档范围（有意为之的边界）

| 归档 | 不归档 |
|---|---|
| 复现脚本（`*.py`，只依赖标准库） | 原始日志（`*.log`，`.gitignore:20` 挡着，单个 100~250KB） |
| 小而必要的输入夹具（`cases.json` / `smoke_cases.json`） | 一次性输出（响应体、`out_*.txt`、`timing_*.txt`） |
| | 带具体 sessionId / submissionId 的运行产物（换个环境就跑不通） |

判断标准：**换个环境、照着 README 能跑出同类结论** → 归档；**只能证明"那天确实跑过"**
→ 留在 `.d??tmp/`，journal 里记结论。

## 脚本清单

### D18 —— MCP 判题

| 文件 | 用途 | 前置 |
|---|---|---|
| `judge_probe.py` | 裸 JSON-RPC 直打 8084 的 MCP 端点，逐例 `tools/call` | 8084 |
| `cases.json` | 上面脚本的 10 个用例（正常 / 编译错 / 超时 / 输出不符 / 中文标识符…） | — |
| `e2e.py` | 起会话 → 发一条带代码的回答 → 打印面试官回复 | 8081 + 8084 |
| `five.py` | HTTP 五轮面试回归（第 1 轮带代码触发 `judge_code`） | 8081 + 8084 |
| `conc.py` | 两个会话同时 start+answer，检验首轮 MCP 会话只建一条 | 8081 + 8084 |

```bash
# 在 docs/evidence/D18 目录下
PYTHONUTF8=1 python -I judge_probe.py cases.json out.txt
PYTHONUTF8=1 python -I five.py five_out.txt
```

### D19 —— 判题闭环 + 等待提示 + A2A 挂起

| 文件 | 用途 | 前置 |
|---|---|---|
| `sse_timing.py` | 记录每个 SSE 事件到达时刻，量 `tool` 事件比首字早多少 | 8081 + 8084 |
| `a2a_hang.py` | **A2A 挂起复现**：同端点发 iv（进程内）与 kb（跨进程）两条，对比 state | 8081 + 8082 |

```bash
PYTHONUTF8=1 python -I sse_timing.py <sessionId> answer.json timing.txt
PYTHONUTF8=1 python -I a2a_hang.py
```

`a2a_hang.py` 的预期输出（未修状态）：`[iv] 40s 后 tasks/get → working`、
`[kb] 40s 后 tasks/get → completed`。根因与修法见
`docs/journal/D20-2026-11-10.md` 第二节。

### D21 —— A2A 修复 + 故障注入

| 文件 | 用途 | 前置 |
|---|---|---|
| `a2a_resume_once.py` | 单发一条简历诊断请求，验跨进程路由到 8083 | 8081 + 8083 |
| `blackhole.py` | **假死服务**：接受 TCP 连接但一个字节都不回，用于验证 `loadTimeout` 真的生效 | 无 |

```bash
# 终端 A：起假服务
PYTHONUTF8=1 python -I blackhole.py 18084
# 终端 B：把 8081 的判题地址指过去再起
export JUDGE_SERVICE_URL="http://127.0.0.1:18084/mcp"
# 预期：start 约 13s（10s 加载超时 + 模型），日志有"工具加载超过 10s"；
#       紧接着发 A2A 请求，任务仍应 completed（不会回到 D19 的永久 working）
```

### D20 —— 异步判题取证

| 文件 | 用途 | 前置 |
|---|---|---|
| `d20_async_judge.py` | 端到端：先开 SSE → 提交 → 收结果 → 轮询比对 | 8081 + 8084 + Kafka |
| `a2a_iv_once.py` | 单发一条 iv 请求，只观察不轮询（取证用） | 8081 |
| `a2a_kb_once.py` | 单发一条 kb 请求（跨进程对照组） | 8081 + 8082 |
| `smoke_cases.json` | 同步判题回归的两个用例 | — |

```bash
PYTHONUTF8=1 python -I d20_async_judge.py
```

## 前置条件

```bash
docker start mockverse-mysql mockverse-pg mockverse-kafka
# 8082 / 8083 先起（8081 启动时要拉它们的 AgentCard），然后 8084、8081
```

端口：8081 interview / 8082 knowledge / 8083 resume / 8084 judge / 9092 Kafka / 3307 MySQL / 5433 pgvector。

## 约定

- 所有脚本**只依赖标准库**，不引入第三方包——换个环境不用装依赖就能跑。
- Windows 下中文输出会炸 GBK 控制台，所以脚本一律**把结果写文件**，并约定用
  `PYTHONUTF8=1 python -I` 运行（`-I` 隔离用户 site-packages，避免环境干扰）。
- 脚本里的 sessionId / submissionId 都是**运行时生成或写死的示例值**，换环境需要改；
  这类参数刻意不抽成配置，因为它们是"一次运行"的标识，不是配置项。
- **改动请改这个目录里的版本**，`.d??tmp/` 下同名文件只是当日草稿（会被清理）。
  归档版与草稿版一旦分叉，以这里为准。
