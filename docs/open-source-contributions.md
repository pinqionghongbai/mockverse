# 开源贡献记录（简历素材）

> 本文件收录可写入简历、且有公开链接佐证的开源贡献。每条都必须"点开链接即真"。

## 1. google/adk-java — Issue #1686（2026-10-22）→ PR #1691（2026-10-23）

**Issue 链接**: https://github.com/google/adk-java/issues/1686（open，署名 pinqionghongbai）
**PR 链接**: https://github.com/google/adk-java/pull/1691（open，1 commit / 4 files / +473 −1）
**进展**:
- 2026-10-22 10:21 追加首条技术评论（issue 正文 + follow-up 评论两部分构成完整证据链）
- 2026-10-23 **维护者 hemasekhar-p 回复：已复现，并邀请提 PR**
  （原文："I have been able to reproduce the behavior with your inputs... Would you be able to
  provide a pull request to address this issue in a robust way?"）
- 2026-10-23 **修复代码完成并推送到 fork 分支**
- 2026-10-23 **PR #1691 已提交**，`cla/google` 检查通过
- 2026-10-23 10:55 **已在 issue 回复维护者**（附 PR 链接 + 实测数据；发出前做了精简，见 1.3）

**评论补强内容**（构成完整贡献的三块）:
1. 精确化 retry 语义：MAX_RETRIES=3 实为 3 次总尝试（1 初始 + 2 重试），900,857ms = 3×300s 严丝合缝
2. 复现完整性：附件说明 + Windows-only 边界声明（Linux/macOS 不受影响）
3. **跨生态对齐分析**（命中该仓库 CONTRIBUTING 的"与 adk-python 对齐"原则）:
   Python MCP SDK 在 SDK 层有 `get_windows_executable_command`（先 `shutil.which(command)`，
   再依次试 `.cmd`/`.bat`/`.exe`/`.ps1` 兜底），Java MCP SDK 的 StdioClientTransport 无对应物
   → adk-java 继承了缺陷 →
   建议修复可落在本仓库 DefaultMcpTransportBuilder 或上游 Java MCP SDK，实现与 Python 生态对齐

---

### 1.1 修复实现（2026-10-23）

| 项 | 内容 |
|---|---|
| 分支 | `fix/windows-npm-shim-resolution`（fork: `pinqionghongbai/adk-java`） |
| 提交 | `52b539e` 单提交（CONTRIBUTING 硬性要求） |
| 改动 | 4 文件 / +473 −1：新增 `WindowsExecutableResolver` + 接入 `DefaultMcpTransportBuilder` + 18 个测试 |
| 格式化 | `mvn fmt:check` 全仓库 0 non-complying；CI 的 `git diff --exit-code` 亦通过 |
| 测试 | core 全量 **2609 用例 / 0 失败 / 0 错误 / 30 skip**（30 个是既有的 assumption skip），`mvn -Prelease clean package` 即 CI 同款命令；本次新增 18 个用例（14 + 4） |
| CI 预演 | ✅ `mvn -Prelease clean package -pl core -am` → BUILD SUCCESS，javadoc jar 生成成功（`doclint=all,-missing`） |

**修复思路**：在 `DefaultMcpTransportBuilder.build()` 里，把 `ServerParameters` 交给
`StdioClientTransport` 之前先解析命令——镜像 Python MCP SDK 的 `shutil.which` 语义：
- 已带 PATHEXT 扩展名（`.cmd`/`.exe`…）→ 按原样查找
- 不带扩展名 → 依次尝试 `command + 每个 PATHEXT 扩展`，按 PATHEXT 顺序
- 命中后返回**磁盘上的真实文件名**（不是 PATHEXT 拼出来的大写形式）
- 非 Windows / 显式路径 / 找不到 → 原样返回，交给 `ProcessBuilder` 自己报错

**两个实现细节（值得讲）**：
1. **大小写匹配必须显式做**。第一版依赖 `File.isFile()`，在 Windows 上能过（文件系统大小写不敏感），
   但测试里解析出的是 `npx.CMD` 而磁盘上是 `npx.cmd`；换到 Linux CI 上 `isFile()` 直接返回 false，
   **整个测试会挂**。改成"列目录 + `equalsIgnoreCase` 比对"，顺带返回磁盘真实大小写。
   ——这是"在 Windows 上跑通 ≠ 能进上游"的典型例子。
2. **重建 `ServerParameters` 不能丢 args/env**。解析后要重新 `builder(resolved).args(...).env(...)`，
   而 `ServerParameters` 构造时会把默认环境合并进 env，`getEnv()` 拿到的是合并后的结果——
   回填是幂等的，但要专门写测试钉住（`build_withServerParameters_preservesArgsAndEnv`）。

**实证对比（同一台机器、同一个 repro）**：

| 版本 | command | 结果 |
|---|---|---|
| 官方 1.13.0（D4 实测） | `npx` | 静默 **900,857 ms** 后抛 `McpToolLoadingException`，`node.exe` 从未启动 |
| 本次修复后 | `npx` | **3463–4061 ms 加载 14 个工具**（两次独立实测） |
| 两者 | `npx.cmd` | ~3.5 s，14 个工具（修复前后一致） |

**遗留决策（已写进 PR 描述）**：这个解析理论上更应该落在上游 Java MCP SDK 的
`StdioClientTransport`（Python SDK 就在那一层），放在 adk-java 是为了当天能修好、不等上游发版。
若维护者倾向挪走，可以直接搬。

---

### 1.2 提交状态

