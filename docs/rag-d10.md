# D10 RAG 知识库技术记录

> 代码：`rag/` 包（`KnowledgeLoader` / `PgVectorStore` / `KnowledgeService` / `RagAnswerService`）
> 语料：`src/main/resources/knowledge/*.md`（7 文件 / 50 篇）
> 复现：`mvn spring-boot:run -Dspring-boot.run.profiles=rag`
> 记录时间：2026-10-31

---

## 一、为什么 embedding 不用 API

原计划是"LangChain4j 接 DeepSeek embedding"，开工第一步就被证伪：

| 路线 | 探测结果 | 结论 |
|---|---|---|
| DeepSeek `/embeddings` | `POST https://api.deepseek.com/embeddings` → **HTTP 404** | 该接口不存在，`/models` 是 200，说明不是鉴权问题 |
| Ollama `nomic-embed-text` | 卡在 `pulling manifest`，60 秒无进展 | 本机代理 fake-ip DNS 拦截 CDN，与 D1 的 qwen2.5 同一个问题 |
| **本地 ONNX（最终方案）** | `langchain4j-embeddings-bge-small-zh-v15:1.12.2-beta22`，jar 内自带模型 | 不联网、不需要第二个 Key、离线可复现 |

**这个转折本身就是素材**：面试里说"我用 DeepSeek 做 embedding"会被追问"DeepSeek 有 embedding 接口吗"，答不上来就露馅。说"我先探了接口发现没有，换成本地 ONNX"，是加分项。

### 版本对齐的坑

`langchain4j-embeddings-*` 走的是 **beta 版本线**，不是核心的 `1.12.2`：

```
dev.langchain4j:langchain4j-embeddings-bge-small-zh-v15:1.12.2-beta22
  └─ dev.langchain4j:langchain4j-embeddings:1.12.2-beta22
       └─ dev.langchain4j:langchain4j-core:1.12.2      ← 与项目一致
```

直接写 `1.12.2` 会 404。**beta 后缀对应的是核心版本号**（`1.12.2-beta22` ↔ core `1.12.2`），不是"更旧的版本"。

---

## 二、切分策略：按 `## ` 切，不做通用分割器

50 篇语料是手写的、格式统一的八股笔记：

```markdown
# Java 基础语料          ← 文件级说明，丢弃
> MockVerse RAG 语料库 · 分类 JAVA_BASIC · 共 10 篇   ← 元信息，丢弃

## HashMap 的扩容机制     ← 一个 chunk
正文……

## String 为什么设计成不可变  ← 另一个 chunk
正文……
```

`KnowledgeLoader` 只做一件事：遇到 `## ` 开新块，其余行累加到当前块。

**为什么不用 LangChain4j 的 `DocumentSplitters.recursive(...)`**：

| 方案 | 优点 | 缺点 |
|---|---|---|
| 通用递归分割（按字符数 + 重叠） | 适配任意文本 | 会把一个小节从中间劈开，检索到半截话 |
| **按 `## ` 切（本项目）** | 语义完整，一个块 = 一个可独立回答的问题 | 依赖格式约定，格式变了会切错 |

语料是自己写的，格式可控，所以选后者。**代价是必须让格式错误立刻暴露**——`flush()` 里对空小节 `log.warn` 而不是静默跳过，就是为了这个。

实测切分结果：`{CONCURRENCY=6, JAVA_BASIC=10, JVM=7, MYSQL=8, NETWORK=5, REDIS=7, SPRING=7}` = **50 块**，与语料标注的篇数逐一对上。

---

## 三、存储：pgvector

```sql
CREATE EXTENSION IF NOT EXISTS vector;   -- 0.8.7

CREATE TABLE knowledge_chunk (
  id         BIGSERIAL    PRIMARY KEY,
  category   VARCHAR(32)  NOT NULL,
  source     VARCHAR(128) NOT NULL,
  title      VARCHAR(256) NOT NULL,
  content    TEXT         NOT NULL,
  embedding  vector(512)  NOT NULL,      -- bge-small-zh-v1.5 输出 512 维
  created_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_knowledge_embedding
  ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);
```

### 三个设计选择

1. **余弦距离 `<=>`，不是欧氏距离**。bge 输出归一化向量，余弦对文本长度不敏感；八股语料长短差异大（有的 100 字有的 500 字），欧氏距离会被长文本占便宜。
2. **HNSW 索引现在用不上**。50 行数据顺序扫描更快，建索引纯属为将来准备。**不假装它现在有性能收益**。
3. **文本格式写入向量**（`CAST(? AS vector)`，形如 `[0.1,0.2,...]`）而不是二进制。慢一点点，但出问题时能在日志里直接看到数值——排查成本比那点性能重要。

### 为什么连接池由组件私有，不声明 DataSource Bean

Spring Boot 的 `DataSourceAutoConfiguration` 是 `@ConditionalOnMissingBean(DataSource.class)`。**只要容器里多出第二个 DataSource，MySQL 的自动配置就会整体退避**，MyBatis-Plus 随之失去主数据源。

要正经做多数据源，得把 MySQL 也手工声明并标 `@Primary`——为一个只跑向量查询的库付这个代价不划算。所以 `PgVectorStore` 自己 new 一个 `HikariDataSource`，`@PreDestroy` 关掉。边界清楚，也不干扰主库。

---

## 四、检索实测

