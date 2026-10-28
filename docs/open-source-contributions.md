# 开源贡献记录

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

**技术细节支撑**: 见 labs/mcp-own-test/ReproToolset.java +
docs/issue-final-adk-java.md

**后续动作（待办）**:
- [x] 签 Google CLA（2026-10-23，Individual CLA）
- [x] 修复代码 + 单元测试 + 本地实证（2026-10-23）
- [x] 分支推送到 fork
- [x] 开 PR → #1691（标题已过 Conventional Commit 检查）
- [x] 在 issue #1686 回复维护者（2026-10-23 10:55）
- [ ] 跟进 review：CONTRIBUTING 要求**单提交**，改动用 `git commit --amend` + `git push --force-with-lease`
- [ ] 若被合并或标记 → 更新本文件状态与简历表述
- [ ] 定期回访 issue（建议每 2-3 天检查一次）

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
## 2. google/adk-java — Issue #1718（2026-10-28）

**Issue 链接**: https://github.com/google/adk-java/issues/1718（open，署名 pinqionghongbai）
**标题**: AgentExecutor ignores the A2A contextId when creating a session, so multi-turn
conversations start over every turn

**来源**：D16 的九查在 MockVerse 实测里发现「同一 contextId 第二轮问不出第一轮内容」，
当时判断为上游 bug 并挂 STATUS 挂起表（理由：修它要 fork ADK 或写子类）。本次回访上游时
确认该缺陷在最新 `main`（`7ed0a93`）仍然存在，且上游无同类 issue/PR，遂补上可复现证据后提交。

**证据构成（比 #1686 更硬）**：

| 项 | 内容 |
|---|---|
| 复现方式 | 确定性单测 `ContextIdSessionReuseTest`（3 例，只用公开 API），不依赖网络/时序 |
| 基线 | `origin/main` @ `7ed0a93`（不是旧版本残留） |
| 回归证明 | 移除修复后 3 例全失败；其中「第二轮只看到 1 条事件」是用户可感知症状 |
| 修复验证 | 补上后 a2a 模块 142 用例 / 0 失败 |
| 版本一致性 | `AgentExecutor` / `BaseSessionService` / `InMemorySessionService` 在 `v1.13.0` 与 `7ed0a93` 之间无差异 |

**根因（三层）**：

1. `AgentExecutor.prepareSession`（`a2a/.../executor/AgentExecutor.java:312-321`）用
   `ctx.getContextId()` 查 session，创建时却调 `createSession(appName, userId)`。
2. 该重载 = `createSession(appName, userId, null, null)`
   （`core/.../BaseSessionService.java:111-113`）。
3. `InMemorySessionService` 收到 null 就生成 UUID
   （`InMemorySessionService.java:144-148`）→ 存进去的 id ≠ contextId → 下一轮永远查不到。

**顺带发现的第二个缺陷**（已拆成独立 PR，未混进本条）：
`EventConverter` 读 `adk_task_id` / `adk_context_id`，而 `ResponseConverter` 经
`AdkMetadataKey` 写的是 `a2a:task_id` / `a2a:context_id`。全仓无人写 `adk_*`，
所以两个访问器恒返回空串；`EventConverterTest` 用同一个常量造事件再读，自洽掩盖了缺陷。

**主动交代的边界（写进 issue 而非回避）**：

- 并发首轮竞态：同 contextId 两个首轮可双双 create，`OVERWRITE` 下静默丢事件、`REJECT` 下抛异常
- `VertexAiSessionService` 的 id 规则比本地校验更窄（后端要小写字母开头，UUID 首位约 10/16 是数字）
- `RemoteA2AAgent.prepareMessage` 普通追问轮根本不发 contextId；Python 有
  `forward_session_id_as_context_id` 配置，Java 无对应物——明确划为设计决策，另议

**下一步（待办）**：

