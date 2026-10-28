# PR 描述（可直接提交版）— A2A contextId 作为 session id

> 对应 Issue: https://github.com/google/adk-java/issues/1718
> 分支: `fix/a2a-context-id-session`（**已推送** `pinqionghongbai/adk-java` @ `9e8dd4f`）
> 补丁: `docs/patches/0001-a2a-context-id-session.patch`（在 mockverse 仓库）
> 基线: `origin/main` @ `461f7d6`（2026-10-28 复核；issue 提交时是 `7ed0a93`）
> 规范: 单提交（CONTRIBUTING「Single Commit」）/ Google Java Style / 与 adk-python 对齐
> 状态: **PR #1719 已创建**（open，等维护者批准 CI 运行）

---

## Title

```
fix(a2a): create the session under the A2A context id
```

## Body

```markdown
Refs #1718. This fixes the multi-turn defect reported there. The issue also
reports a second, independent defect — `EventConverter` reading metadata keys
nothing writes — which is a separate change, so this PR does not close the
issue.

## Problem

A follow-up A2A message carrying the `contextId` from the previous response does
not continue the conversation — the agent starts from an empty history every
turn.

`AgentExecutor.prepareSession` looks the session up by `ctx.getContextId()`, but
creates it with `createSession(appName, userId)`, the overload that lets the
service pick the id. `InMemorySessionService` generates a UUID in that case, so
the session is stored under an id the next turn never asks for. Every turn misses,
creates another session, and runs against an empty history.

## Change

Pass the context id as the session id on creation:

```java
String userId = getUserId(ctx);
String contextId = ctx.getContextId();
return service
    .getSession(appName, userId, contextId, Optional.empty())
    .switchIfEmpty(Maybe.defer(() -> createSession(appName, userId, contextId, service)));
```

Requests without a context id are unaffected: `RequestContext` assigns a UUID in
`checkOrGenerateContextId` before the executor sees the request, and the service
still generates an id if one is somehow missing.

## Why this is the right layer

adk-python does exactly this, which is why the Python samples hold a conversation
across turns:

- `request_converter.py:134` — `session_id=request.context_id`
- `a2a_agent_executor_impl.py:283-305` — `_resolve_session` gets by that id and
  passes `session_id=session_id` into `create_session`

The Java executor kept the lookup and dropped the id on creation.

`RunConfig.autoCreateSession()` defaults to `false`, so `Runner.runAsync` cannot
create the session on its own — `prepareSession` is the only place this can be
decided.

## The race this introduces, and the fallback

Before this change every turn invented a fresh UUID, so two first turns could
never collide. Now they can, and I measured what each duplicate-id behaviour
actually does (two threads released from a barrier after both had read):

| Behaviour | Creates | Agents run | Final task states |
|---|---|---|---|
| `OVERWRITE` (current default) | 2 | 2 | all `COMPLETED`, both events stored |
| `REJECT` (documented next default) | 2 | 1 | one `FAILED` |

`OVERWRITE` loses nothing: the session it replaces was the one just created and
still empty, and both turns then append to the same id. `REJECT` is the real
problem — the losing request hands its peer a failed task. So `createSession`
re-reads the session the winner created when creation reports "already exists",
and rethrows anything else:

```java
.onErrorResumeNext(
    error -> {
      if (!isSessionAlreadyExists(error)) {
        return Single.error(error);
      }
      return service.getSession(appName, userId, contextId, Optional.empty())
          .switchIfEmpty(Single.error(error));
    })
