# 正式 Issue（已提交）— A2A contextId 未用于 session

> **已提交**: https://github.com/google/adk-java/issues/1718（2026-10-28，open）
> 验证基线: `origin/main` @ `7ed0a93`（不是旧版本残留，最新主线仍存在）
> 配套 PR 补丁: `docs/patches/0001-a2a-context-id-session.patch`
> 配套 PR 描述: `docs/pr-adk-java-context-id.md`

> **本文件的 Body 与线上 #1718 逐字一致**（2026-10-28 校验），是提交存档。
> 提交后复核发现「Open questions」第 1 条对并发后果的描述有误，
> 更正已作为评论发布：https://github.com/google/adk-java/issues/1718#issuecomment-6460493156
> 修订后的方案见 `docs/pr-adk-java-context-id.md`。

---

## Title

```
AgentExecutor ignores the A2A contextId when creating a session, so multi-turn conversations start over every turn
```

## Body

```markdown
## Description

Sending a follow-up A2A message with the `contextId` returned by the previous
response does not continue the conversation: the remote agent starts from an
empty history on every turn.

A2A defines `contextId` as the anchor for a multi-turn exchange, and
`AgentExecutor` does look the session up by it. It never stores the session
under that id, though, so the lookup cannot succeed on the next turn.

## Environment

- google-adk: `main` at `7ed0a93` (this is where it was reproduced)
- JDK: 21
- Module: `a2a`
- OS: Windows 11 (platform-independent — this is pure Java logic)

I reproduced this on `main` at `7ed0a93` and did not re-run it against the 1.13.0
release. The code path is the same in both: `AgentExecutor.java`,
`BaseSessionService.java` and `InMemorySessionService.java` are unchanged
between the `v1.13.0` tag and `7ed0a93`.

## Root cause

`AgentExecutor.prepareSession`
(`a2a/src/main/java/com/google/adk/a2a/executor/AgentExecutor.java:312-321`):

```java
return service
    .getSession(appName, getUserId(ctx), ctx.getContextId(), Optional.empty())
    .switchIfEmpty(
        Maybe.defer(
            () -> {
              return service.createSession(appName, getUserId(ctx)).toMaybe();
            }));
```

The lookup uses `ctx.getContextId()`, but the creation calls the two-argument
overload. That overload is `createSession(appName, userId, null, null)`
(`core/src/main/java/com/google/adk/sessions/BaseSessionService.java:111-113`),
so `InMemorySessionService` generates a UUID
(`core/src/main/java/com/google/adk/sessions/InMemorySessionService.java:144-148`).

The result is a session stored under a fresh UUID, while every subsequent turn
asks for the session named by the context id. `getSession` misses, a new session
is created, and the agent runs against an empty history — one session per turn,
forever.

## Minimal reproduction

`a2a/src/test/java/com/google/adk/a2a/executor/ContextIdSessionReuseTest.java`,
using only the public API. Two requests share `context-1`; the agent records the
session id and the number of events it can see.

```java
@Test
public void execute_secondTurnWithSameContextId_reusesTheFirstTurnSession() {
  executor.execute(requestContext("task-1"), eventQueue);
  String firstTurnSessionId = agent.lastSessionId;

  executor.execute(requestContext("task-2"), eventQueue);

  assertThat(agent.lastSessionId).isEqualTo(firstTurnSessionId);
}

@Test
public void execute_secondTurnWithSameContextId_seesTheFirstTurnHistory() {
  executor.execute(requestContext("task-1"), eventQueue);
  int firstTurnEventCount = agent.lastEventCount;

  executor.execute(requestContext("task-2"), eventQueue);

  assertThat(agent.lastEventCount).isGreaterThan(firstTurnEventCount);
}

@Test
public void execute_firstTurn_storesTheSessionUnderTheContextId() {
  executor.execute(requestContext("task-1"), eventQueue);

  assertThat(sessionIdForContext()).isEqualTo(CONTEXT_ID);
}
```

Observed on `7ed0a93` — all three fail:

```
execute_firstTurn_storesTheSessionUnderTheContextId
  value of: sessionIdForContext()
  expected: context-1
  but was an empty string

execute_secondTurnWithSameContextId_reusesTheFirstTurnSession
  expected: 7a5974d7-5f4f-423f-9378-8b768f470c4f
  but was : cd5e4a35-ed66-4e3e-8f0d-43e25de8c9f3

execute_secondTurnWithSameContextId_seesTheFirstTurnHistory
  expected to be greater than: 1
  but was                    : 1
