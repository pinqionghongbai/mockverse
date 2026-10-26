# D24 部署文档：Docker Compose 一键拉起

> 对应计划：**D24 = Nginx + Docker Compose 一键拉起全家桶**，验收 = `docker compose up` 后浏览器完整走一遍流程。
> 一句话结论：**九个容器（五个基础设施 + 四个服务 + Nginx）一条命令起全，经 80 端口走完五段演示**。

---

## 一、快速开始

```bash
# 1. 准备环境变量（首次）
cp docker/.env.example .env
#    然后填 MYSQL_PASSWORD / PG_PASSWORD / DEEPSEEK_API_KEY
#    （.env 被 .gitignore 的 *.env 排除，不会入库）

# 2. 构建 jar（镜像里不跑 Maven，见第三节）
mvn package -DskipTests

# 3. 一键拉起
docker compose up -d

# 4. 等健康（约 60~90s：四个 Spring Boot 要拉 AgentCard、灌种子）
docker compose ps          # 九个都 healthy 即可

# 5. 验证
curl http://localhost/healthz/interview
```

浏览器/curl 走网关（80 端口）：

```bash
curl -X POST "http://localhost/api/interview/start?userId=1"
curl -N -X POST "http://localhost/api/interview/<sessionId>/answer/stream" \
  -H "Content-Type: application/json" --data-binary @docs/evidence/D22/fixtures/answer.json
```

---

## 二、容器清单

| 容器 | 镜像 | 端口 | 说明 |
|---|---|---|---|
| `mockverse-mysql` | mysql:8.0 | 3307→3306 | 五张表；**3307 不是笔误**（本机 3306 另有一个无关实例） |
| `mockverse-pg` | pgvector/pgvector:pg16 | 5433→5432 | `docker/pg/init/01-vector.sql` 负责 `CREATE EXTENSION vector` |
| `mockverse-kafka` | apache/kafka:3.9.0 | 9092 | **双 listener**（见第五节） |
| `mockverse-redis` | redis:7.2-alpine | 6379 | 题库缓存 / 会话锁 / SSE 限流（D23） |
| `mockverse-nacos` | nacos/nacos-server:v2.4.3 | 8848/9848/9849 | standalone，认证关闭 |
| `mockverse-knowledge` | 自建（JRE） | 8082 | RAG 知识库 |
| `mockverse-resume` | 自建（JRE） | 8083 | 简历诊断 |
| `mockverse-judge` | 自建（**JDK**） | 8084 | 要 `javac` 编译候选人代码 |
| `mockverse-interview` | 自建（JRE） | 8081 | 面试主流程 + A2A 编排 |
| `mockverse-nginx` | nginx:1.27-alpine | 80 | 统一入口 |

启动顺序由 `depends_on: condition: service_healthy` 保证：

```
mysql/pg/kafka/redis/nacos  →  knowledge/resume/judge  →  interview  →  nginx
```

**interview 必须最后**：它在 `@PostConstruct` 里就要拉 knowledge/resume 的 AgentCard，
拉不到直接启动失败（D16 建议 3 的 fail-fast）。

---

## 三、为什么镜像里不跑 Maven

`docker/service/Dockerfile` 只做两件事：装 curl（给 HEALTHCHECK 用）、COPY 已构建的 jar。

| 方案 | 代价 |
|---|---|
| 容器内 `mvn package`（多阶段构建） | 每次都要在容器里下一遍依赖（几百 MB）；16G 机器上 Maven + 四服务 + 五容器会吃满内存（D22 踩过进程被回收） |
| **宿主机 `mvn package` 后 COPY**（当前） | 构建前必须先本地打包 |

选了后者。代价是"必须先 `mvn package`"，这一条写在快速开始的第 2 步。

---

## 四、Nginx 路由表

| 路径 | 上游 | 备注 |
|---|---|---|
| `/healthz` | nginx 自己 | 容器 healthcheck |
| `/healthz/{interview,knowledge,resume,judge}` | 各服务的健康端点 | 三个服务都有 `/api/health`，靠这个前缀区分 |
| `/api/interview/` | interview:8081 | 含 `/start`、`/{id}/answer`、`/judge` 等 |
| `/api/knowledge/` | knowledge:8082 | |
| `/api/resume/` | resume:8083 | |
| `/api/health` | interview:8081 | 默认健康检查 |
| `/a2a/` | interview:8081 | A2A JSON-RPC |
| `/.well-known/agent-card.json` | interview:8081 | AgentCard |

**SSE 路径单独处理**（正则 location 优先于前缀匹配）：

```nginx
location ~ ^/api/interview/[^/]+/(answer|judge)/stream$ {
    proxy_buffering off;          # ← 不关这个，流会被攒在缓冲区一次性吐出
    proxy_cache off;
    chunked_transfer_encoding off;
    proxy_set_header Connection "";
    proxy_read_timeout 300s;      # interview 侧 SSE 上限 180s，留余量
}
```

实测（`docs/evidence/D23` 同款时序脚本）：**54 个 chunk / spread 0.91s / 最后 0.1s 只到达 8 个** ——
确认是逐字到达，不是缓冲后一次性吐出。

---

## 五、Kafka 为什么要两个 listener

```yaml
KAFKA_LISTENERS:          INTERNAL://:9094,EXTERNAL://:9092,CONTROLLER://:9093
KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:9094,EXTERNAL://localhost:9092,CONTROLLER://localhost:9093
```