- [x] 提交 issue #1718（2026-10-28）
- [x] 修复补丁 + 测试已备好并通过干净基线验证（`docs/patches/0001-a2a-context-id-session.patch`）
- [x] 2026-10-28 复现复核：全新 worktree（`origin/main` @ `7ed0a93`，主代码零改动、只加测试）
      两个缺陷均稳定复现；诊断输出显示同一 contextId 两轮拿到两个不同 UUID session，
      第二轮仍只看到 1 条事件；`getSession("context-1")` 返回 `EMPTY`
- [x] 2026-10-28 对抗复核：用真实 `RequestContext`（非 mock）、无 contextId 请求、
      不同 contextId、`REJECT` 严格模式、双线程 barrier 竞态共 5 项验证，
      修复全部成立；**同时推翻了自己在 issue 里的一条错误断言**（见下）
- [x] 并发竞态修复已并入主 PR（`onErrorResumeNext` 仅在 "already exists" 时重查一次）
- [ ] 待维护者回应后决定是否同时提交并发竞态修复 ← 已提前完成，等待 review 反馈

**复核中发现并修正的一处错误结论**：

初稿 issue 写「`OVERWRITE` 下并发首轮会静默替换并丢弃事件」。实测（双线程 barrier
强制两个请求都先读到空）结果相反：`OVERWRITE` 下 2 次 create、2 个 agent 都跑、
**2 条事件都在**——被替换的是刚创建的空 session，两个 turn 随后都 append 到同一 id。
真正的问题是 `REJECT`：2 次 create 只有 1 个 agent 跑，另一个请求以 `FAILED` 收场。
据此把修复从「只传 contextId」扩展为「再补一次 already-exists 重查」，并把勘误
评论公开提交（2026-10-28 12:14）：
https://github.com/google/adk-java/issues/1718#issuecomment-6460493156

**教训**：issue 里的竞态后果是推理出来的，不是测出来的；公开仓库的断言必须实测，
否则会在上游留下错误结论。与 D16「措辞被十五查推翻」是同一类错误。

**issue 关联方式定为 `Refs` 而非 `Fixes`（2026-10-28 决定）**：issue 报告的**第二个缺陷
（`EventConverter` 读错元数据键）未被主 PR 修复**，若用 `Fixes #1718`，PR 合并时 GitHub
会自动关闭 issue，留下未修的缺陷且无人再追。因此改为 `Refs`，第二个缺陷走独立 PR，
两个都合并后再由维护者关闭 issue。

**可复现证据（不依赖任何临时脚本）**：

1. **看 diff**——主 PR 只改了 3 个文件，`EventConverter.java` 不在其中：
   ```bash
   git diff --name-only origin/main...fix/a2a-context-id-session
   # a2a/src/main/java/com/google/adk/a2a/executor/AgentExecutor.java
   # a2a/src/test/java/com/google/adk/a2a/executor/ContextIdEndToEndTest.java
   # a2a/src/test/java/com/google/adk/a2a/executor/ContextIdSessionReuseTest.java
   ```
2. **跑测试**——把元数据补丁里的 `ContextIdRoundTripTest`（已提交、可追溯）单独放到
   PR 分支上跑，仍失败：
   ```bash
   git apply --include="a2a/src/test/java/com/google/adk/a2a/converters/ContextIdRoundTripTest.java" \
     docs/patches/0001-a2a-metadata-keys.patch
   ./mvnw -pl a2a -Dtest=ContextIdRoundTripTest test
   # ContextIdRoundTripTest.taskToEvent_thenContextId_returnsThePeerContextId
   #   value of: contextId(...)  expected: context-1  but was an empty string
   # Tests run: 1, Failures: 1
   ```

> **教训（第二次犯）**：第一次是 issue 里的 `OVERWRITE` 断言，这次是引用一个已删除的临时
> 测试当"实证"。**凡是写进文档或上游的断言，必须给出别人能独立重跑的命令**，不能只贴
> 一段自己跑过、代码已不存在的输出。

- [x] 2026-10-28 **PR #1719 已提交**：https://github.com/google/adk-java/pull/1719
      （open，1 commit / 3 files / +462 −6，`cla/google` ✅、`check-changes` ✅；
      其余 workflow 为 `action_required`，等维护者批准运行）
