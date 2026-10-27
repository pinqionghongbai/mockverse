# D26 部署：内网穿透（Cloudflare Tunnel）

> 目标：让**手机**能打开 MockVerse 并走完「开局 → 对话 → 判题」。
> 本机没有云服务器、也没装任何穿透客户端，选了**不需要账号**的 Cloudflare Quick Tunnel。
> 结论：**链路通了，手机可用**；但发现并修掉了一个只在穿透下暴露的真 bug，
> 还留下三条必须写清楚的限制（第八节）。

---

## 一、为什么是 Cloudflare Quick Tunnel

| 方案 | 需要什么 | 结论 |
|---|---|---|
| 云服务器 | 公网 IP 的机器 | 没有 |
| frp / ngrok / cpolar | 账号 + 服务端或 token | 没有，且要注册 |
| **cloudflared quick tunnel** | **只要一个二进制**，不需要账号 | ✅ 选了 |

代价：域名是**随机生成且每次重启都变**（`https://<随机词>.trycloudflare.com`），
且官方明说 quick tunnel **不承诺可用性、不用于生产**。用于演示/验收足够。

## 二、怎么起（三步，可复现）

```bash
mkdir -p .d26tmp/tunnel && cd .d26tmp/tunnel

# 1) 下载（本机 GitHub 直连可用；不通时用镜像）
curl -sL -o cloudflared.exe \
  https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe

# 2) 指向本地 nginx 的 80 端口
./cloudflared.exe tunnel --url http://localhost:80 --no-autoupdate > tunnel.log 2>&1 &

# 3) 取地址
grep -oE "https://[a-z0-9-]+\.trycloudflare\.com" tunnel.log | head -1
```

前置：`docker compose up -d` 十个容器全 healthy（见 `deploy-d24.md`）。
**只暴露 nginx 的 80**——MySQL/PG/Kafka/Redis/Nacos 的端口不要经隧道暴露。

## 三、验收实测（2026-11-16）

| 项 | 结果 |
|---|---|
| 首页 | `HTTP 200`，1.4~1.7s（含 TLS 握手） |
| 四个健康端点 | `/healthz/{interview,knowledge,resume,judge}` 全 200 |
| 开局 | 手机视口 390×844，`ALGORITHM · MEDIUM`，题目带样例 |
| 流式对话 | 逐字出现，评价 + 追问正常 |
| 知识路由 | 3 次里 2 次正确路由（第 3 次是已登记的 coordinator 摇摆，见 D13） |
| 判题 | 修复前 **60s**（等 SSE 超时）；修复后 **1.6s** |
| SSE 逐字（对话） | **6 次取样**：末 0.1s 占比 2%~60%；已知 spread 0.29~0.85s（1 次未记录 spread）→ **未被缓冲** |
| 隧道稳定性 | 15 分钟内掉线 1 次（QUIC 流被远端关闭），**4 秒自动重连** |

## 四、🔴 穿透下暴露的真 bug：判题结果要等 60 秒

### 现象

手机上点「提交判题」→ 判题 1.2 秒就完成了、服务端日志显示结果已推送，
但浏览器一直显示「已提交，等待判题结果…」，**整整 60 秒后**才出现结果。

### 定位过程（每一步都留了证据）

| 步 | 做法 | 结果 |
|---|---|---|
| 1 | 看服务端日志 | `judge-request 判题完成 matched=true 1174ms`；`judge-result 到达`；**没有**"推送失败" → 服务端认为推成功了 |
| 2 | 隧道侧 `curl -sN /judge/stream` | **24 秒 0 字节** |
| 3 | 同一条流走本地 nginx | **立刻**收到 310 字节 |
| 4 | 独立实验：自建最小 SSE 服务 + 独立隧道 | 见下 |

### 独立实验（排除"我们代码的问题"）

自建一个只发 SSE 的 Python 服务（`.d26tmp/probe/sse_probe_server.py`），三种端点：

| 端点 | 行为 | 经隧道的结果 |
|---|---|---|
| `/stream` | 每 200ms 发一条，共 20 条 | **立刻逐条到达** ✅ |
| `/small` | 首帧 12 字节，3s 后发一条，然后静默 30s | **33.8s 后一次性到达** ❌ |
| `/big` | 首帧 4096 字节，3s 后发一条，然后静默 | **同样被压住** ❌ |
| `/burst` | 1 秒内密集发 10 条，然后静默 15s | **17s 后一次性到达** ❌ |

**结论**：与响应体大小无关、与我们的事件格式无关——**Cloudflare 会把"开始后长时间没有
新数据"的流式响应整个缓冲，直到响应结束才转发**。聊天流没中招是因为 token 持续在流；
判题流在结果到达前**一个字节都没有**（唯一一条 `:connected` 注释帧也不够）。

### 修法（客户端并行轮询）

