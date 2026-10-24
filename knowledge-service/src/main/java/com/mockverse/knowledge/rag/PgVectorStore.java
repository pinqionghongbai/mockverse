package com.mockverse.knowledge.rag;

import com.mockverse.knowledge.config.VectorProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.locks.ReentrantLock;

/**
 * pgvector 向量库：建表、批量入库、余弦相似度检索。
 *
 * <p><b>为什么自己持有连接池、而不声明一个 {@code DataSource} Bean</b>：
 * Spring Boot 的 {@code DataSourceAutoConfiguration} 是 {@code @ConditionalOnMissingBean(DataSource.class)}，
 * 一旦容器里多出第二个 DataSource，MySQL 的自动配置会整体退避，MyBatis-Plus 随之失去主数据源。
 * 要正经做多数据源，得手工把 MySQL 也声明出来并标 {@code @Primary}——为了一个只跑向量查询的库，
 * 这个代价不划算。所以这里把 PG 连接池当作**组件私有资源**管理，边界更清楚，也不干扰主库。
 *
 * <p><b>距离函数选余弦（{@code <=>}）</b>：bge 系列输出的是归一化向量，余弦距离等价于点积排序，
 * 且对文本长度不敏感——八股语料长短差异大，用欧氏距离会被长文本占便宜。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgVectorStore {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS knowledge_chunk (
              id         BIGSERIAL    PRIMARY KEY,
              category   VARCHAR(32)  NOT NULL,
              source     VARCHAR(128) NOT NULL,
              title      VARCHAR(256) NOT NULL,
              content    TEXT         NOT NULL,
              embedding  vector(%d)   NOT NULL,
              created_at TIMESTAMP    NOT NULL DEFAULT now()
            )
            """;

    /**
     * HNSW 索引。50 行数据其实用不上（顺序扫描更快），这里建出来是为了让 DDL 完整——
     * 语料涨到几千块时它就是必需品，而不是到时候再改表。
     */
    private static final String CREATE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_knowledge_embedding
              ON knowledge_chunk USING hnsw (embedding vector_cosine_ops)
            """;

    private static final String INSERT = """
            INSERT INTO knowledge_chunk (category, source, title, content, embedding)
            VALUES (?, ?, ?, ?, CAST(? AS vector))
            """;

    /** 1 - 余弦距离 = 余弦相似度，落在 [-1, 1]，实际语料通常 0.3~0.9。 */
    private static final String SEARCH = """
            SELECT category, source, title, content,
                   1 - (embedding <=> CAST(? AS vector)) AS score
              FROM knowledge_chunk
             ORDER BY embedding <=> CAST(? AS vector)
             LIMIT ?
            """;

    private final VectorProperties properties;
    private final EmbeddingModel embeddingModel;

    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;

    /**
     * 向量库是否可用。
     *
     * <p>pgvector 只服务知识库模块，面试官模块不依赖它。**不能让 PG 没起来就拖垮整个应用**——
     * 否则 D8/D9 那条"只 start MySQL 就能跑面试"的命令行流程会突然失效。
     * 所以这里降级为"启动成功但知识库不可用"，调用检索接口时才明确报错。
     */
    private volatile boolean available;

    /**
     * 上次尝试建连的时刻（毫秒）。用于惰性重连的退避。
     *
     * <p><b>为什么是 5 秒</b>（D15 七查实测）：退避窗口必须**大于**单次建连耗时（3 秒的
     * connectionTimeout），否则串行请求的间隔（≈3 秒）总会落在窗口外，每个请求都重新尝试，
     * 实测 3 个串行请求耗时 9.5 秒。改成 5 秒后：故障期 5 秒内只有第一个请求承担 3 秒，
     * 其余立即 503；pg 恢复后最坏等 5 秒即自愈。
     */
    private volatile long lastConnectAttemptAt;

    /** 惰性重连的退避窗口，必须大于 connectionTimeout（见 lastConnectAttemptAt 注释）。 */
    private static final long RECONNECT_BACKOFF_MILLIS = 5_000;

    /** 重连互斥（非阻塞）。见 {@link #connect()} 里"为什么用 tryLock"的说明。 */
    private final ReentrantLock connectLock = new ReentrantLock();

    /** 检索结果。score 越高越相关。 */
    public record Hit(String category, String source, String title, String content, double score) {}

    @PostConstruct
    void init() {
        connect();
    }

    /**
     * 建连 + 建表（幂等）。启动时调用一次；之后 pg 恢复时由 {@link #ensureAvailable()} 惰性重试。
     *
     * <p><b>为什么可重入</b>：D15 六查实测——冷启动时 pg 没起（available=false），
     * pg 之后起来时服务**不会自己恢复**，必须重启应用。容器编排不保证启动顺序，
     * 这是真实的可用性缺陷。所以把建连抽出来，失败后允许再试。
     *
     * <p><b>为什么用 tryLock 而不是 synchronized</b>（D15 七查）：建连最长要 3 秒
     * （connectionTimeout）。用 synchronized 时故障期的并发请求会**排队**——排队者拿到锁时
     * 1 秒退避窗口早已过去，于是每个都再触发一次 3 秒尝试，N 个并发请求总耗时 O(N×3s)，
     * 把一次依赖故障放大成雪崩。tryLock 让"已有线程在重连"时其余请求立即返回不可用
     * （快速 503），只有第一个请求承担这 3 秒。
     *
     * @return 建连后是否可用
     */
    private boolean connect() {
        if (available) {
            return true;
        }
        if (!connectLock.tryLock()) {
            return false;   // 已有线程在重连，不排队（见方法注释）
        }
        try {
            if (available) {
                return true;    // 双检：等锁期间别的线程可能已连上
            }
            long now = System.currentTimeMillis();
            if (now - lastConnectAttemptAt < RECONNECT_BACKOFF_MILLIS) {
                return false;   // 退避窗口内不重复尝试（见 lastConnectAttemptAt 注释）
            }
            lastConnectAttemptAt = now;

            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl("jdbc:postgresql://%s:%d/%s".formatted(
                    properties.getHost(), properties.getPort(), properties.getDatabase()));
            cfg.setUsername(properties.getUsername());
            cfg.setPassword(properties.getPassword());
            cfg.setMaximumPoolSize(4);          // 只服务检索，不需要大池
            cfg.setPoolName("pgvector-pool");
            cfg.setConnectionTimeout(3_000);    // 连不上就快速失败，不要卡启动
            cfg.setInitializationFailTimeout(3_000);

            HikariDataSource ds = null;
            try {
                // HikariDataSource 构造时就会建连接，连不上会直接抛 PoolInitializationException
                ds = new HikariDataSource(cfg);
                JdbcTemplate jt = new JdbcTemplate(ds);
                jt.execute("CREATE EXTENSION IF NOT EXISTS vector");
                jt.execute(CREATE_TABLE.formatted(properties.getDimensions()));
                jt.execute(CREATE_INDEX);
                dataSource = ds;
                jdbc = jt;
                available = true;
                log.info("pgvector 就绪: {}:{}/{} 维度={}",
                        properties.getHost(), properties.getPort(), properties.getDatabase(),
                        properties.getDimensions());
                return true;
            } catch (RuntimeException e) {
                // 池已建好但 DDL 失败（如权限不足）时 ds 尚未赋给字段，必须在这里关掉——
                // 否则每次重试都漏一个连接池，故障期会累积。
                if (ds != null) {
                    try {
                        ds.close();
                    } catch (RuntimeException ignored) {
                        // 关闭失败不掩盖原始异常
                    }
                }
                available = false;
                log.warn("pgvector 不可用，知识库相关接口将返回错误: {}。"
                        + "启动方式见 STATUS.md（docker start mockverse-pg）", e.getMessage());
                return false;
            }
        } finally {
            connectLock.unlock();
        }
    }

    /**
     * 惰性可用性检查：不可用时尝试重连一次（带退避）。
     *
     * <p>正常路径是 volatile 读短路，不碰锁；只有故障期才会进入 {@link #connect()}。
     */
    private boolean ensureAvailable() {
        return available || connect();
    }

    @PreDestroy
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * 当前是否可用（**会触发惰性重连**）。
     *
     * <p>health 端点靠它探活：若只读标志位，pg 恢复后 health 会一直显示 DEGRADED，
     * 直到有人调用检索接口——运维看不到"已经好了"。所以这里委托给 ensureAvailable()。
     */
    public boolean isAvailable() {
        return ensureAvailable();
    }

    public int count() {
        if (!ensureAvailable()) {
            return 0;
        }
        try {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM knowledge_chunk", Integer.class);
            return n == null ? 0 : n;
        } catch (DataAccessException e) {
            // 启动后 pgvector 宕掉：available 仍是 true，但查询会失败。
            // 转成 VectorStoreUnavailableException 让调用方拿到 503 语义。
            // **不**把 available 置 false：Hikari 会在下次调用时自动重连，毒化标志会让
            // "依赖恢复后必须重启应用"——D15 实测过 Hikari 惰性重连是自愈的。
            throw new VectorStoreUnavailableException("向量库连接失败: " + e.getMessage());
        }
    }

    /**
     * 全量重建。先 TRUNCATE 再批量插入——语料是静态的、几十条，全量重建比增量对账简单得多，
     * 也避免"改了语料但库里还是旧向量"这种最难查的问题。
     *
     * <p>顺序很重要：**先把所有向量算完，再动数据库**。反过来的话，embedding 中途失败会留下
     * 一张空表，而且没有事务保护（这里是两条独立语句，不是同一个事务）。
     *
     * <p><b>已知局限（D15 收口后复查，挂 STATUS）</b>：TRUNCATE 与 batchUpdate 之间没有事务，
     * 若 TRUNCATE 成功后 pg 立刻宕机，表会**空着**，此时 search 返回空列表而不是报错——
     * 调用方无法区分"库里没内容"和"知识库坏了"。窗口只有 batchUpdate 的几十毫秒，概率低且
     * reindex 可重试。不在此处补事务的原因：本服务刻意不注册 Spring 的 DataSource bean
     * （见类注释），拿不到 PlatformTransactionManager；手写 JDBC 事务要绕过 JdbcTemplate
     * 的连接管理，收益与复杂度不成比例。真要修应在 D21 引入统一事务基础设施时做。
     */
    public int replaceAll(List<KnowledgeChunk> chunks) {
        if (!ensureAvailable()) {
            throw new VectorStoreUnavailableException("向量库不可用，无法入库");
        }
        if (chunks.isEmpty()) {
            throw new IllegalStateException("没有可入库的知识块，检查 knowledge/*.md 是否存在");
        }

        List<Object[]> args = new ArrayList<>(chunks.size());
        for (KnowledgeChunk c : chunks) {
            Embedding e = embeddingModel.embed(c.embedText()).content();
            if (e.dimension() != properties.getDimensions()) {
                throw new IllegalStateException(
                        "embedding 维度 %d 与配置 %d 不一致".formatted(e.dimension(), properties.getDimensions()));
            }
            args.add(new Object[]{c.category(), c.source(), c.title(), c.content(), toLiteral(e)});
        }

        try {
            jdbc.execute("TRUNCATE TABLE knowledge_chunk RESTART IDENTITY");
            jdbc.batchUpdate(INSERT, args);
        } catch (DataAccessException e) {
            // 同 count()/search(): 运行期宕机 → 503 语义，不毒化 available。
            throw new VectorStoreUnavailableException("向量库连接失败: " + e.getMessage());
        }
        log.info("向量入库完成: {} 块", args.size());
        return args.size();
    }

    public List<Hit> search(String query, int topK) {
        if (!ensureAvailable()) {
            throw new VectorStoreUnavailableException("向量库不可用，无法检索");
        }
        Embedding q = embeddingModel.embed(query).content();
        String literal = toLiteral(q);
        try {
            return jdbc.query(SEARCH, (rs, i) -> new Hit(
                            rs.getString("category"),
                            rs.getString("source"),
                            rs.getString("title"),
                            rs.getString("content"),
                            rs.getDouble("score")),
                    literal, literal, topK);
        } catch (DataAccessException e) {
            // 同 count(): 运行期宕机 → 503 语义；不毒化 available，Hikari 自动重连。
            throw new VectorStoreUnavailableException("向量库连接失败: " + e.getMessage());
        }
    }

    /** pgvector 的文本输入格式：{@code [0.1,0.2,...]}。走文本而不是二进制，便于日志排查。 */
    private static String toLiteral(Embedding e) {
        StringJoiner sj = new StringJoiner(",", "[", "]");
        for (float v : e.vector()) {
            sj.add(Float.toString(v));
        }
        return sj.toString();
    }
}