| 查询 | 类型 | Top-1 结果 | 分数 |
|---|---|---|---|
| HashMap 的扩容机制是什么 | 术语原样 | `[JAVA_BASIC] HashMap 的扩容机制` | 0.7134 |
| 索引什么时候会失效 | **换一种说法**（语料原文是"最左前缀"） | `[MYSQL] 索引失效的常见场景` | 0.6987 |
| TCP 为什么要三次握手而不是两次 | 跨文件（50 篇里只有 network.md 有） | `[NETWORK] TCP 三次握手与四次挥手` | 0.7019 |

三条 Top-1 全中。**第二条最有价值**：查询里没有"最左前缀"四个字，靠的是语义匹配——这正是向量检索相对关键词搜索的意义。

### 分数分布的一个观察

同一批查询里，Top-1 与 Top-2 的差距是 0.13~0.22（0.70 vs 0.48~0.54）。差距够大，说明**语料之间区分度良好，没有出现"什么都像"的退化**。如果 Top-1 和 Top-3 都是 0.70 左右，就要怀疑 embedding 模型不适合这个领域了。

---

## 五、RAG 问答实测

**问题**：`HashMap 扩容的时候，元素的新位置是怎么算的？`

**召回**：[1] java-basic.md / HashMap 的扩容机制（0.7529）、[2] jvm.md / JVM 常用调优参数（0.5964）、[3] mysql.md / 为什么推荐自增主键（0.5568）

**回答**：

> HashMap 扩容时元素不需要重新计算 hash [1]。因为容量是 2 的幂，元素在新表中的位置只取决于 `hash & (oldCap - 1)` 和 `hash & oldCap` [1]。JDK8 用高低位链表拆分：按位与结果为 0 的元素留在原索引，结果为 1 的元素移动到 `原索引 + 原容量` [1]。
>
> （补充：扩容触发条件是元素数量超过 `容量 × 负载因子`，容量翻倍 [1]。）

**这三点说明 RAG 真的在工作**：

1. **内容来自资料**：`hash & (oldCap - 1)`、`原索引 + 原容量` 这些具体表达式在语料原文里，不是模型的通用记忆。
2. **引用编号对得上**：[1] 指向的就是 HashMap 那一篇。
3. **主动忽略了无关召回**：[2][3] 是相似度只有 0.5 的噪声，回答里一个都没提——说明"只依据资料回答"这条约束生效了，模型没有把召回的所有东西都塞进去凑字数。

### 提示词的三条硬约束

```
1. 每个结论后面用 [编号] 标注它来自哪条资料
2. 资料里没有提到的内容，直接说"资料未覆盖"，不要用自己的知识补充
3. 用 200 字以内讲清楚，不要罗列无关细节
```

第 2 条是 RAG 最重要的防线。**不加这条，模型会在召回失败时用自己的知识编一个看起来很专业的答案**——那比直接报错更危险，因为没人能发现它错了。

### 为什么用 temperature 0.2

RAG 的任务是"照着资料说"，不是"自由发挥"。0.7 时模型倾向于把检索片段改写成自己习惯的说法，引用就对不上了。所以 `DeepSeekModelConfig` 里现在有两个模型 Bean：

| Bean | temperature | 用途 |
|---|---|---|
| `deepSeekModel`（ADK `LangChain4j` 类型） | 0.7 | 面试官，需要表达上的发挥 |
| `ragChatModel`（LangChain4j `ChatModel` 类型） | 0.2 | RAG 问答，要贴着原文 |

两者类型不同，容器里不冲突，注入时不用 `@Qualifier`。

---

## 六、健壮性：pgvector 挂了不能拖垮整个应用

`PgVectorStore` 在 `@PostConstruct` 里连库。如果 PG 没起，直接抛异常会让**整个应用启动失败**——D8/D9 那条"只 start MySQL 就能跑面试"的流程会突然失效。

所以降级为 **fail-soft**：

```java
try {
    dataSource = new HikariDataSource(cfg);   // 构造时就建连接
    ...建表建索引...
    available = true;
} catch (RuntimeException e) {
    available = false;
    log.warn("pgvector 不可用，知识库相关接口将返回错误: {}", e.getMessage());
}
```

**实测**（`PG_PORT=5999` 指向一个没人监听的端口，跑 `interview` profile）：

```
WARN  PgVectorStore : pgvector 不可用，知识库相关接口将返回错误:
      Failed to initialize pool: Connection to 127.0.0.1:5999 refused.
INFO  MockVerseApplication : Started MockVerseApplication in 9.847 seconds
[1] 出题  题号=... 分类=JAVA_BASIC
[3] 面试官追问: 评价：翻倍和重新放置说对了，但没提触发条件……
[4] 会话历史落库检查: seq=1/2/3 全部落库
[INFO] BUILD SUCCESS
```

面试链路完全不受影响。

配套加了 `VectorStoreUnavailableException` → **503 Service Unavailable**（不是 409）。"依赖挂了"和"业务状态冲突"是两回事，调用方据此决定重试还是改参数。

---

## 七、局限（诚实清单）

1. **50 篇语料是我自己写的**，不是真实面试题库。它的价值在于"证明管线能跑通"，不在于"知识覆盖面"。
2. **bge-small-zh-v1.5 是 small 模型**（512 维、~24M 参数）。换成 bge-m3 或 bge-large 会更好，但 jar 更大、CPU 推理更慢，当前数据量下提升不明显。
3. **没有 rerank**。Top-3 里总有 1-2 条是噪声（0.5 左右）。加一个 cross-encoder rerank 能显著提升精度，D10 不做，记为可选增强。
4. **没有评估集**。三条查询全中只能说明"没坏"，不能说明"召回率 90%"。要做正经评估，需要人工标注 50 题 × 正确答案，这是 D27 之后的事。
5. **HNSW 索引没有实测收益**。50 行数据用不上，纯属为规模准备。