| 项 | 状态 |
|---|---|
| Google CLA | ✅ **已签并通过校验**（`cla/google` check = success） |
| 开 PR | ✅ **PR #1691** 已提交（2026-10-23） |
| PR 合规 | ✅ 单提交 / Conventional Commit 标题 / 4 文件 / mergeable=true |
| CI | `cla/google` ✅、`check-changes` ✅；`validation`、`pr-title-check`、`pr-commit-check` 尚未出现在 check-runs 中（GitHub 对首次贡献者的 workflow 需维护者批准后才运行） |

**PR 链接**：
https://github.com/google/adk-java/pull/1691

---

**简历一句话**（英文表述供参考）:
> Reported **and fixed** a Windows-specific defect in google/adk-java (Google's official Agent
> Development Kit): MCP stdio toolsets with npm-shim commands (`npx`) hung silently for a measured
> 900s before failing. Root-caused it (npm `.cmd` shims × Win32 CreateProcess × 5-min
> initializationTimeout × retry), then shipped a fix that resolves commands against `PATH`/`PATHEXT`
> the way the Python MCP SDK does — turning a 900s hang into a 4s startup, with 18 unit tests.

**中文面试话术**:
"我在跑通 ADK 的 MCP 示例时发现 Windows 上会静默挂死，深入排查后定位是 npm 的 cmd shim
与 Java ProcessBuilder 的兼容问题，叠加框架 5 分钟初始化超时和重试机制，实测会挂整整
15 分钟才报错。我把完整根因分析和最小复现提给了 Google 官方仓库，维护者复现后邀请我提 PR，
我按 Python SDK 的 shutil.which 语义写了修复：在交给 ProcessBuilder 之前把 npx 解析成 npx.cmd，
实测从挂死 900 秒变成 4 秒加载 14 个工具。"

**技术细节支撑**: 见 qa-manual.md Q14/Q16 + labs/mcp-own-test/ReproToolset.java +
docs/issue-final-adk-java.md

**后续动作（待办）**:
- [x] 签 Google CLA（2026-10-23，Individual CLA）
- [x] 修复代码 + 单元测试 + 本地实证（2026-10-23）
- [x] 分支推送到 fork
- [x] 开 PR → #1691（标题已过 Conventional Commit 检查）
- [x] 在 issue #1686 回复维护者（2026-10-23 10:55）
- [ ] 跟进 review：CONTRIBUTING 要求**单提交**，改动用 `git commit --amend` + `git push --force-with-lease`
- [ ] 若被合并或标记 → 更新本文件状态与简历表述
- [ ] 定期回访 issue（建议每 2-3 天，D7/D14 复盘时顺带检查）

---

### 1.3 issue 回复（2026-10-23 10:55 已发出）

维护者的原话是两个要求：「provide a pull request」+「**in a robust way**」。

**实际发出的是精简版**：只保留 PR 链接、一句机制说明、实测对比表、上游归属的开放问题。
「robust」论证小节由作者有意删去，不发。下面存档的是完整稿，供后续 PR 讨论或
reviewer 追问「为什么这么修」时取用。

**两处疑似丢词**（非有意删除，发出后才发现，可选修正）：
- `Measured on the same machine, same repro,` 后面缺 `bare npx:**`，表格前悬空
- `rather than in ADK — ython SDK does it.` 丢了 `that is the layer where the P`

若要修，编辑原评论即可，不要追加第二条。

```markdown
@hemasekhar-p Yes — opened #1691: https://github.com/google/adk-java/pull/1691

**What it does.** Before `ServerParameters` reaches `StdioClientTransport`, the command is
resolved against `PATH` / `PATHEXT` the way `shutil.which` does — the same lookup the Python
MCP SDK's `get_windows_executable_command` already performs. A bare `command("npx")` now reaches
`ProcessBuilder` as `npx.cmd`.

**Why I think this is robust rather than a workaround:**

- It mirrors the reference implementation instead of inventing new semantics — same PATHEXT
  ordering, same "already has an extension → try as-is" rule, same default PATHEXT when the
  variable is unset.
- It does **not** wrap the command in `cmd /c`. That would have been the quick fix, but it puts
  an extra process in front of the JSON-RPC stdio stream, which is exactly the kind of thing that
  breaks subtly later.
- It fails safe: a lookup miss returns the command untouched, so `ProcessBuilder` still reports
  its own, more useful error.
- It changes nothing outside Windows, nothing for commands that are already paths, and nothing in
  the public API — the helper is package-private.
- The match is made case-insensitively against the directory listing and returns the on-disk
  spelling (`npx.cmd`, not `npx.CMD`), so the behaviour does not depend on the case sensitivity of
  the filesystem it runs on.

**Measured on the same machine, same repro, bare `npx`:**

| | Result |
|---|---|
| 1.13.0 | silent 900,857 ms → `McpToolLoadingException`, `node.exe` never started |
| with the fix | ~3.5–4.1 s, 14 tools loaded |

Covered by 14 resolver tests and 4 builder tests; full `core` suite is 2609 tests, 0 failures.

**One open question for you.** This resolution arguably belongs in the upstream Java MCP SDK's
`StdioClientTransport` rather than in ADK — that is the layer where the Python SDK does it. I
placed it in `DefaultMcpTransportBuilder` so ADK users get the fix without waiting on an upstream
release, but the class can be lifted upstream as-is if you'd prefer that. Happy to rework either
way.

Thanks again for the quick turnaround on triage.
```

**注意**：编辑后 issue 的 `waiting on reporter` label 应会由维护者更新；若后续 reviewer 要求改动，
改完在 PR 回复的同时**在 issue 补一句 `updated the PR`**，避免被当成 stale 关闭。
## 2.（预留）后续 PR / 其他贡献
