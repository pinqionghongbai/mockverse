# PR 草稿（已提交）— google/adk-java#1686 修复

> **状态：已于 2026-10-23 提交为 PR #1691**
> https://github.com/google/adk-java/pull/1691
>
> 本文件保留为归档，记录 PR 正文原文。后续若要改动 PR 描述，在此改完再粘贴到 GitHub。
>
> 分支：`pinqionghongbai:fix/windows-npm-shim-resolution` → `google/adk-java:main`
> 提交：`52b539e`（单提交，符合 CONTRIBUTING）

---

## Title

```
fix: resolve Windows command shims for stdio MCP servers
```

## Body

```markdown
Fixes #1686.

## Problem

On Windows, `McpToolset` configured with an npm-shim command (`command("npx")`) never starts a
subprocess. `ProcessBuilder` delegates to Win32 `CreateProcess`, which appends only `.exe` to an
extension-less command and never consults `PATHEXT`. npm installs its CLI tools as batch shims
(`npx.cmd`, `npm.cmd`), so `CreateProcess` fails with `error=2` even though `npx` works fine in a
shell — the shell does the `PATHEXT` lookup, `CreateProcess` does not.

The failure is invisible to callers: no process is ever spawned, so the MCP client waits out
`initializationTimeout` (default 5 min) and retries (`MAX_RETRIES = 3`, i.e. 3 total attempts).
Measured on the reporter's machine with `adk-java` 1.13.0: **900,857 ms** of silence, then
`McpToolLoadingException`, with `node.exe` never started.

## Fix

Resolve the command against `PATH` / `PATHEXT` before handing `ServerParameters` to
`StdioClientTransport`, mirroring `shutil.which` as used by the Python MCP SDK — which is where
`get_windows_executable_command` already does this for the Python transports. This keeps the Java
side aligned with the other SDKs, per CONTRIBUTING.

New package-private helper `WindowsExecutableResolver`:

- a command that already ends with a `PATHEXT` extension is tried as-is;
- any other command is tried with each `PATHEXT` extension appended, in `PATHEXT` order;
- the match is returned with its **on-disk spelling** (`npx.cmd`, not the `npx.CMD` that
  concatenating `PATHEXT` would produce);
- non-Windows, explicit paths (containing `/` or `\`), and lookup misses all return the command
  unchanged, so `ProcessBuilder` still surfaces its own, more useful error.

`DefaultMcpTransportBuilder.build()` applies the resolver only for `ServerParameters` and rebuilds
the parameters with the caller's args and env intact.

## Measured result

Same machine, same repro, bare `command("npx")`:

| Version | Command | Result |
|---|---|---|
| 1.13.0 | `npx` | silent 900,857 ms → `McpToolLoadingException`, `node.exe` never started |
| this PR | `npx` | **3.5–4.1 s** (two independent runs), 14 tools loaded |
| both | `npx.cmd` | ~3.5 s, 14 tools |

## Tests

- `WindowsExecutableResolverTest` — 14 cases: non-Windows passthrough, explicit paths, shim
  resolution, `PATHEXT` ordering, multiple `PATH` entries, not-found passthrough, missing
  `PATHEXT` fallback, custom `PATHEXT`, case-insensitive OS name.
- `DefaultMcpTransportBuilderTest` — 4 new cases: args/env survive the command substitution (runs
  on every platform), the resolved command reaches the transport, and a Windows+Node-gated
  end-to-end shim assertion.
- Full `core` suite: 2609 tests, 0 failures, 0 errors, 30 skipped (pre-existing assumption-based
  skips). With `-Dos.name=Linux` the Windows-only case is skipped and the rest still pass.
- `mvn -Prelease clean package -pl core -am` — the command CI runs — passes, including the javadoc
  jar (`doclint=all,-missing`).
- `mvn fmt:check` (all modules): 0 non-complying.

## Note on placement

The resolution arguably belongs in the upstream Java MCP SDK's `StdioClientTransport` (that is
where the Python SDK does it). It is placed in `DefaultMcpTransportBuilder` so ADK users get the
fix without waiting on an upstream release; if you would rather move it upstream, it can be lifted
as-is.
```

---

## 提交后检查清单

| 项 | 状态 |
|---|---|
| Google CLA | ✅ 已签，`cla/google` check = success |
| 分支已推送 | ✅ `52b539e` on `pinqionghongbai/adk-java` |
| 单提交 | ✅ GitHub 显示 1 commit |
| PR 标题合规 | ✅ 通过 `pr-title-check.yml` 的 Conventional Commit 正则（本地实测） |
| google-java-format | ✅ 全模块 `fmt:check` 0 non-complying（core 422 文件） |
| 全量测试 | ✅ 2609 / 0 fail / 0 err / 30 skip |
| CI 同款构建 | ✅ `mvn -Prelease clean package -pl core -am` BUILD SUCCESS（含 javadoc） |
| 端到端实证 | ✅ 900,857 ms → 3463–4061 ms |
| PR 状态 | ✅ open / mergeable=true / 4 files / +473 −1 |
| diff 一致性 | ✅ GitHub 的 `.diff` 与本地 `git diff HEAD^..HEAD` 逐行一致 |