```

The check is deliberately narrow: `SessionException` whose message is
`SESSION_ALREADY_EXISTS`, which is what `InMemorySessionService` and
`FirestoreSessionService` both raise.

**Known gap.** `VertexAiSessionService` reports backend failures as
`UncheckedIOException` / transport errors rather than `SessionException`, so a
concurrent first turn on Vertex would still fail one request. I left it that way
rather than retrying on *any* creation error, because that would also mask genuine
failures. Broadening it is a one-line change if you would prefer the wider
net — say the word.

## Tests

Covered at two levels: the executor driven directly, and the executor reached
through the A2A request handler the way a real client reaches it.

### `ContextIdSessionReuseTest` (5 cases, drives the executor)

| Case | Without the fix |
|---|---|
| first turn stores the session under the context id | fails — stored under a UUID |
| second turn reuses the first turn's session | fails — a different session id each turn |
| second turn sees the first turn's history | fails — 1 event, the one just sent |
| a turn that loses the creation race joins the winner's session | fails — `lastSessionId` is null |
| an unrelated creation failure still fails the turn | passes (guard) |

Removing only the fallback fails exactly the fourth case; removing the whole fix
fails the first four.

### `ContextIdEndToEndTest` (2 cases, through `DefaultRequestHandler.onMessageSend`)

The request context here is built by the A2A SDK from `MessageSendParams`, not by
the test, so the context id travels the whole path a JSON-RPC client takes.

| Case | Without the fix |
|---|---|
| two sends sharing a context id continue the same conversation | fails — `expected: ctx-e2e` / `but was: 308b7f6a-…` |
| two sends without a context id get separate conversations | passes (guard against collapsing every request into one session) |

### Diagnostic run

A temporary print (not committed) makes the symptom concrete:

| | turn 1 | turn 2 (same `contextId`) |
|---|---|---|
| without the fix | session `7ef443c4-…`, 1 event visible | session `f4ba6903-…`, **1 event visible** |
| with the fix | session `context-1`, 1 event visible | session `context-1`, **3 events visible** |

`getSession(app, user, "context-1")` returns `EMPTY` before the fix and the live
session (4 events) after it.

### Totals

Full `a2a` module: **146 tests, 0 failures** (139 before, +7 here).
`mvn fmt:check`: 0 non-complying. Verified by re-applying the patch onto a clean
`origin/main` worktree and running the CI command
(`./mvnw -Prelease -pl a2a -am clean package`).

## Behaviour changes worth knowing about

Two consequences of making the session id equal the context id. Both follow from
the A2A semantics and both match adk-python, but they are new in Java, so I would
rather state them than let them be discovered later.

**1. Whoever holds the context id can read that conversation.** The user id was
already derived from it (`A2A_USER_<contextId>`), so knowing a context id always
identified the owner. Before this change the session id was a separate random
UUID, which made the history unguessable even with the context id in hand; now
the history is reachable with it. That is what a conversation anchor means, and a
server-generated context id is a UUID, so the practical exposure is unchanged for
clients that let the service generate it. A client that hard-codes a context id
(as the README's `cli-demo-context` example does) shares that conversation with
anyone using the same string — correct per the spec, worth knowing.

**2. Existing conversations do not carry over.** Sessions created before this
change are stored under generated UUIDs, with no record of which context id they
belonged to, so there is nothing to migrate: after upgrading, a follow-up with an
old context id starts a fresh session. Since the old code never continued a
conversation either, this is not a regression — but it is not a silent upgrade
path either.

## Open questions

**`VertexAiSessionService` id rules.** Local validation accepts
`^[a-zA-Z0-9_-]+$`, but the javadoc says the backend wants `[a-z0-9-]` starting
with a letter, up to 63 characters. A UUID context id starts with a hex digit
about 10 times in 16, so it can be rejected on Vertex where in-memory works.
adk-python has the same exposure, so I did not change the id shape here — happy
to discuss whether ADK should normalise it.

## The issue's second defect, sent separately

`EventConverter` reads `adk_task_id` / `adk_context_id`, but `ResponseConverter`
writes `a2a:task_id` / `a2a:context_id` (via `AdkMetadataKey`), so
`EventConverter.taskId` and `contextId` always return `""` for A2A-converted
events. That is a separate commit with its own test, kept out of this PR so each
change stays reviewable on its own — which is why this PR says `Refs` rather than
`Fixes`.

It also adds a test asserting the two constants equal `AdkMetadataKey`'s values,
because the existing round-trip tests build the event with the same constant they
read it back with — which is exactly why they never noticed.

Happy to fold it into this PR instead if you would rather review both together;
that would then close the issue.
```

---

## 提交前检查清单

