# 正式 Issue（可直接提交版）

> 提交地址: https://github.com/google/adk-java/issues/new
> 标题填入 Title, 正文整体复制 Body, 附件可补 ReproToolset.java
> 每个部分浮浮酱都在末尾用 [注] 标了依据, 提交前把 [注] 行删掉即可

---

## Title

```
McpToolset hangs silently for ~5 minutes per retry (3 retries) when stdio command is an npm shim ("npx") on Windows
```

## Body

```markdown
## Description

The `examples/mcpfilesystem` sample — and any `McpToolset` built with
`command("npx")` — appears to hang indefinitely on Windows. No error is
printed and no `node.exe` process is ever spawned; the caller just blocks
inside `McpToolset.getTools()`.

After digging into it, the "hang" is actually a very slow failure loop:
each initialization attempt waits out the full default
`initializationTimeout` (5 minutes), then the sync retry logic retries up
to 3 times, so the program stays silent for roughly 4 x 5 = 20 minutes
before a `McpToolLoadingException` (wrapped
`Client failed to initialize by explicit API call`) finally surfaces —
and even that is invisible unless an SLF4J provider is on the classpath,
which the samples don't include by default.

## Environment

- OS: Windows 11 Home (10.0.26200)
- JDK: Oracle 21.0.5
- google-adk: 1.13.0
- Maven: 3.9.8
- Node.js: 24.15.0 (npx 11.12.1, standard npm-for-Windows install with
  `npx.cmd` shim)

## Root cause

On Windows, npm installs command shims as batch scripts (`npx.cmd`), not
executables. Java's `ProcessBuilder` cannot execute a bare `npx` command
(it is not a PE executable and is not resolved through PATHEXT), so the
stdio subprocess never starts.

A plain `new ProcessBuilder("npx", "-v").start()` on the same machine
fails immediately with `CreateProcess error=2`, which confirms the
JVM/OS-level cause. Inside ADK, however, the failure is masked by:

1. `McpSessionManager.initializeSession(...)` defaults the
   `initializationTimeout` to **5 minutes**
   (`core/src/main/java/com/google/adk/tools/mcp/McpSessionManager.java`,
   the `Duration.ofMinutes(5)` fallback), so `client.initialize()` waits
   for a response from a process that never started.
2. The sync `McpToolset.getTools()` retry logic (`MAX_RETRIES = 3`,
   100 ms delay) then re-runs the same doomed initialization.
3. Without an SLF4J provider (the default for the samples), even the
   retry ERROR logs are swallowed, leaving zero output on the console.

Observed log once an SLF4J provider is added:

```
ERROR com.google.adk.tools.mcp.McpToolset - Unexpected error during tool loading, retry attempt 1
java.lang.RuntimeException: Client failed to initialize by explicit API call
    at io.modelcontextprotocol.client.LifecycleInitializer...
```

## Minimal reproduction (public API only)

```java
StdioServerParameters params = StdioServerParameters.builder()
    .command("npx")   // hangs; "npx.cmd" works
    .args(ImmutableList.of(
        "-y", "@modelcontextprotocol/server-filesystem", "<some-dir>"))
    .build();

McpToolset toolset = new McpToolset(params.toServerParameters());
List<BaseTool> tools = toolset.getTools(null).toList().blockingGet();
// blocks: ~5 min per attempt, up to 3 retries, then throws
```

## Observed behavior

| command          | result                                                       |
| ---------------- | ------------------------------------------------------------ |
| `npx`            | silent block ≥ 5 min per retry; ~20 min total, then `McpToolLoadingException`; no `node.exe` ever spawned |
| `npx.cmd`        | OK in ~3.5 s, 14 tools loaded                                |

## Workaround

Use the `.cmd` extension explicitly on Windows:

```java
.command("npx.cmd")
```

## Suggestions

1. Add a Windows note to `examples/mcpfilesystem/README.md` (lowest
   effort, immediate relief for new users).
2. Optionally, fail fast with a clear error when the configured command
   cannot be executed at all (e.g. probe with `ProcessBuilder.start()`
   before the 5-minute timeout), or auto-map well-known npm shims
   (`npx` / `npm` / `node`) to their `.cmd` variants on Windows.

Happy to send a PR for either suggestion. Thanks!
```

---

## 提交步骤（给主人）

1. 打开 https://github.com/google/adk-java/issues/new
2. Title 处粘贴上面 Title 的单行内容
3. Body 整体粘贴 markdown 块内容
4. （可选）把 `labs/mcp-own-test/ReproToolset.java` 拖进附件区
5. Submit new issue
6. 提交后把 issue 链接告诉浮浮酱 —— 归档进 qa-manual.md + 简历素材（"向 google/adk-java 报告 Windows 平台缺陷，提供最小复现与修复建议"）
```
