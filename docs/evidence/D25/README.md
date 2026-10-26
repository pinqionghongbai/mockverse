# D25 前端验证归档

D25 的前端是零依赖静态页（`web/`），验证方式是**真实浏览器操作**，没有需要归档的脚本。
这里记录"怎么复现同一套结论"。

## 前置

```bash
cd E:/cluadeworkbase/mockverse
docker compose up -d          # 十个容器 healthy
curl http://localhost/healthz/interview   # UP
```

## 四条链路（浏览器打开 http://localhost/）

| # | 操作 | 期望 |
|---|---|---|
| 1 | 选方向/难度 → 点「开始面试」 | 出现题目（题库气泡）+ 面试官开场白；顶栏显示方向·难度 |
| 2 | 输入回答 → Enter | 回答**逐字出现**（不是一次性蹦出来）；结束后显示评价与追问 |
| 3 | 输入「先不答题，我想问个知识：索引什么时候会失效？」 | **两个气泡**：面试官过渡语 + 知识库助手带 `[1][2]` 引用的答案 |
| 4 | 展开「代码判题」→ 贴代码 + 期望输出 42 → 提交 | `✓ 通过` / 类名 Main / 耗时 xxx ms / 退出码 0 / 输出 42 |

## 三个必须信的判据

1. **sessionId 是字符串**：`curl -X POST "http://localhost/api/interview/start?userId=1"` 的响应里
   `"sessionId":"2114..."` 带引号。若是不带引号的数字，说明 `JacksonConfig` 没生效，
   前端会静默丢精度（19 位 > 2^53-1）。
2. **逐字到达**：用 `docs/evidence/D24/sse_timing.py`，看 spread 与"最后 0.1s 到达比例"。
   浏览器里肉眼看也行，但判据是时刻分布。
3. **判题 stderr 为空**：`/judge/stream` 的结果里 `"stderr":""`。若出现
   `Picked up JAVA_TOOL_OPTIONS`，说明判题服务还在用 `JAVA_TOOL_OPTIONS`（D25 已修）。

## 已知不做的

- 无历史会话列表（`/history` 未接）
- 无断线重连
- 未做移动端真机验证（只写了 640px 以下响应式）
