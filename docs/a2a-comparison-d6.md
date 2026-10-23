# D6 选型对比笔记：a2a-java 官方 SDK 的 0.3.x 与 1.x 两条版本线

> 目的：为章程「诚信红线」中的表述 *"对比评估过 A2A Java SDK 的 0.3.x 与 1.x 两条版本线"* 提供实证。
> 日期：2026-10-27（D6）｜**2026-10-28（D7）复查修正**｜实证方式：两者各自跑通服务端 + curl 打 JSON-RPC 报文
>
> ⚠️ **D7 复查修正声明**：本文初稿把 adk-java 内置 A2A 写成"Google 自带的独立实现"，**与 pom 证据不符**。
> 复查发现 `google-adk-a2a` 的 pom 直接依赖 `io.github.a2asdk:a2a-java-sdk-*:0.3.2.Final`，
> `RemoteA2AAgent` 里 import 的 `io.a2a.client.Client` / `io.a2a.spec.AgentCard` 全部来自该 SDK。
> **真相是：adk-java 的 A2A 能力 = ADK 集成层（`com.google.adk.a2a.*`）+ 官方 a2a-java SDK 0.3.2.Final。**
> 下面按修正后的事实重写。

---

## 一、实证环境

| 项 | adk-java 侧 | a2a-java 官方侧 |
|---|---|---|
| 版本 | google-adk 1.13.0 / google-adk-a2a 1.13.0 | org.a2aproject.sdk 1.4.0.Final |
| 底层 A2A SDK | **`io.github.a2asdk:a2a-java-sdk-*:0.3.2.Final`**（a2a 模块 pom:19）；示例 `examples/a2a_server` 覆写为 **0.3.0.Beta1** | 自身即 SDK（`org.a2aproject.sdk`，A2A 1.0 线） |
| Java 包命名空间 | `io.a2a.client.*` / `io.a2a.spec.*` | `org.a2aproject.sdk.client.*` / `org.a2aproject.sdk.spec.*` |
| 示例工程 | `adk-java/examples/a2a_server`（Quarkus 3.30.6，端口 9090） | `mockverse/labs/a2a-java-helloworld`（Quarkus 3.39.2，端口 9999） |
| Agent 实现 | ADK `LlmAgent`（DeepSeek）+ `FunctionTool` | 手写 `AgentExecutor`（固定返回 "Hello World"） |
| 实测结果 | ✅ 双轮跨进程协作通过 | ✅ JSON-RPC 调用返回 "Hello World" |

> 说明：a2a-java 克隆源码当前为 1.4.1.Final-SNAPSHOT（未发布），因此实证工程直接依赖已发布的 1.4.0.Final 制品，示例代码与官方 `examples/helloworld/server` 一致。

### 证据链（D7 复查新增）

```bash
# 1. adk-java a2a 模块确实依赖官方 SDK
$ grep -n "a2a.sdk.version" a2a/pom.xml
19:    <a2a.sdk.version>0.3.2.Final</a2a.sdk.version>

# 2. RemoteA2AAgent 用的是 SDK 的类，不是自研协议实现
$ grep "^import io.a2a" a2a/src/main/java/com/google/adk/a2a/agent/RemoteA2AAgent.java
import io.a2a.client.Client;
import io.a2a.client.ClientEvent;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Message;
...

# 3. 本地仓库里两个版本都在
$ ls ~/.m2/repository/io/github/a2asdk/a2a-java-sdk-client/
0.3.0.Beta1  0.3.2.Final
```

**结论：不存在"Google 自研的 A2A 实现"。** ADK 做的是集成层（Event ↔ Part 转换、AgentExecutor、RemoteA2AAgent），协议层用的是官方 SDK 的老版本。

---

## 二、核心差异对照表

> 注意：这不是"两个不同产品"的对比，而是**同一个官方 SDK 的两条大版本线**，外加 ADK 在其上做的集成。

