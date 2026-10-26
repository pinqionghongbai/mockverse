# 演示请求体（D22 走查用的原文件）

这四个是 `docs/demo-script-d21.md` 里 `--data-binary @xxx.json` 引用的文件。
放在仓库里是为了**下次开工不用重新拼**——它们原本在 `.d22tmp/`（gitignored），会随清理消失。

| 文件 | 用在 | 内容要点 |
|---|---|---|
| `answer.json` | 分镜 1（SSE 流式） | 一段 HashMap 结构说明，能引出追问 |
| `knowledge.json` | 分镜 2（跨进程知识） | "先不答题，我想先问个知识：索引什么时候会失效？" |
| `code-wrong.json` | 分镜 3（判题闭环） | 斐波那契 **少迭代一次** 的代码（`a=0,b=1` + `i<n`） |
| `answer2.json` | 分镜 5（重启后续聊） | 一句 MVCC 作答，重启后继续用 |

## ⚠️ `code-wrong.json` 的坑（D22 实测踩过）

```java
long a = 0, b = 1;                       // ← 必须是 0,1
for (int i = 2; i < n; i++) { ... }      // ← 配合 i<n 才"少一次"
```

如果写成 `a = 1, b = 1`，初值正好补上 `i<n` 少算的那一次，n=10 输出**就是 55**，
判题返回 `matched=true`，整段演示演成了"代码正确"。

**判据只能是 8084 日志的 `matched=` 字段**，不是"代码看起来像不像错的"。

## 用法

```bash
cd /e/cluadeworkbase/mockverse
SID=$(curl -s -X POST "http://localhost:8081/api/interview/start?userId=1&category=JAVA_BASIC" | python -c "import sys,json;print(json.load(sys.stdin)['sessionId'])")
curl -N -X POST "http://localhost:8081/api/interview/$SID/answer/stream" \
  -H "Content-Type: application/json" \
  --data-binary @docs/evidence/D22/fixtures/answer.json
```

> 中文必须走 `--data-binary @file`，不要用 `-d '...'`（Windows 下会变 GBK，D11 教训）。