| 项 | 状态 |
|---|---|
| 单提交 | ✅ 1 commit / 3 files（1 main + 2 test） |
| Conventional Commit 标题 | ✅ `fix(a2a): ...` |
| **issue 关联** | ✅ `Refs #1718`（非 `Fixes`——issue 第二个缺陷另发，合并本 PR 不应关闭 issue） |
| **正文语言** | ✅ 全英文，无中文内部备注残留 |
| **CI 同款命令** | ✅ `./mvnw -Prelease -pl a2a -am clean package` → BUILD SUCCESS（core 2988 / a2a 146，0 失败） |
| **CI 格式步骤** | ✅ `git diff --exit-code` 干净（fmt 0 reformatted） |
| `mvn -pl a2a fmt:check` | ✅ 0 non-complying |
| `mvn -pl a2a test` | ✅ 146 / 0 failures（含端到端 2 例） |
| **无 deprecated API** | ✅ 测试用 `immutableEvents()` 而非已废弃的 `events()`（2026-10-28 修正） |
| 基于最新 `origin/main` | ✅ `461f7d6`（上游 8 个新提交后仍干净应用、无冲突） |
| 回归证明 | ✅ 移除全部修复 → 7 例中 5 例失败（单测 4/5 + 端到端 1/2）；仅移除兜底 → 恰好竞态用例失败 |
| **已推送** | ✅ `pinqionghongbai/adk-java` @ `9e8dd4f`，SHA 与本地一致 |
| 公共 API 变化 | ✅ 无（全部为 private 方法） |
| 无 API Key / 本地路径 | ✅ 纯逻辑改动 |
| 与 adk-python 对齐 | ✅ 已在 PR 正文说明 |
| Google CLA | 已签（#1691 时通过） |

> **Java 17/21/25**：模块 `maven.compiler.release=17`，编译已在该 release 下通过；
> 未使用 21+ 语法（测试中的 `instanceof` 模式匹配为 Java 16+）。本机只验证了 21。

> **验证口径（本地经验，不进 PR 正文）**：改动 `static final String` 常量的值后，
> 同目录增量编译会复用旧测试类（javac 把常量值内联进去了）。复现/验证一律先
> `rm -rf a2a/target/{classes,test-classes}` 再跑，否则会看到与改动无关的假失败。

## 操作步骤

### 当前状态（2026-10-28 已完成）

| 项 | 值 |
|---|---|
| 分支 | `fix/a2a-context-id-session` |
| 提交 | `9e8dd4f`（单提交，父提交 `461f7d6`） |
| 已推送 | ✅ `pinqionghongbai/adk-java`（fork），SHA 与本地一致 |
| PR | ✅ [#1719](https://github.com/google/adk-java/pull/1719) 已创建 |

远程配置（在 adk-java 仓库内）：

```
origin → https://github.com/google/adk-java.git      （上游，只读）
fork   → https://github.com/pinqionghongbai/adk-java.git（本人 fork，可推）
```

### 开 PR

浏览器打开（已预选 base/compare，需已登录 GitHub）：

```
https://github.com/google/adk-java/compare/main...pinqionghongbai:adk-java:fix/a2a-context-id-session?expand=1
```

页面会显示 `Able to merge. These branches can be automatically merged.`，
点 **Create pull request**，标题与正文分别取上面的 Title 和 Body。

### 从零重建（仅在需要时，例如补丁改动后）

```bash
# 1. 在 adk-java 仓库内，基于最新上游重建分支
cd adk-java
git fetch origin
git branch -D fix/a2a-context-id-session          # 若已存在
git checkout -b fix/a2a-context-id-session origin/main

# 2. 应用补丁（补丁在 mockverse 仓库，不在 adk-java）
git am "../mockverse/docs/patches/0001-a2a-context-id-session.patch"

# 3. 本地验证（先清增量产物，见上方「验证口径」）
rm -rf a2a/target/classes a2a/target/test-classes
./mvnw -pl a2a test
./mvnw -pl a2a fmt:check

# 4. 推送到 fork（不是 origin）
git push -u fork fix/a2a-context-id-session
```

### review 后修改

CONTRIBUTING 硬性要求单提交，所以改法是：

```bash
git commit --amend
git push fork fix/a2a-context-id-session --force-with-lease
```