| 维度 | adk-java 侧（SDK 0.3.x 线） | a2a-java 官方 1.x 线 |
|---|---|---|
| **协议版本** | A2A **0.3.0**（AgentCard 中 `protocolVersion: "0.3.0"`） | A2A **1.0**（AgentCard `supportedInterfaces[].protocolVersion: "1.0"`） |
| **JSON-RPC 方法名** | `message/send`、`message/stream`、`tasks/get`… | `SendMessage`、`SendStreamingMessage`、`GetTask`…（PascalCase） |
| **版本协商** | 无（版本只写在 AgentCard 里） | `A2A-Version` HTTP 头；**缺省回落 0.3**，不支持则报 `-32009 VERSION_NOT_SUPPORTED` |
| **报文结构** | 带 `kind` 判别字段：`kind: "message" / "task" / "text" / "data"` | protobuf JSON 映射：**无 `kind`**；`role: ROLE_USER / ROLE_AGENT`；`parts[].text` |
| **响应形态** | `result` = **task**（status + artifacts + history） | `result` = **message**（parts + messageId/contextId/taskId） |
| **Agent 接入成本** | 极低：ADK 提供 `AgentExecutor.Builder().agent(LlmAgent)` + `RemoteA2AAgent`，**几乎零适配** | 需自己实现 `AgentExecutor.execute/cancel`，手动 `emitter.sendMessage(...)` |
| **服务发现** | `/.well-known/agent-card.json`（扁平字段：`name/url/skills/capabilities`） | 同一 well-known 路径，但结构升级：`supportedInterfaces[]` 数组 + `preferredTransport` |
| **传输层** | **仅 Quarkus 示例**（`examples/a2a_server`）。**adk-java 1.13.0 里没有 Spring Boot 传输模块**（详见第四节纠错） | Quarkus reference 实现，JSON-RPC / gRPC / HTTP+JSON 三传输同仓 |
| **生态位置** | Agent 框架（ADK）对协议 SDK 的**集成层** | 协议层 SDK 本体（A2A 组织官方 Java 实现） |

---

## 三、最关键的结论：两条线**报文不互通**

同一个语义请求，两边的报文完全不同：

**adk-java 侧（0.3.0 线）**
```json
{
  "jsonrpc": "2.0", "id": "cli-check-2", "method": "message/send",
  "params": {"message": {"kind": "message", "role": "user",
             "parts": [{"kind": "text", "text": "Is 2 prime?"}]}}
}
```

**a2a-java 1.x 线**
```json
{
  "jsonrpc": "2.0", "id": "d6-check-4", "method": "SendMessage",
  "params": {"message": {"role": "ROLE_USER", "parts": [{"text": "hi"}]}}
}
```
外加 HTTP 头 `A2A-Version: 1.0`（缺省会被当成 0.3 而拒绝）。

实测证据（三次失败 → 成功）：
1. 向 a2a-java 服务发 `message/send` → `-32601 METHOD_NOT_FOUND`
2. 改用 `SendMessage` 但缺 `A2A-Version` 头 → `-32009 VERSION_NOT_SUPPORTED`
3. 补齐头与 schema 后 → `result.message.parts[0].text = "Hello World"` ✅

> a2a-java 1.x 侧提供 `compat-0.3` 模块做 v0.3 兼容（`VersionRouter` 按头路由），但 **adk-java 1.13.0 并未接入该兼容层**——它锁定的是 `io.github.a2asdk` 0.3.x 老线。

---

## 四、D7 复查纠错（两处）

### 纠错 1：`a2a/webservice` 模块不存在

`a2a/README.md` 的 "Module Boundaries" 一节写着：

> - `a2a/webservice` – Spring Boot transport module that imports the runtime library and exposes the JSON-RPC endpoint.
> - `com.google.adk.a2a.A2ASendMessageExecutor` 和 `A2AClient` …

**实际核查结果（adk-java 1.13.0，HEAD `aab51ef`）：**

```bash
$ ls a2a/
README.md  pom.xml  src  target          # 没有 webservice 子目录
$ find a2a/src/main -name "*.java" | wc -l
13                                        # 只有 agent/common/converters/executor 四包
$ grep -rin "spring" a2a/src a2a/pom.xml
(无输出)                                  # a2a 模块零 Spring 代码
$ grep -rn "A2ASendMessageExecutor\|class A2AClient" --include="*.java" .
(只有 A2AClientError 这个异常类)           # README 提到的两个类都不存在
```