```

The third failure is the user-visible one: on turn 2 the agent sees exactly one
event — the message that just arrived. Nothing from turn 1.

## Fix

Pass the context id as the session id when creating the session:

```java
private Maybe<Session> prepareSession(
    RequestContext ctx, String appName, BaseSessionService service) {
  String contextId = ctx.getContextId();
  return service
      .getSession(appName, getUserId(ctx), contextId, Optional.empty())
      .switchIfEmpty(
          Maybe.defer(
              () ->
                  service
                      .createSession(
                          appName, getUserId(ctx), /* state= */ (Map<String, Object>) null,
                          contextId)
                      .toMaybe()));
}
```

With the fix the three tests above pass, and the full `a2a` suite is green
(142 tests, 0 failures).

A request that carries no context id is unaffected: `RequestContext` assigns a
UUID in `checkOrGenerateContextId` before the executor ever sees the request, so
`createSession` is never asked for a null id in practice — and the service still
generates one if it is.

## Alignment with adk-python

adk-python already behaves this way, which is why the Python samples keep their
conversation across turns:

- `src/google/adk/a2a/converters/request_converter.py:134` —
  `session_id=request.context_id`
- `src/google/adk/a2a/executor/a2a_agent_executor_impl.py:283-305` —
  `_resolve_session` gets by that id, and passes `session_id=session_id` into
  `create_session` when it is missing

The Java executor looks like a port that kept the lookup but dropped the id on
creation.

## Open questions

1. **Concurrent first turns.** Two requests carrying the same *new* context id
   can both miss `getSession` and both call `createSession`. With
   `InMemorySessionService`'s current default (`OVERWRITE`) the second call
   silently replaces the first session and discards its events; with `REJECT` it
   throws `SessionException`. Should `prepareSession` fall back to a second
   `getSession` when creation loses that race? I kept this change minimal, but
   it is a real hole and I am happy to close it in the same PR if you prefer.

2. **`VertexAiSessionService` id rules.** `validateSessionId` accepts
   `^[a-zA-Z0-9_-]+$` (`VertexAiSessionService.java:391`), but its javadoc says
   the backend is narrower: up to 63 characters of `[a-z0-9-]`, starting with a
   letter. A UUID context id starts with a hex digit roughly 10 times out of 16,
   so on Vertex a raw context id can be rejected where in-memory works. adk-python
   has the same exposure, so I did not change the id shape here — flagging it
   rather than fixing it unilaterally.

## A second, independent defect found while reproducing this

`EventConverter` reads the ids from the wrong metadata keys:

- `EventConverter.java:34-35` defines `ADK_TASK_ID_KEY = "adk_task_id"` and
  `ADK_CONTEXT_ID_KEY = "adk_context_id"`
- `ResponseConverter.updateEventMetadata` writes them through
  `AdkMetadataKey.TASK_ID` / `AdkMetadataKey.CONTEXT_ID`, which are
  `"a2a:task_id"` / `"a2a:context_id"`

Nothing in the repository writes `adk_*`, so `EventConverter.taskId(event)` and
`EventConverter.contextId(event)` always return `""` for events produced by the
A2A converters. `RemoteA2AAgent.prepareMessage` uses both accessors when it
builds a follow-up for a function response
(`RemoteA2AAgent.java:199-207`), so those ids are dropped from the outgoing
request.

`EventConverterTest` does not catch it because it builds the event with
`EventConverter.ADK_CONTEXT_ID_KEY` and then reads with the same constant — the
test is self-consistent regardless of the value.

I have this as a separate one-line fix with its own test, ready to send as a
follow-up PR; I left it out of this one to keep the change focused. Happy to fold
it in if you would rather review it together.

## Related observation (not a defect, a gap)

Even with both fixes, `RemoteA2AAgent.prepareMessage` only sets `contextId` on
the function-response path; a plain follow-up turn sends no context id at all
(`RemoteA2AAgent.java:209-213`). adk-python covers that with a
`forward_session_id_as_context_id` config option
(`_remote_a2a_agent.py:2017`), which has no Java equivalent. That is a design
decision — whether to add the option, and what its default should be — so I am
raising it here rather than deciding it in a bug-fix PR.

```

---

## 勘误评论（已发布 2026-10-28 12:14）

> **已提交**: https://github.com/google/adk-java/issues/1718#issuecomment-6460493156
> 触发：提交后复核并发竞态时，实测推翻了本 issue「Open questions」第 1 条里
> 「OVERWRITE 会静默丢弃事件」的表述。必须公开更正，不能让错误结论留在上游。
> 线上正文未编辑——GitHub 编辑不保留可见历史，公开更正更可信。

```markdown
A correction to the "Concurrent first turns" bullet in my original report, after
I actually measured it instead of reasoning about it.

I said `OVERWRITE` "silently replaces the first session and discards its events".
That is wrong. Two threads released from a barrier after both had read
`getSession` (so both really do create), measured on `7ed0a93`:

| Behaviour | createSession calls | agents run | final task states |
|---|---|---|---|
| `OVERWRITE` (current default) | 2 | 2 | all `COMPLETED`, both events stored |
| `REJECT` (documented next default) | 2 | 1 | one `FAILED` |

`OVERWRITE` loses nothing, because the session being replaced is the one that was
just created and is still empty; both turns then append to the same id. The real
casualty is `REJECT`, where the losing request reports a failed task to its peer.

So the fix now falls back to re-reading the session the winner created, and only
when creation reports "already exists" — any other creation failure still
surfaces. That keeps the concurrent case working under both behaviours for
services that raise `SessionException` with `SESSION_ALREADY_EXISTS`
(`InMemorySessionService`, `FirestoreSessionService`). It does not cover
`VertexAiSessionService`, which reports backend failures as transport errors — I
did not widen the check to "any creation error" because that would also mask
genuine failures.

Everything else in the report stands: the empty-history symptom, the root cause,
and the second defect in `EventConverter` were all reproduced as described.
```

**为什么不直接编辑原 issue 正文**：GitHub 编辑不保留可见历史，公开更正更可信；
且维护者可能已读过原文。