- [x] 2026-10-28 提交后自查：发现两处新测试用了已废弃的 `Session.events()`，
      改为 `immutableEvents()` 后 `--amend` + `--force-with-lease` 推送（`08e28fb` → `9e8dd4f`）
- [ ] 第二个缺陷（元数据键）的独立 PR 待发

**验证口径（避免踩坑）**：改动 `static final String` 常量后，同目录增量编译会复用旧测试类
（javac 内联常量），必须先 `rm -rf a2a/target/{classes,test-classes}` 再跑，否则出现与改动
无关的假失败——本次复核就撞到过一次，已记入 PR 文档。

**用例数口径**（a2a 模块，基线 `origin/main` = 139）：

| 组合 | 用例数 | 结果 |
|---|---|---|
| 主修复（+5 单测 +2 端到端） | 146 | 0 失败，连跑 3 次稳定 |
| 元数据键修复（+2） | 141 | 0 失败 |
| 两者叠加（按顺序 `git am`） | 148 | 0 失败，无冲突 |

**CI 同款命令**（`validation.yml` 实际执行的是 `./mvnw -Prelease clean package` +
`git diff --exit-code`）：

| 检查 | 结果 |
|---|---|
| `./mvnw -Prelease -pl a2a -am clean package` | ✅ BUILD SUCCESS：core **2988** / a2a **146**，0 失败 |
| `git diff --exit-code`（格式未被改写） | ✅ 干净，fmt 0 reformatted |

**上游漂移复核（2026-10-28 晚）**：`7ed0a93` → `461f7d6`（8 个新提交，含
`SessionUtils` / `FirestoreSessionService` 改动）。两个补丁在新上游**均干净应用、无冲突**，
测试结果不变。`FirestoreSessionService` 仍抛 `SessionException(SESSION_ALREADY_EXISTS)`，
兜底的异常契约未受影响。

**已知覆盖缺口（写进 PR 而非隐藏）**：竞态兜底只认 `SessionException` +
`SESSION_ALREADY_EXISTS`（`InMemorySessionService` / `FirestoreSessionService` 都是这样抛）。
`VertexAiSessionService` 走远端 HTTP，失败是 `UncheckedIOException` 一类，**不在兜底范围内**——
Vertex 上并发首轮仍会有一个请求失败。没有放宽成「任何创建错误都重查」，因为那会连真实故障
一起吞掉。这一点在 issue 勘误和 PR 正文里都写明了。

**两处行为变化（主动写进 PR，不等 reviewer 发现）**：

1. **持有 contextId 即可读到该会话历史**。修复前 `userId` 已由 contextId 派生
   （`A2A_USER_<contextId>`），但 `sessionId` 是独立随机 UUID，光有 contextId 拿不到历史；
   修复后两者合一，历史可直达。这是「会话锚点」的应有语义，且服务端生成的 contextId 是
   UUID，风险面不变；但客户端若硬编码 contextId（如 README 的 `cli-demo-context`），
   同一字符串的使用者会共享该会话——符合规范，但值得知情。
2. **升级后旧会话不延续**。修复前的 session 以随机 UUID 存储，没有 contextId 记录，
   无处迁移；升级后带旧 contextId 的追问会新开会话。因为旧代码本来也无法多轮，不算回归。

---

## 3.（预留）后续 PR / 其他贡献

**一次险些发出的坏提交（2026-10-28，已拦下）**：做「移除修复 → 验证回归」时用
`git checkout origin/main -- AgentExecutor.java` 把上游版本写进了**暂存区**，验证完
`cp` 回工作区却没有 `git add`。随后 `--amend` 只带了两个测试文件，生成的新提交
`cc2a418` **只含测试、不含修复**——若直接 force-push，PR 会变成一个「测试必然失败」
的坏提交。靠 amend 后复查 `git show --stat`（文件数从 3 变 2）发现，补 `git add`
重新 amend 为 `9e8dd4f` 才推送。

**教训**：`git checkout <ref> -- <path>` 会**同时写工作区和暂存区**；验证类操作之后
必须确认 `git status` 没有 `MM`，且 amend 后要核对 `git show --stat` 的文件数。