服务端能做的是"让流不要长时间静默"，但实验证明**先发 4KB 再静默照样被压**，
所以单靠服务端治不了。真正的修法是**别把 60 秒押在一条流上**：

`web/app.js` 里把判题结果改成**两条路并行、谁先到用谁**：

```
提交后同时启动：① SSE 结果流   ② 每秒一次的轮询（GET /judge/{submissionId}）
先到的那条渲染结果，另一条立刻停手
```

实测：**60s → 1.63s**。本地环境下 SSE 仍然先到，轮询被立即取消（不会多打接口）。

### 附带的两处改动（诚实标注：它们**没有**修好这个问题）

| 改动 | 有没有解决隧道缓冲 | 为什么保留 |
|---|---|---|
| 判题流加 `:connected` 首帧 + 每 15s 心跳 | **没有**（加了之后隧道仍 0 字节） | 防中间层空闲断连、让代理尽早拿到响应头；成本一条注释帧 |
| nginx SSE location `chunked_transfer_encoding on` | **没有** | SSE 本来就该分块传输；原先是 `off`（靠关闭连接定界），对直连无害、对中间层更糟 |

> 这两条属于"改对了但不够"，**不要把它们当成修复**。真正的修复是并行轮询。

## 五、另一个真 bug：知识库回答里的 `**` 原样显示

手机截图里满屏 `**Redis 过期删除策略**`、`- **惰性删除**`。

根因：气泡一直用 `textContent` 塞纯文本，而知识库 Agent 输出的是 Markdown。
验收标准是"非技术朋友能看懂"，满屏语法符号不合格。

修法：`web/app.js` 加 20 行 `mdToHtml`（**先转义 HTML 再套标签**），只支持
`**粗体**` / `` `代码` `` / `- 列表` / `# 标题` / 空行分段；流式期间仍按纯文本追加，
**流结束后统一渲染一次**（增量渲染会出"半个 `**`"的中间态）。

单测覆盖：`**未闭合` 原样保留、`<script>` 被转义、列表/标题/代码正常。

## 六、第三个真 bug：模型把分类传成"Java 基础"，工具连查 8 次空

A2A 链路上发"开始一场 Java 后端面试"，`pick_question` 的 artifact 里有 **8 个 part**
（= 4 次调用）。查库日志：模型依次传了 `Java 基础` / `Java` / `medium` 等，
每个都返回 0 行，最后才蒙对 `JAVA_BASIC`。

修法：`QuestionTools` 加入参归一化——规范值（含大小写/分隔符变体）+ 中英文别名表
（"Java 基础""算法""中等"…），**认不出来返回 null（不按该维度过滤）而不是原样透传**，
否则必然 0 行、模型只能继续试。

修复后：DB 查询 **1 次**，artifact 里 `pick_question` 从 8 个 part 降到 2 个（= 1 次调用）。

> 单测 `QuestionToolsTest` 当场抓到一个我自己引入的 bug：别名表分类/难度共用，
> "中等"传进 category 会被转成 `MEDIUM` 去查分类列——等于把要修的问题换个形式又造一遍。
> 修法是查完别名再校验归属（`canonical.contains(mapped)`）。

## 七、回归

| 项 | 结果 |
|---|---|
| MCP 判题 13 用例 | 12 正常 + 1 参数非法（与 D18/D24 基线一致） |
| A2A `message/stream` iv | `submitted → working → completed` |
| 对话 SSE 经隧道 | 6 次取样均逐字到达（末段占比最高 60%，spread 仍 0.29~0.85s） |
| 对话 SSE 经本地 nginx | 51 chunk / spread 0.60s / 末段 16% |
| 判题流本地 | 立刻收到 `:connected` + 结果 |
| `QuestionToolsTest` | 4/4 |

## 八、已知限制（必须写清楚）

1. **URL 是临时的**：quick tunnel 每次重启换域名，且官方不承诺可用性。演示前现起。
2. **隧道会掉线**：15 分钟内观测到 1 次 QUIC 流被远端关闭，4 秒自动重连。演示时若正好
   卡在这 4 秒，刷新页面即可。
3. **全项目仍无鉴权**（STATUS 6.2.2 三件事一件没做）：拿到 URL 的任何人可以驱动 Agent
   花 token、占判题并发。**这个地址不能长期挂着**，验收完就停掉。
4. **判题结果在穿透下走轮询**：SSE 那条路在 Cloudflare 下不可用（第四节）。功能等价，
   代价是每秒一次请求。
5. **未做真机实测**：用 390×844 的手机视口验证了布局与四条链路，**没有在真手机上点过**。
6. **只在 Windows + Docker Desktop 环境验证**。

## 九、停掉隧道

```bash
# 找到 cloudflared 的 PID 后单独结束（不要按进程名批量杀）
netstat -ano | grep -i cloudflared   # 或 tasklist | grep cloudflared
taskkill //F //PID <pid>
```