**结论：`a2a/README.md` 描述的是比 1.13.0 更新（或不同分支）的代码结构，与当前 checkout 不符。**
（注：克隆为 `--depth 1` 浅克隆，无法用 git history 判断该模块是"从未存在"还是"后来新增"。）

**对项目的影响：**
- ❌ 计划文档里"adk-java 官方 Spring Boot 传输层"的说法**不成立**
- ✅ 但第三周微服务化**不受阻**——我们自己写 Spring Boot Controller 调用 `AgentExecutor`，反而是一个更好的简历故事（"手写 A2A 传输绑定"比"用官方模块"更能体现理解）
- ⚠️ D8 建 Spring Boot 工程时，A2A 那部分要**自己接**，不能指望官方模块

### 纠错 2：adk-java 的 A2A 不是"自研实现"

见第一节证据链。D3 审计文档 `docs/audit-s-level.md` 里"classpath 里不会自动出现 a2a-java"的判断是**错的**——它一直在 classpath 上，只是版本老（0.3.x）且包名不同（`io.a2a.*`）。

---

## 五、选型结论（MockVerse 的实际决策）

**主链路跟随 adk-java 集成的 0.3.x 线，a2a-java 1.x 作为升级参考。**

理由：
1. **集成成本**：ADK 的 `AgentExecutor` 直接吃 `LlmAgent`，A2A 化只需一个 `@Produces` Bean；1.x 线需要自己写执行器，Agent 逻辑与协议层耦合更紧。
2. **版本一致性**：MockVerse 的 Agent 编排在 ADK 里，跟着 ADK 的 SDK 版本走最省事；贸然升到 1.x 需要自己写 0.3 ↔ 1.0 的报文转换（或接入 `compat-0.3`）。
3. **a2a-java 1.x 的价值**：它是协议层的当前标准实现，适合第三周做"独立 A2A 服务"（不带 Agent 框架的场景，例如纯判题工具服务），也提供 Kafka 复制队列、OTel、JPA 任务存储等 extras，是架构参考。

**已知风险（面试可主动讲）**：ADK 1.13.0 锁在 SDK 0.3.x 老线（示例甚至降到 0.3.0.Beta1），上游升 1.x 时 MockVerse 要做一次报文迁移；这是"跟框架版本走"的代价。

---

## 六、简历话术（已满足诚信红线，D7 修正后）

> 「对比评估过 A2A Java SDK 的 0.3.x 与 1.x 两条版本线：adk-java 1.13.0 的 A2A 能力建立在官方 SDK 0.3.2.Final 之上（`io.a2a.*` 命名空间，方法名 `message/send`，无版本协商），而当前官方 1.4.0.Final 已升级到 A2A 1.0（`org.a2aproject.sdk.*`，PascalCase 方法名 + `A2A-Version` 头）。实测两条线报文不互通，最终按集成成本与版本跟随策略选择 ADK 集成的 0.3.x 线，并以 1.x 作为协议层参考。」

**注意**：不能说"adk-java 有自研 A2A 实现"——这是错的，会被懂行的面试官当场拆穿。

---

## 七、复现命令

```bash
# adk-java 侧（9090）—— 必须带 effective-model-builder 参数
cd E:/cluadeworkbase/adk-java/examples/a2a_server
../../mvnw quarkus:dev -Dquarkus.console.enabled=false -Dquarkus.bootstrap.effective-model-builder=true

# a2a-java 1.x 线（9999）
cd E:/cluadeworkbase/mockverse/labs/a2a-java-helloworld
E:/cluadeworkbase/adk-java/mvnw quarkus:dev -Dquarkus.console.enabled=false

# a2a-java 1.x 调用（注意方法与版本头）
curl -X POST http://localhost:9999 -H "Content-Type: application/json" -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":{"messageId":"m1","role":"ROLE_USER","parts":[{"text":"hi"}]}}}'
```
