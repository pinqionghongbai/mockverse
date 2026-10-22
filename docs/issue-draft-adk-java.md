# Issue 草稿：google/adk-java（英文，待主人过目后提交）

> 提交地址: https://github.com/google/adk-java/issues/new
> 状态: 草稿 —— 等长跑实验(20分钟理论)确认最终失败形态后可微调"Expected/Saw"部分
> 证据链: D4 实战排坑 + ReproToolset.java 最小复现(25行, 纯公共API)

---

**Title:**
```
McpToolset with command="npx" hangs silently for ~20 minutes on Windows (npm shims are .cmd scripts)
```

**Body:**

## Description

The `examples/mcpfilesystem` sample (and any `McpToolset` built with `command("npx")`) hangs
silently on Windows. No error is printed, no exception is thrown, and no `node.exe` process is
ever spawned — the caller just blocks inside `McpToolset.getTools()`.

## Environment

- OS: Windows 11 (10.0.26200)
- JDK: 21.0.5 (Oracle)
- ADK: 1.13.0
- Maven: 3.9.8
- Node.js: 24.15.0 (npx 11.12.1, installed at the standard location with `npx.cmd` shim)

## Root cause

On Windows, npm installs command shims as batch scripts (`npx.cmd`), not `npx.exe`. Java's
`ProcessBuilder` cannot execute a bare `npx` command (it is neither a PE executable nor
resolvable via PATHEXT), so the stdio subprocess never starts.

What makes this especially hard to diagnose:

1. The failure is not surfaced as an `IOException`. `McpSessionManager.initializeSession(...)`
   builds the client with a default `initializationTimeout` of **5 minutes**
   (core/src/main/java/com/google/adk/tools/mcp/McpSessionManager.java:81-84), so
   `client.initialize()` blocks waiting for a response from a process that never started.
2. The sync `McpToolset.getTools()` retry logic (MAX_RETRIES = 3, 100 ms delay) then retries
   the same failing initialization, so the total silent hang is roughly **4 x 5 minutes**.
3. With no SLF4J provider on the classpath (the default for the samples), even the retry
   ERROR logs are invisible.

## Minimal reproduction (public API only, ~25 lines)

```java
StdioServerParameters params = StdioServerParameters.builder()
    .command("npx")   // hangs; "npx.cmd" works
    .args(ImmutableList.of("-y", "@modelcontextprotocol/server-filesystem", "<some-dir>"))
    .build();

McpToolset toolset = new McpToolset(params.toServerParameters());
List<BaseTool> tools = toolset.getTools(null).toList().blockingGet(); // blocks indefinitely
```

## Observed behavior

| command | result |
|---|---|
| `npx` | blocks indefinitely (no error, no node.exe process, no logs without an SLF4J provider) |
| `npx.cmd` | OK in ~3.5 s, 14 tools loaded |

A plain `new ProcessBuilder("npx", "-v").start()` on the same machine fails immediately with
`CreateProcess error=2`, which confirms the JVM/OS level cause.

## Workaround

Use the `.cmd` extension explicitly on Windows:

```java
.command("npx.cmd")
```

## Suggestions

1. Add a Windows note to `examples/mcpfilesystem/README.md` (lowest effort, immediate relief
   for new users).
2. Optionally, in `McpToolset`/`DefaultMcpTransportBuilder`, either auto-map well-known npm
   shim commands (`npx`, `npm`, `node`) to their `.cmd` variants on Windows, or fail fast
   with a clear error message when the command cannot be executed, instead of waiting out
   the 5-minute initialization timeout.

Happy to send a PR for either suggestion. Thanks!

---

# 中文备注（给主人，不进 issue）

- 建议提交前: 长跑实验确认"20分钟静默后是否最终抛错"——若是,措辞"blocks for ~20 minutes then throws McpToolLoadingException"更准确; 若否(永久挂死), 保持 "indefinitely"
- 提交时附上 ReproToolset.java 或精简版
- 提交后把 issue 链接记入 docs/qa-manual.md 和简历素材("向 google/adk-java 报告 Windows 平台缺陷, 含最小复现与修复建议")
- 若维护者回应积极 → 可跟 PR(需签 Google CLA, 线上电子签)
