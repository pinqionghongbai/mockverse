# MockVerse 项目工作纪律

> 本文件对仓库内所有会话自动生效。项目背景/进度/明日指引见 `STATUS.md`（新工作日
> 唯一入口）；最高基准是 `docs/charter.md`（章程 + 诚信红线）。

## 五轮审查协议（自动触发，无需用户提醒）

每个 Day 的任务完成（编码 + 验证 + 归档提交推送）后，**自动执行五轮收工审查**，
协议全文见 `docs/review-protocol.md`，要点：

- 五轮各有视角：①证据一致性（文档数字 vs 实测，抓贴金/漏计）→ ②事实断言核验 +
  修复代码重读（抓挪用，修 bug 必 grep 同族病灶）→ ③错误路径语义验算/真故障注入
  （定律：bug 全在错误路径）→ ④回归缺口与未覆盖角落 → ⑤收口轮。
- 收口条件：**连续两轮无新发现**。
- 发现即修：当轮修复 + 验证 + 独立提交（`D{X} {N}查: ...`）；不修的写理由挂
  STATUS 挂起表。
- 每轮结果写入当日 `docs/journal/DXX-*.md` 补记节。

## 不可违反的既有纪律（每条都踩过坑，全文见 STATUS.md 第九节）

- **API Key/token 严禁入 Git**：推送前扫暂存区（应 0 命中）与历史（当前 6 命中，
  已逐条定位全部为扫描说明文字的自指误报，无真实 token；数字变化时更新本行）；
  `.env`/`dev.env` 不入库；Key 只从环境变量读。
- **push 用** `GITHUB_TOKEN="$GITHUB_TOKEN" git push`（credential helper 是引用式）。
- **MYSQL_PORT 必须 3307**（默认 3306 会连到本机无关的 MySQL）。
- **git 提交只在用户要求的流程内执行**（"开工/检查/继续推进"即含当日归档流程）。
- 验证工具链坑：curl 中文用 `--data-binary @file.json`（UTF-8 文件体）；
  Windows Python 解 JSON 用 `PYTHONUTF8=1 python -I` + 文件 + `open(encoding='utf-8')`。
- 后台任务后 bash cwd 会重置，跨命令显式 cd。
