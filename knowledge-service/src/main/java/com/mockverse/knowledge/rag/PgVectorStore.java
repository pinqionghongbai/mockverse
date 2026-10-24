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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

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

    /** 检索结果。score 越高越相关。 */
    public record Hit(String category, String source, String title, String content, double score) {}

    @PostConstruct
    void init() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://%s:%d/%s".formatted(
                properties.getHost(), properties.getPort(), properties.getDatabase()));
        cfg.setUsername(properties.getUsername());
        cfg.setPassword(properties.getPassword());
        cfg.setMaximumPoolSize(4);          // 只服务检索，不需要大池
        cfg.setPoolName("pgvector-pool");
        cfg.setConnectionTimeout(3_000);    // 连不上就快速失败，不要卡启动
        cfg.setInitializationFailTimeout(3_000);

        try {
            // HikariDataSource 构造时就会建连接，连不上会直接抛 PoolInitializationException
            dataSource = new HikariDataSource(cfg);
            jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            jdbc.execute(CREATE_TABLE.formatted(properties.getDimensions()));
            jdbc.execute(CREATE_INDEX);
            available = true;
            log.info("pgvector 就绪: {}:{}/{} 维度={} 现有块数={}",
                    properties.getHost(), properties.getPort(), properties.getDatabase(),
                    properties.getDimensions(), count());
        } catch (RuntimeException e) {
            available = false;
            log.warn("pgvector 不可用，知识库相关接口将返回错误: {}。"
                    + "启动方式见 STATUS.md（docker start mockverse-pg）", e.getMessage());
        }
    }

    @PreDestroy
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public int count() {
        if (!available) {
            return 0;
        }
        Integer n = jdbc.queryForObject("SELECT count(*) FROM knowledge_chunk", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * 全量重建。先 TRUNCATE 再批量插入——语料是静态的、几十条，全量重建比增量对账简单得多，
     * 也避免"改了语料但库里还是旧向量"这种最难查的问题。
     *
     * <p>顺序很重要：**先把所有向量算完，再动数据库**。反过来的话，embedding 中途失败会留下
     * 一张空表，而且没有事务保护（这里是两条独立语句，不是同一个事务）。
     */
    public int replaceAll(List<KnowledgeChunk> chunks) {
        if (!available) {
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

        jdbc.execute("TRUNCATE TABLE knowledge_chunk RESTART IDENTITY");
        jdbc.batchUpdate(INSERT, args);
        log.info("向量入库完成: {} 块", args.size());
        return args.size();
    }

    public List<Hit> search(String query, int topK) {
        if (!available) {
            throw new VectorStoreUnavailableException("向量库不可用，无法检索");
        }
        Embedding q = embeddingModel.embed(query).content();
        String literal = toLiteral(q);
        return jdbc.query(SEARCH, (rs, i) -> new Hit(
                        rs.getString("category"),
                        rs.getString("source"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getDouble("score")),
                literal, literal, topK);
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