- 服务**容器化**时连 `kafka:9094`（INTERNAL）
- 服务用 **`java -jar` 在宿主机**跑时连 `localhost:9092`（EXTERNAL）

只配一个的话，两种模式必有一种连不上。这是 Kafka 的经典坑：
**broker 返回给客户端的 `advertised.listeners` 才是客户端真正去连的地址**，
配错的表现是"能连上 broker、拿不到元数据、然后超时"。

D20~D23 的宿主机流程仍然可用，不需要改任何配置。

---

## 六、容器化的三个坑（D24 实测）

### 6.1 🔴 AgentCard 里的 `url` 是 `localhost`，跨容器必挂

**症状**：A2A 跨进程调用 1.3s 就 `failed`，interview 日志里是
`java.net.ConnectException`，但 `docker exec` 进去 `curl http://knowledge-service:8082/api/health` 是 200。

**根因**：`A2AServerConfig` 构造 AgentCard 时用的是
`@Value("${mockverse.a2a.public-url:http://localhost:8082}")`。interview 拉到卡片后，
**往卡片里的 url 发请求**——在 interview 容器里 `localhost` 指向它自己，8082 没人监听。

**修法**：给三个服务设 `MOCKVERSE_A2A_PUBLIC_URL` 指向容器内地址：

```yaml
MOCKVERSE_A2A_PUBLIC_URL: http://knowledge-service:8082
```

修后卡片 url 变成 `http://knowledge-service:8082/a2a/v1`，A2A 复测 `completed`（256 字）。

> **教训**：服务发现类协议里，"服务自己说的地址"和"调用方连的地址"是两个概念。
> 宿主机部署时它们恰好都是 localhost，所以这个问题在 D15-D23 一直没暴露。

### 6.2 🔴 knowledge-service 漏了 `DEEPSEEK_API_KEY`

启动即失败：

```
IllegalStateException: 缺少环境变量 DEEPSEEK_API_KEY, 无法初始化 DeepSeek 模型
```

四个服务里只有 judge 不需要模型（纯编译判题），另外三个都要。compose 里最初只给
resume/interview 配了，knowledge 漏掉——因为它看起来"只是向量检索"，
但 **Agentic RAG 的最终生成走 DeepSeek**（D12）。

### 6.3 nginx healthcheck 用 `localhost` 被拒

```
wget: can't connect to remote host: Connection refused    (FailingStreak=27)
```

nginx 只 `listen 0.0.0.0:80`（IPv4），而容器内 `localhost` 优先解析到 `::1`。
改成 `http://127.0.0.1/healthz` 后 healthy。

---

## 七、常用命令

```bash
# 只起基础设施（服务仍用 java -jar，开发时常用）
docker compose up -d mysql pg kafka redis nacos

# 看某个服务的日志
docker compose logs -f interview-service

# 重建单个服务（改了 jar 之后）
mvn -pl interview-service package -DskipTests
docker compose up -d --build interview-service

# 停止（数据卷保留）
docker compose down

# 连数据一起删（慎用）
docker compose down -v
```

---

## 八、D24 实测数字

| 项 | 结果 |
|---|---|
| 首次构建四个镜像 | knowledge 36s + 其余三个 35s（Docker 层缓存生效后） |
| `docker compose up -d`（已构建） | **34s** 起完九个容器 |
| 全部 healthy | 再等约 60s（四个 Spring Boot 拉卡片 + 灌种子） |
| `down` + `up` 后数据 | `question=13` / `chat_message` 递增 / `knowledge_chunk=50` **全部保留** |
| start（经网关） | 200 |
| SSE（经网关） | 54 chunk / spread 0.91s / **逐字到达** |
| 知识问答（跨进程） | 5.24s / 397 字 / 带 `[1][2]` |
| 同步判题（MCP 跨进程） | `matched=false, 812ms`，tool 事件到达 |
| 异步判题（Kafka） | 提交 85.6ms → `COMPLETED matched=true` |
| 简历诊断（跨进程） | 11.9s completed |
| 重启后链路 | start 200 / 知识 6.32s / 425 字 / 带引用 |

---

## 九、已知局限（如实标注）

- **镜像里不跑 Maven**：必须先本地 `mvn package`，不是"clone 即跑"（那是 D29 的目标）。
- **Nacos 认证关闭**（`NACOS_AUTH_ENABLE=false`）：配置内容外部可写。只在本地/内网使用；
  对外部署前必须开认证并改默认口令。
- **Redis 无密码**：同上。服务端 `REDIS_PASSWORD` 留空即对齐。
- **A2A 卡片 url 是容器内地址**：宿主机客户端若要用卡片里的 url 直连，需要另配一个对外地址。
  当前外部只走 HTTP API（经 nginx），不受影响。
- **没有资源上限**（`mem_limit` / `cpus`）：堆靠 `JAVA_TOOL_OPTIONS` 控制。
  真要限制容器内存，加 `mem_limit` 并确认 JVM 能正确感知（需要 `-XX:MaxRAMPercentage` 或显式 `-Xmx`）。
- **单机单实例**：没有副本、没有负载均衡策略（Nginx 直接 upstream 到容器名）。
- **未做镜像体积优化**：四个 jar 共约 870MB，镜像更大。多阶段构建 + jlink 可以压，
  但那是 D29 的事。
